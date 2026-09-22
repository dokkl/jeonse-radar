package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 집계 스냅샷 하나를 처음부터 끝까지 만든다.
 *
 * 실행 순서는 고정이다: 시세 -> 만기 -> 플래그.
 * 만기 예측이 현재 시세를 조인하므로 뒤집을 수 없다.
 *
 * 빌드가 실패하면 스냅샷을 FAILED로 표시하고 예외를 다시 던진다.
 * 조회 계층은 COMPLETE만 보므로 직전 스냅샷이 계속 서비스된다 (스펙 §1.7).
 */
@Service
public class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final RentStatBuilder rentStatBuilder;
    private final MaturityForecastBuilder maturityForecastBuilder;
    private final DealFlagBuilder dealFlagBuilder;
    private final MetricSnapshotRepository snapshotRepository;
    private final JdbcTemplate jdbcTemplate;

    public SnapshotBuilder(RentStatBuilder rentStatBuilder,
                           MaturityForecastBuilder maturityForecastBuilder,
                           DealFlagBuilder dealFlagBuilder,
                           MetricSnapshotRepository snapshotRepository,
                           JdbcTemplate jdbcTemplate) {
        this.rentStatBuilder = rentStatBuilder;
        this.maturityForecastBuilder = maturityForecastBuilder;
        this.dealFlagBuilder = dealFlagBuilder;
        this.snapshotRepository = snapshotRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * **의도적으로 @Transactional을 붙이지 않는다.** 두 가지 이유가 있다.
     *
     * 첫째, 실패 기록이 살아남아야 한다. 하나의 트랜잭션으로 감싸면 catch 블록의
     * snapshot.fail() + save()가 곧바로 던지는 예외에 함께 롤백되어, 실패 원인을
     * 남기려고 쓴 코드가 스스로를 지운다.
     *
     * 둘째, 전국 규모에서는 수백만 행 삽입을 한 트랜잭션이 감싸게 되어 언두 로그가
     * 부풀고 빌드 내내 락을 쥔다.
     *
     * 원자성은 트랜잭션이 아니라 metric_snapshot의 status가 보장한다. 중간에 실패하면
     * 그 snapshot_date의 행이 일부 남지만 COMPLETE가 아니므로 아무도 읽지 않고,
     * 다음 빌드의 clearExisting이 지운다 (스펙 §1.7).
     */
    public MetricSnapshot build(LocalDate asOf) {
        LocalDate snapshotDate = asOf;
        MetricSnapshot snapshot = snapshotRepository.findBySnapshotDate(snapshotDate)
                .orElseGet(() -> MetricSnapshot.start(snapshotDate));

        // 재빌드 시작을 여기서 커밋해 둔다. findBySnapshotDate가 돌려준 기존 행은
        // (같은 날 재빌드라면) 여전히 COMPLETE 상태다. 이걸 커밋하지 않고 바로
        // clearExisting으로 넘어가면, 그 사이 조회 계층이 이 snapshot_date를 COMPLETE로
        // 읽으면서 이미 지워진/반쪽인 집계 테이블을 서비스하는 창이 생긴다 (원장 F-33).
        // @Transactional이 없으므로 이 save()는 즉시 커밋되어 다른 커넥션에서 보인다.
        snapshot.restart();
        snapshot = snapshotRepository.save(snapshot);

        try {
            clearExisting(snapshotDate);

            int rentStatRows = rentStatBuilder.build(snapshotDate, asOf);
            int maturityRows = maturityForecastBuilder.build(
                    snapshotDate, YearMonth.from(asOf).format(YM));
            int dealFlagRows = dealFlagBuilder.build(snapshotDate, asOf);

            snapshot.complete(rentStatRows, maturityRows, dealFlagRows);
            log.info("집계 스냅샷 완료 {}: 시세 {}행, 만기 {}행, 플래그 {}행",
                    snapshotDate, rentStatRows, maturityRows, dealFlagRows);
        } catch (RuntimeException e) {
            try {
                snapshot.fail(e.toString());
                snapshotRepository.save(snapshot);
            } catch (RuntimeException saveException) {
                // 실패 기록 저장 자체가 실패하면(같은 DB 장애일 가능성이 높다) 원래
                // 예외를 이걸로 덮지 않는다. 원인은 suppressed로 붙이고 원래 e를 던진다.
                e.addSuppressed(saveException);
            }
            log.error("집계 스냅샷 실패 {}: {}", snapshotDate, e.toString());
            throw e;
        }

        return snapshotRepository.save(snapshot);
    }

    /** 같은 날 재빌드하면 이전 결과를 지우고 다시 만든다. 부분 중복을 남기지 않는다. */
    private void clearExisting(LocalDate snapshotDate) {
        jdbcTemplate.update("DELETE FROM maturity_forecast WHERE snapshot_date = ?", snapshotDate);
        jdbcTemplate.update("DELETE FROM complex_rent_stat WHERE snapshot_date = ?", snapshotDate);
        jdbcTemplate.update("DELETE FROM deal_flag WHERE snapshot_date = ?", snapshotDate);
    }
}
