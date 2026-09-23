package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MaturityForecastBuilderTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    MaturityForecastBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

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
    void 짝수_건수_서로_다른_보증금이면_중위값은_가운데_두_값의_평균이다() {
        currentStat(10, 85, 400_000_000L, "HIGH");
        lease(10, 85, 400_000_000L, "202612", false, false);
        lease(10, 85, 600_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(10, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(2);
        assertThat(((Number) f.get("median_deposit_then")).longValue()).isEqualTo(500_000_000L);
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

    /** 만기가 termEndYm인 순수 전세를 count건 심는다. 보증금은 서로 다르게 흩뿌린다. */
    private void leases(long complexId, int areaBucket, String termEndYm, int count) {
        for (int i = 0; i < count; i++) {
            lease(complexId, areaBucket, 500_000_000L + i * 1_000_000L, termEndYm, false, false);
        }
    }

    // ledger F-41: confidence는 현재 시세 쪽 신뢰도만 보고 있었다. then 쪽 표본이 1건이면
    // 중위값이 아니라 그 한 건의 가격일 뿐인데도 현재 쪽이 HIGH면 그대로 HIGH가 찍혔다.
    // 마곡엠밸리2단지 115㎡, 만기 202810, contract_count=1, gap 6.78억, confidence=HIGH가
    // 실측 데이터에서 나온 구체 사례다.

    @Test
    void 현재_시세가_HIGH여도_만기_계약이_1건이면_confidence는_LOW다() {
        currentStat(100, 115, 400_000_000L, "HIGH");
        lease(100, 115, 467_800_000L, "202810", false, false);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(100, 115, "202810").get("confidence"))
                .isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 만기_계약_건수가_표본_경계값을_지날_때마다_confidence_등급이_바뀐다() {
        currentStat(101, 85, 400_000_000L, "HIGH");
        leases(101, 85, "202612", 2);
        currentStat(102, 85, 400_000_000L, "HIGH");
        leases(102, 85, "202612", 3);
        currentStat(103, 85, 400_000_000L, "HIGH");
        leases(103, 85, "202612", 9);
        currentStat(104, 85, 400_000_000L, "HIGH");
        leases(104, 85, "202612", 10);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(101, 85, "202612").get("confidence")).isEqualTo(Confidence.LOW.name());
        assertThat(forecastOf(102, 85, "202612").get("confidence")).isEqualTo(Confidence.MEDIUM.name());
        assertThat(forecastOf(103, 85, "202612").get("confidence")).isEqualTo(Confidence.MEDIUM.name());
        assertThat(forecastOf(104, 85, "202612").get("confidence")).isEqualTo(Confidence.HIGH.name());
    }

    @Test
    void 현재_시세가_LOW나_MEDIUM이면_만기_표본이_많아도_그보다_승격되지_않는다() {
        currentStat(105, 85, 400_000_000L, "LOW");
        leases(105, 85, "202612", 50);
        currentStat(106, 85, 400_000_000L, "MEDIUM");
        leases(106, 85, "202612", 50);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(105, 85, "202612").get("confidence")).isEqualTo(Confidence.LOW.name());
        assertThat(forecastOf(106, 85, "202612").get("confidence")).isEqualTo(Confidence.MEDIUM.name());
    }

    /**
     * 만기가 termEndYm인 순수 전세를 count건, 전부 같은 보증금으로 심는다.
     *
     * <p>{@link #leases(long, int, String, int)}가 일부러 피하는 패턴이다 — 대형 임대사업자의
     * 일괄 행정 갱신은 한 보증금 값이 그룹을 통째로 지배한다. 이 메서드는 그 패턴을 재현한다.
     */
    private void bulkRenewal(long complexId, int areaBucket, String termEndYm,
                             int count, long deposit) {
        for (int i = 0; i < count; i++) {
            lease(complexId, areaBucket, deposit, termEndYm, false, false);
        }
    }

    // ---- F-31c: then 쪽 표본 등급이 "몇 건이냐"만 보고 있었다. 대형 임대사업자가 같은
    //      보증금으로 17건을 한꺼번에 갱신하면 contract_count는 17이라 HIGH가 찍히지만,
    //      그 17건은 독립된 관측 17개가 아니라 임대 의사결정 하나가 복제된 것이다.
    //      RentStatBuilder의 CLEAN_DEALS_CTE는 현재 시세 쪽에서 같은 패턴을 이미 배제하고
    //      있었는데(F-31), 예측 쪽에는 그 규칙이 아예 넘어오지 않아 갭이 "행정가 − 시장가"가
    //      됐다. 계약은 실재하므로 지우지 않는다 — 등급만 증거에 맞춘다.
    //
    //      판정식은 CLEAN_DEALS_CTE와 글자 그대로 같다: 그룹 10건 이상 + 한 보증금 값이 90%
    //      이상(dep_cnt * 10 >= grp_cnt * 9). 아래 9/10건, 85%/90% 경계 테스트는
    //      RentStatBuilderTest의 F-31 경계 테스트와 짝을 이룬다. ----

    @Test
    void 만기_그룹이_전부_같은_보증금이면_현재_시세가_HIGH여도_confidence는_LOW다() {
        // 구파발9단지래미안 85㎡ 만기 202810 재현: 17건이 전부 정확히 4.5006억(100% 동일).
        // 현재 시세는 정제된 표본 137건짜리 L1 HIGH인데, then 쪽은 원본을 그대로 읽어
        // contract_count=17 → HIGH가 찍히고 갭 +2.33억(51.7%)이 HIGH로 노출됐다.
        currentStat(200, 85, 217_000_000L, "HIGH");
        bulkRenewal(200, 85, "202810", 17, 450_060_000L);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(200, 85, "202810");
        assertThat(f.get("confidence")).isEqualTo(Confidence.LOW.name());
        // 계약은 실재한다 — 건수와 당시 보증금은 그대로 둔다. 강등되는 것은 등급뿐이다.
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(17);
        assertThat(((Number) f.get("median_deposit_then")).longValue()).isEqualTo(450_060_000L);
        assertThat(((Number) f.get("gap_amount")).longValue()).isEqualTo(233_060_000L);
    }

    @Test
    void 만기_그룹이_9건이면_보증금이_전부_같아도_강등되지_않는다() {
        // 집중도는 100%지만 10건 미만이라 규칙이 켜지지 않는다. 표본 9건의 원래 등급
        // (10건 미만 → MEDIUM)이 그대로여야 한다 — LOW로 더 내려가면 안 된다.
        currentStat(201, 85, 217_000_000L, "HIGH");
        bulkRenewal(201, 85, "202810", 9, 450_060_000L);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(201, 85, "202810").get("confidence"))
                .isEqualTo(Confidence.MEDIUM.name());
    }

    @Test
    void 만기_그룹이_10건이면_보증금이_전부_같을_때_강등된다() {
        // 같은 집중 패턴에 1건만 더해 10건을 만들면 규칙이 켜진다. 규칙이 없다면
        // contract_count=10 + 현재 HIGH라 HIGH가 찍힐 자리다.
        currentStat(202, 85, 217_000_000L, "HIGH");
        bulkRenewal(202, 85, "202810", 10, 450_060_000L);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(202, 85, "202810").get("confidence"))
                .isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 만기_그룹의_집중도가_90퍼센트_미만이면_강등되지_않는다() {
        // 17/20 = 85% — 10건 이상이지만 90%에 못 미쳐 규칙이 켜지지 않는다.
        currentStat(203, 85, 217_000_000L, "HIGH");
        bulkRenewal(203, 85, "202810", 17, 450_060_000L);
        lease(203, 85, 520_000_000L, "202810", false, false);
        lease(203, 85, 540_000_000L, "202810", false, false);
        lease(203, 85, 560_000_000L, "202810", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(203, 85, "202810");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(20);
        assertThat(f.get("confidence")).isEqualTo(Confidence.HIGH.name());
    }

    @Test
    void 만기_그룹의_집중도가_정확히_90퍼센트면_강등된다() {
        // 18/20 = 90% 정확히 — 경계값이므로 켜져야 한다. 정수 비교(dep_cnt * 10 >= grp_cnt * 9)
        // 라서 부동소수점 반올림으로 경계가 흔들리지 않는다.
        currentStat(204, 85, 217_000_000L, "HIGH");
        bulkRenewal(204, 85, "202810", 18, 450_060_000L);
        lease(204, 85, 520_000_000L, "202810", false, false);
        lease(204, 85, 540_000_000L, "202810", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(204, 85, "202810");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(20);
        assertThat(f.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 만기_그룹의_보증금이_제각각이면_10건_이상에서_여전히_HIGH다() {
        // "항상 강등"이 되어서는 안 된다. 12건이 전부 다른 보증금이면 어느 값도 그룹을
        // 지배하지 않으므로 규칙과 무관하고, 원래대로 HIGH여야 한다.
        currentStat(205, 85, 400_000_000L, "HIGH");
        leases(205, 85, "202612", 12);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(205, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(12);
        assertThat(f.get("confidence")).isEqualTo(Confidence.HIGH.name());
    }
}
