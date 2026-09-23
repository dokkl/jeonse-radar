package com.jeonseradar.ingest.service;

import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DataQualityReporterTest extends IntegrationTestSupport {

    @Autowired
    DataQualityReporter reporter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private void insertRent(String hash, boolean estimated, long monthlyRent, boolean renewalUsed) {
        jdbcTemplate.update("""
                INSERT INTO rent_deal
                  (deal_hash, complex_id, region_code, legal_dong, jibun, apt_name,
                   area, area_bucket, floor, deal_date, deposit, monthly_rent,
                   build_year, contract_type, contract_term_raw, term_start_ym, term_end_ym,
                   term_estimated, renewal_right_used, created_at, updated_at)
                VALUES (?,1,'11110','청운동','1','단지',84.97,85,3,'2026-03-14',
                        500000000,?,2005,'신규','',NULL,NULL,?,?, NOW(), NOW())
                """, hash, monthlyRent, estimated, renewalUsed);
    }

    @Test
    void 추정_비율을_계산한다() {
        insertRent("q1", true, 0, false);
        insertRent("q2", false, 0, false);
        insertRent("q3", false, 0, false);
        insertRent("q4", false, 0, false);

        var report = reporter.report();

        assertThat(report.totalRent()).isEqualTo(4);
        assertThat(report.estimatedTerm()).isEqualTo(1);
        assertThat(report.estimatedRatio()).isEqualTo(0.25);
    }

    @Test
    void 순수전세만_센다() {
        insertRent("q5", false, 0, false);
        insertRent("q6", false, 800000, false);

        var report = reporter.report();

        assertThat(report.pureJeonse()).isEqualTo(1);
    }

    @Test
    void 갱신권_사용건을_센다() {
        insertRent("q7", false, 0, true);
        insertRent("q8", false, 0, false);

        var report = reporter.report();

        assertThat(report.renewalRightUsed()).isEqualTo(1);
    }

    @Test
    void 데이터가_없으면_비율은_0이다() {
        var report = reporter.report();

        assertThat(report.estimatedRatio()).isZero();
    }
}
