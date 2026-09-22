package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.entity.SnapshotStatus;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SnapshotBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    SnapshotBuilder builder;

    @Autowired
    MetricSnapshotRepository snapshotRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MaturityForecastBuilder maturityForecastBuilder;

    @Autowired
    DealFlagBuilder dealFlagBuilder;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM maturity_forecast");
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM deal_flag");
        jdbcTemplate.update("DELETE FROM metric_snapshot");
        jdbcTemplate.update("DELETE FROM rent_deal");
        jdbcTemplate.update("DELETE FROM trade_deal");
        jdbcTemplate.update("DELETE FROM complex");
    }

    private void seedOneComplexWithLease() {
        jdbcTemplate.update(
                "INSERT INTO complex (id, region_code, apt_seq, legal_dong, jibun, display_name, slug)"
                        + " VALUES (1,'11680','11680-1','대치동','1','단지','slug-1')");
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES ('seed-1',1,'11680-1','11680','대치동','1','단지',85,85,3,"
                        + " ?,500000000,0,'202812',FALSE,FALSE,NOW(),NOW())",
                LocalDate.now().minusMonths(1));
    }

    @Test
    void 빌드가_끝나면_스냅샷이_COMPLETE로_표시된다() {
        seedOneComplexWithLease();

        MetricSnapshot s = builder.build(LocalDate.now());

        assertThat(s.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(s.getCompletedAt()).isNotNull();
    }

    @Test
    void 완료된_스냅샷만_조회_대상이_된다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());

        // 빌드 중인 스냅샷을 직접 심는다 — 더 최신이지만 COMPLETE가 아니다
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at)"
                        + " VALUES (?, 'BUILDING', NOW())", LocalDate.now().plusDays(1));

        MetricSnapshot latest = snapshotRepository.findLatestComplete().orElseThrow();

        assertThat(latest.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(latest.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
    }

    @Test
    void 실패한_스냅샷도_조회_대상이_아니다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());

        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, failure_reason)"
                        + " VALUES (?, 'FAILED', NOW(), '테스트')", LocalDate.now().plusDays(1));

        assertThat(snapshotRepository.findLatestComplete().orElseThrow().getSnapshotDate())
                .isEqualTo(LocalDate.now());
    }

    @Test
    void 완료된_스냅샷이_하나도_없으면_비어있다() {
        assertThat(snapshotRepository.findLatestComplete()).isEmpty();
    }

    @Test
    void 스냅샷에_각_테이블의_행수가_기록된다() {
        seedOneComplexWithLease();

        MetricSnapshot s = builder.build(LocalDate.now());

        assertThat(s.getRentStatRows()).isEqualTo(1);
        assertThat(s.getMaturityRows()).isEqualTo(1);
        assertThat(s.getDealFlagRows()).isZero();
    }

    @Test
    void 같은_날_다시_빌드하면_이전_행을_지우고_다시_만든다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());
        builder.build(LocalDate.now());

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE snapshot_date = ?",
                Integer.class, LocalDate.now());

        assertThat(n).isEqualTo(1);
    }

    @Test
    void 빌드가_실패하면_FAILED_기록이_남는다() {
        // complex_rent_stat 테이블을 통째로 이름 바꿔 없애서 clearExisting(정리 단계)의
        // DELETE가 예외를 던지게 만든다. 이 테스트는 SnapshotBuilder.build()에서
        // clearExisting()이 try 블록 "안"에 있어야 한다는 것의 유일한 회귀 방지선이다 —
        // 나중에 누군가 "더 간단하게" 정리 단계를 try 밖으로 옮기면 이 테스트가 깨진다.
        jdbcTemplate.update("DROP TABLE IF EXISTS complex_rent_stat_backup");
        jdbcTemplate.execute("RENAME TABLE complex_rent_stat TO complex_rent_stat_backup");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(LocalDate.now()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("RENAME TABLE complex_rent_stat_backup TO complex_rent_stat");
        }

        // 핵심: 예외가 다시 던져졌어도 FAILED 기록이 살아남아야 한다.
        // 오케스트레이터가 @Transactional이면 이 기록이 함께 롤백되어 사라진다.
        MetricSnapshot s = snapshotRepository.findBySnapshotDate(LocalDate.now()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SnapshotStatus.FAILED);
        assertThat(s.getFailureReason()).isNotBlank();
    }

    @Test
    void 빌드_로직_수행_중_예외가_나도_FAILED_기록이_남는다() {
        // 위 테스트는 clearExisting(정리 단계)에서 실패를 유도한다.
        // 이 테스트는 세 빌더 중 하나가 실제로 던지는 경우를 별도로 고정한다.
        RentStatBuilder failingRentStatBuilder = mock(RentStatBuilder.class);
        when(failingRentStatBuilder.build(any(), any()))
                .thenThrow(new RuntimeException("시세 집계 실패"));

        SnapshotBuilder failingBuilder = new SnapshotBuilder(
                failingRentStatBuilder, maturityForecastBuilder, dealFlagBuilder,
                snapshotRepository, jdbcTemplate);

        assertThatThrownBy(() -> failingBuilder.build(LocalDate.now()))
                .isInstanceOf(RuntimeException.class);

        MetricSnapshot s = snapshotRepository.findBySnapshotDate(LocalDate.now()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SnapshotStatus.FAILED);
        assertThat(s.getFailureReason()).contains("시세 집계 실패");
    }

    @Test
    void 재빌드_도중에는_이전_완료_스냅샷이_계속_조회된다() {
        // 원장 F-33: findBySnapshotDate가 기존 행을 그대로 돌려줄 뿐 상태를 건드리지
        // 않으면, 같은 날을 재빌드하는 동안 metric_snapshot은 여전히 COMPLETE인 채로
        // clearExisting이 그 날짜의 집계 행을 지운다 — 그 창에서 조회 계층이 오늘을
        // COMPLETE로 읽고 비어있는(혹은 반쪽인) 테이블을 서비스하게 된다.
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // 어제 완료된 스냅샷을 직접 심는다 — 재빌드 중에도 계속 서비스돼야 하는 대상.
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, completed_at)"
                        + " VALUES (?, 'COMPLETE', NOW(), NOW())", yesterday);

        // 오늘을 한 번 완료해 둔다 — 이제 오늘도 COMPLETE다.
        seedOneComplexWithLease();
        builder.build(today);

        // 오늘을 재빌드한다. rentStatBuilder.build()가 불리는 시점(=clearExisting
        // 직후)에 조회 계층이 실제로 보게 될 최신 COMPLETE 스냅샷의 날짜를 관찰한다.
        AtomicReference<LocalDate> observedDate = new AtomicReference<>();
        RentStatBuilder observingRentStatBuilder = mock(RentStatBuilder.class);
        when(observingRentStatBuilder.build(any(), any())).thenAnswer(invocation -> {
            observedDate.set(snapshotRepository.findLatestComplete()
                    .map(MetricSnapshot::getSnapshotDate)
                    .orElse(null));
            return 0;
        });

        SnapshotBuilder rebuildingBuilder = new SnapshotBuilder(
                observingRentStatBuilder, maturityForecastBuilder, dealFlagBuilder,
                snapshotRepository, jdbcTemplate);

        rebuildingBuilder.build(today);

        // 재빌드 도중 조회 계층은 오늘(반쪽/빈 상태)이 아니라 어제를 봐야 한다.
        assertThat(observedDate.get()).isEqualTo(yesterday);
    }

    @Test
    void 같은_날_재빌드해도_스냅샷_행이_늘지_않는다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());
        builder.build(LocalDate.now());

        assertThat(snapshotRepository.count()).isEqualTo(1);
    }
}
