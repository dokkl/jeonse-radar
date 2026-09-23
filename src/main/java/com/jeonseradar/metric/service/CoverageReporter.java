package com.jeonseradar.metric.service;

import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 3단계가 실제로 발행할 수 있는 페이지가 몇 장인지 잰다.
 *
 * 스펙 §4.1이 confidence = LOW면 noindex라고 정했으므로, 색인 가능한 단지 수가
 * 곧 3단계가 만들 수 있는 검색 유입의 상한이다. 이 리포터는 스냅샷마다 그 숫자를
 * 다시 잴 수 있게 만들어, 손으로 짠 SQL 한 번으로 끝내지 않고 매 스냅샷을
 * 재현 가능하게 보고한다.
 *
 * 단지 단위 판정 기준: 그 단지의 평형 중 LOW가 아닌 것이 하나라도 있으면 색인 가능.
 * 단지 페이지는 여러 평형을 함께 보여주므로, 쓸 만한 평형이 하나라도 있으면
 * 페이지에 담을 내용이 있다.
 */
@Service
public class CoverageReporter {

    private static final Logger log = LoggerFactory.getLogger(CoverageReporter.class);

    public record CoverageReport(
            long totalComplexes,
            long indexable,
            double indexableRatio,
            long maturityRows,
            long lowConfidenceRows,
            long flaggedDeals
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MetricSnapshotRepository snapshotRepository;

    public CoverageReporter(JdbcTemplate jdbcTemplate,
                            MetricSnapshotRepository snapshotRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
    }

    public CoverageReport report() {
        var snapshot = snapshotRepository.findLatestComplete().orElse(null);
        if (snapshot == null) {
            log.warn("완료된 집계 스냅샷이 없어 커버리지를 계산할 수 없다");
            return new CoverageReport(0, 0, 0.0, 0, 0, 0);
        }
        LocalDate d = snapshot.getSnapshotDate();

        long total = count(
                "SELECT COUNT(DISTINCT complex_id) FROM complex_rent_stat WHERE snapshot_date = ?", d);
        long indexable = count(
                "SELECT COUNT(DISTINCT complex_id) FROM complex_rent_stat "
                        + "WHERE snapshot_date = ? AND confidence <> 'LOW'", d);
        long maturityRows = count(
                "SELECT COUNT(*) FROM maturity_forecast WHERE snapshot_date = ?", d);
        long lowRows = count(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE snapshot_date = ? AND confidence = 'LOW'", d);
        long flagged = count(
                "SELECT COUNT(DISTINCT deal_hash) FROM deal_flag WHERE snapshot_date = ?", d);

        double ratio = total == 0 ? 0.0 : (double) indexable / total;
        CoverageReport report = new CoverageReport(
                total, indexable, ratio, maturityRows, lowRows, flagged);
        log.info("발행 커버리지 ({}): {}", d, report);
        return report;
    }

    private long count(String sql, LocalDate snapshotDate) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, snapshotDate);
        return v == null ? 0L : v;
    }
}
