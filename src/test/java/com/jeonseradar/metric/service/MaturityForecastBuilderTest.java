package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MaturityForecastBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    MaturityForecastBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM maturity_forecast");
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM rent_deal");
    }

    /** 현재 시세를 직접 심는다. Task 2의 산출물을 흉내 내는 것이다. */
    private void currentStat(long complexId, int areaBucket, long median, String confidence) {
        jdbcTemplate.update(
                "INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,"
                        + " median_deposit, sample_count, source_level, confidence)"
                        + " VALUES (?,?,?,?,5,'L1',?)",
                SNAPSHOT, complexId, areaBucket, median, confidence);
    }

    /** 만기가 termEndYm인 순수 전세 한 건. */
    private void lease(long complexId, int areaBucket, long deposit, String termEndYm,
                       boolean estimated, boolean renewalUsed) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES (?,?,'11680-1','11680','대치동','1','단지',?,?,3,'2024-12-01',?,0,?,?,?,NOW(),NOW())",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                complexId, java.math.BigDecimal.valueOf(areaBucket), areaBucket,
                deposit, termEndYm, estimated, renewalUsed);
    }

    private Map<String, Object> forecastOf(long complexId, int areaBucket, String ym) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM maturity_forecast WHERE complex_id = ? AND area_bucket = ?"
                        + " AND maturity_ym = ?", complexId, areaBucket, ym);
    }

    @Test
    void 만기월별로_건수와_당시_보증금_중위값을_집계한다() {
        currentStat(1, 85, 400_000_000L, "HIGH");
        lease(1, 85, 500_000_000L, "202612", false, false);
        lease(1, 85, 600_000_000L, "202612", false, false);
        lease(1, 85, 700_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(1, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(3);
        assertThat(((Number) f.get("median_deposit_then")).longValue()).isEqualTo(600_000_000L);
    }

    @Test
    void 역전세면_갭이_양수다() {
        currentStat(2, 85, 400_000_000L, "HIGH");
        lease(2, 85, 600_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(2, 85, "202612");
        // 당시 6억 - 현재 4억 = 2억
        assertThat(((Number) f.get("gap_amount")).longValue()).isEqualTo(200_000_000L);
        assertThat(((Number) f.get("gap_rate")).doubleValue()).isCloseTo(
                0.3333, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void 시세가_올랐으면_갭이_음수다() {
        currentStat(3, 85, 700_000_000L, "HIGH");
        lease(3, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        assertThat(((Number) forecastOf(3, 85, "202612").get("gap_amount")).longValue())
                .isEqualTo(-200_000_000L);
    }

    @Test
    void 추정으로_만기를_잡은_건수를_따로_센다() {
        currentStat(4, 85, 400_000_000L, "HIGH");
        lease(4, 85, 500_000_000L, "202612", true, false);
        lease(4, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(4, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(2);
        assertThat(((Number) f.get("estimated_count")).intValue()).isEqualTo(1);
    }

    @Test
    void 갱신권을_이미_쓴_건수를_따로_센다() {
        currentStat(5, 85, 400_000_000L, "HIGH");
        lease(5, 85, 500_000_000L, "202612", false, true);
        lease(5, 85, 500_000_000L, "202612", false, true);
        lease(5, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(5, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(3);
        assertThat(((Number) f.get("renewal_exhausted_count")).intValue()).isEqualTo(2);
    }

    @Test
    void 이미_지난_만기는_예측_대상이_아니다() {
        currentStat(6, 85, 400_000_000L, "HIGH");
        lease(6, 85, 500_000_000L, "202601", false, false);   // fromYm 이전
        lease(6, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT maturity_ym FROM maturity_forecast WHERE complex_id = 6");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("maturity_ym")).isEqualTo("202612");
    }

    @Test
    void confidence는_현재_시세_쪽에서_가져온다() {
        currentStat(7, 85, 400_000_000L, "LOW");
        lease(7, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(7, 85, "202612").get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 현재_시세가_없으면_행을_만들지_않는다() {
        // Task 2가 어떤 이유로든 시세를 못 낸 (단지,평형)은 갭을 계산할 수 없다
        lease(8, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maturity_forecast WHERE complex_id = 8", Integer.class);
        assertThat(n).isZero();
    }

    @Test
    void 월세가_있는_계약은_만기_집계에서_제외된다() {
        currentStat(9, 85, 400_000_000L, "HIGH");
        lease(9, 85, 500_000_000L, "202612", false, false);
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES ('banj-9',9,'11680-9','11680','대치동','1','단지',85,85,3,"
                        + " '2024-12-01',100000000,800000,'202612',FALSE,FALSE,NOW(),NOW())");

        builder.build(SNAPSHOT, "202610");

        assertThat(((Number) forecastOf(9, 85, "202612").get("contract_count")).intValue())
                .isEqualTo(1);
    }
}
