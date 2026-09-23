package com.jeonseradar.metric.service;

import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CoverageReporterTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    CoverageReporter reporter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCompleteSnapshot() {
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, completed_at)"
                        + " VALUES (?, 'COMPLETE', NOW(), NOW())", SNAPSHOT);
    }

    private void stat(long complexId, int bucket, String confidence) {
        jdbcTemplate.update(
                "INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,"
                        + " median_deposit, sample_count, source_level, confidence)"
                        + " VALUES (?,?,?,500000000,5,'L1',?)",
                SNAPSHOT, complexId, bucket, confidence);
    }

    @Test
    void 단지에_LOW가_아닌_평형이_하나라도_있으면_색인_가능이다() {
        stat(1, 85, "LOW");
        stat(1, 60, "HIGH");

        assertThat(reporter.report().indexable()).isEqualTo(1);
    }

    @Test
    void 모든_평형이_LOW인_단지는_색인_불가다() {
        stat(2, 85, "LOW");
        stat(2, 60, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(1);
        assertThat(r.indexable()).isZero();
    }

    @Test
    void 색인_가능_비율을_계산한다() {
        stat(3, 85, "HIGH");
        stat(4, 85, "MEDIUM");
        stat(5, 85, "LOW");
        stat(6, 85, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(4);
        assertThat(r.indexable()).isEqualTo(2);
        assertThat(r.indexableRatio()).isEqualTo(0.5);
    }

    @Test
    void 완료된_스냅샷이_없으면_전부_0이다() {
        jdbcTemplate.update("DELETE FROM metric_snapshot");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }

    @Test
    void 단지가_하나도_없으면_비율이_0이고_0으로_나누지_않는다() {
        var r = reporter.report();

        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }
}
