package com.jeonseradar.web.complex;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ComplexPageQueryTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired ComplexPageQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void 발행된_스냅샷을_심는다() {
        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 1, 0);
        snapshots.save(s);
    }

    private void complex(long id, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, '11710', '잠실동', '40', ?, ?, 2018)
                """, id, "11710-" + id, name, id + "-단지-11710-1");
    }

    private void stat(long complexId, int bucket, long deposit, int n, String level, String conf) {
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT, complexId, bucket, deposit, n, level, conf);
    }

    private void reduction(long complexId, int bucket, int reductionCount, long medianReduction,
                           LocalDate latestReductionDate) {
        jdbc.update("""
                INSERT INTO renewal_reduction_stat (snapshot_date, complex_id, area_bucket,
                        renewal_count, reduction_count, median_reduction, latest_reduction_date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT, complexId, bucket, reductionCount, reductionCount, medianReduction,
                latestReductionDate);
    }

    @Test
    void 단지가_없으면_비어있다() {
        assertThat(query.find(999L)).isEmpty();
    }

    @Test
    void 평형별_시세를_넓은_평형_순으로_준다() {
        complex(1, "헬리오시티");
        stat(1, 59, 700_000_000L, 12, "L1", "HIGH");
        stat(1, 84, 950_000_000L, 30, "L1", "HIGH");

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.displayName()).isEqualTo("헬리오시티");
        assertThat(page.regionName()).isEqualTo("송파구");
        assertThat(page.snapshotDate()).isEqualTo(SNAPSHOT);
        assertThat(page.areaStats()).extracting(ComplexPage.AreaStat::areaBucket)
                .containsExactly(84, 59);
        assertThat(page.areaStats().get(0).medianDeposit()).isEqualTo(950_000_000L);
    }

    @Test
    void 다른_날짜_스냅샷의_행은_섞이지_않는다() {
        complex(1, "헬리오시티");
        stat(1, 84, 950_000_000L, 30, "L1", "HIGH");
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES ('2026-09-21', 1, 59, 1, 1, 'L1', 'HIGH')
                """);

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.areaStats()).hasSize(1);
        assertThat(page.areaStats().get(0).areaBucket()).isEqualTo(84);
    }

    @Test
    void 만기_행을_만기월_순으로_준다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 30, "L1", "HIGH");
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, 1, 84, '202612', 5, 1, 1000000000, 900000000, 100000000, 0.1, 2, 'MEDIUM'),
                       (?, 1, 84, '202611', 3, 0,  950000000, 900000000,  50000000, 0.05, 1, 'MEDIUM')
                """, SNAPSHOT, SNAPSHOT);

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.maturities()).extracting(ComplexPage.MaturityRow::maturityYm)
                .containsExactly("202611", "202612");
        assertThat(page.maturities().get(0).gapAmount()).isEqualTo(50_000_000L);
    }

    @Test
    void MEDIUM_이상_시세가_하나라도_있으면_색인_대상이다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 2, "L3", "LOW");
        stat(1, 59, 700_000_000L, 5, "L1", "MEDIUM");

        assertThat(query.find(1L).orElseThrow().indexable()).isTrue();
    }

    @Test
    void 전부_LOW면_색인_대상이_아니다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 2, "L3", "LOW");
        stat(1, 59, 700_000_000L, 1, "L3", "LOW");

        assertThat(query.find(1L).orElseThrow().indexable()).isFalse();
    }

    @Test
    void 시세가_아예_없으면_색인_대상이_아니다() {
        complex(1, "헬리오시티");

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.areaStats()).isEmpty();
        assertThat(page.indexable()).isFalse();
    }

    @Test
    void 이름이_지번뿐이면_색인_대상이_아니다() {
        complex(1, "(828-76)");
        stat(1, 84, 900_000_000L, 30, "L1", "HIGH");

        assertThat(query.find(1L).orElseThrow().indexable()).isFalse();
    }

    @Test
    void 감액_이력을_넓은_평형_순으로_준다() {
        complex(1, "헬리오시티");
        reduction(1, 59, 5, 30_000_000L, LocalDate.of(2024, 6, 1));
        reduction(1, 84, 3, 50_000_000L, LocalDate.of(2025, 3, 15));

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.reductions()).extracting(ComplexPage.ReductionRow::areaBucket)
                .containsExactly(84, 59);
        ComplexPage.ReductionRow row84 = page.reductions().get(0);
        assertThat(row84.reductionCount()).isEqualTo(3);
        assertThat(row84.medianReduction()).isEqualTo(50_000_000L);
        assertThat(row84.latestReductionDate()).isEqualTo(LocalDate.of(2025, 3, 15));
    }

    @Test
    void 다른_날짜_스냅샷의_감액_행은_섞이지_않는다() {
        complex(1, "헬리오시티");
        reduction(1, 84, 3, 50_000_000L, LocalDate.of(2025, 3, 15));
        jdbc.update("""
                INSERT INTO renewal_reduction_stat (snapshot_date, complex_id, area_bucket,
                        renewal_count, reduction_count, median_reduction, latest_reduction_date)
                VALUES ('2026-09-21', 1, 59, 1, 1, 10000000, '2024-01-01')
                """);

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.reductions()).hasSize(1);
        assertThat(page.reductions().get(0).areaBucket()).isEqualTo(84);
    }

    @Test
    void 감액_이력이_없으면_빈_목록이다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 30, "L1", "HIGH");

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.reductions()).isEmpty();
    }
}
