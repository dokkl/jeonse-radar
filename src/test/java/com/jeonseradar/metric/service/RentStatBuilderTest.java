package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
import com.jeonseradar.metric.entity.SourceLevel;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RentStatBuilderTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired
    RentStatBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** complex 한 건을 만들고 id를 돌려준다. */
    private long complex(long id, String regionCode, String legalDong) {
        jdbcTemplate.update(
                "INSERT INTO complex (id, region_code, apt_seq, legal_dong, jibun, display_name, slug) "
                        + "VALUES (?,?,?,?,'1','단지',?)",
                id, regionCode, regionCode + "-" + id, legalDong, "slug-" + id);
        return id;
    }

    /** 순수 전세 한 건. deposit은 원 단위. */
    private void rent(long complexId, String regionCode, String legalDong,
                      int areaBucket, long deposit, LocalDate dealDate) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?, '1','단지',?,?,3,?,?,0,FALSE,FALSE,NOW(),NOW())",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                complexId, regionCode + "-" + complexId, regionCode, legalDong,
                java.math.BigDecimal.valueOf(areaBucket), areaBucket, dealDate, deposit);
    }

    private Map<String, Object> statOf(long complexId, int areaBucket) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM complex_rent_stat WHERE complex_id = ? AND area_bucket = ?",
                complexId, areaBucket);
    }

    /** 월세가 있는 계약(반전세) 한 건. 순수 전세 필터 검증용. */
    private void mixedRent(String dealHash, long complexId, String regionCode, String legalDong,
                           int areaBucket, long deposit, long monthlyRent, LocalDate dealDate) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?, '1','단지',?,?,3,?,?,?,FALSE,FALSE,NOW(),NOW())",
                dealHash, complexId, regionCode + "-" + complexId, regionCode, legalDong,
                java.math.BigDecimal.valueOf(areaBucket), areaBucket, dealDate, deposit, monthlyRent);
    }

    @Test
    void L1은_같은_단지_평형의_최근_6개월_중위값을_쓴다() {
        complex(1, "11680", "대치동");
        rent(1, "11680", "대치동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(1, "11680", "대치동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));
        rent(1, "11680", "대치동", 85, 700_000_000L, LocalDate.of(2026, 6, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(1, 85);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(600_000_000L);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(3);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L1.name());
    }

    @Test
    void 표본이_짝수면_가운데_두_값의_평균이다() {
        complex(2, "11680", "대치동");
        rent(2, "11680", "대치동", 85, 400_000_000L, LocalDate.of(2026, 8, 1));
        rent(2, "11680", "대치동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));

        builder.build(SNAPSHOT, AS_OF);

        assertThat(((Number) statOf(2, 85).get("median_deposit")).longValue())
                .isEqualTo(500_000_000L);
    }

    @Test
    void 거래일이_6개월보다_오래되면_L1_표본에서_빠진다() {
        complex(3, "11680", "대치동");
        rent(3, "11680", "대치동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));  // 6개월 밖
        rent(3, "11680", "대치동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(3, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void L1_표본이_없으면_같은_단지의_제곱미터당_단가로_환산한다() {
        complex(4, "11680", "대치동");
        // 85㎡는 오래된 거래만 있어 L1 불가
        rent(4, "11680", "대치동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 같은 단지 60㎡에 최근 거래 → ㎡당 600만원
        rent(4, "11680", "대치동", 60, 360_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(4, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L2.name());
        // ㎡당 600만원 x 85㎡ = 5.1억
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(510_000_000L);
        // 단가 표본이 1건뿐이므로 Confidence.of(1, L2) = LOW
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(stat.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void L2의_confidence와_sample_count는_Confidence_of와_일치한다() {
        // 단지별로 최근 순수 전세 표본 수를 1/3/10건으로 다르게 만들어, SQL의 CASE가
        // Confidence.of(n, L2)와 모든 대표 표본 수에서 실제로 일치하는지 확인한다.
        // (n=10에서는 L2 상한이 MEDIUM이므로 HIGH가 아니라 MEDIUM이어야 한다 — 상한 검증)
        complex(20, "11680", "반포동");
        rent(20, "11680", "반포동", 85, 900_000_000L, LocalDate.of(2025, 1, 1)); // L1 불가
        rent(20, "11680", "반포동", 60, 360_000_000L, LocalDate.of(2026, 8, 1)); // 표본 1건

        complex(21, "11680", "반포동");
        rent(21, "11680", "반포동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        for (int i = 0; i < 3; i++) {
            rent(21, "11680", "반포동", 60, 360_000_000L, LocalDate.of(2026, 8, 1));
        }

        complex(22, "11680", "반포동");
        rent(22, "11680", "반포동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // F-31과 우연히 겹치지 않도록 10건을 서로 다른 보증금으로 흩어 둔다(전부
        // 동일하면 그 자체가 10건+90% 오염 패턴이 되어, 이 단지의 유일한 window-내
        // 데이터가 통째로 제외되고 L2가 아예 못 채우게 된다 — 이 테스트의 의도인
        // "표본 10건일 때의 confidence/sample_count"와 무관한 이유로 실패한다).
        for (int i = 0; i < 10; i++) {
            rent(22, "11680", "반포동", 60, 360_000_000L + i * 1_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        assertL2ConfidencePinnedToSampleCount(20, 1);
        assertL2ConfidencePinnedToSampleCount(21, 3);
        assertL2ConfidencePinnedToSampleCount(22, 10);
    }

    private void assertL2ConfidencePinnedToSampleCount(long complexId, int expectedSampleCount) {
        Map<String, Object> stat = statOf(complexId, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L2.name());
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(expectedSampleCount);
        // SQL의 CASE 식과 Confidence.of(n, L2)가 같은 값을 내는지, 하드코딩된 리터럴이 아니라
        // 자바 쪽 규칙 자체에 견주어 확인한다.
        assertThat(stat.get("confidence"))
                .isEqualTo(Confidence.of(expectedSampleCount, SourceLevel.L2).name());
    }

    @Test
    void L2에서도_월세가_있는_계약은_제외된다() {
        complex(23, "11680", "논현동");
        rent(23, "11680", "논현동", 85, 900_000_000L, LocalDate.of(2025, 1, 1)); // L1 불가
        // 순수 전세: ㎡당 600만원
        rent(23, "11680", "논현동", 60, 360_000_000L, LocalDate.of(2026, 8, 1));
        // 반전세: ㎡당 단가가 전혀 다르다. 섞이면 단가 중위값과 표본 수가 모두 달라진다.
        mixedRent("banjeonse-l2", 23, "11680", "논현동", 70, 70_000_000L, 500_000L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(23, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L2.name());
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        // ㎡당 600만원 x 85㎡ = 5.1억 (반전세가 섞였다면 이 값도, 표본 수도 달라졌을 것이다)
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(510_000_000L);
    }

    @Test
    void L3에서도_월세가_있는_계약은_제외된다() {
        complex(24, "11680", "행당동");
        complex(25, "11680", "행당동");
        // 단지24의 85㎡는 오래된 거래뿐이고 다른 평형도 없어 L1·L2 모두 불가
        rent(24, "11680", "행당동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 같은 법정동의 다른 단지 85㎡에 순수 전세 최근 거래
        rent(25, "11680", "행당동", 85, 550_000_000L, LocalDate.of(2026, 8, 1));
        // 반전세: 같은 법정동·평형에 섞여 있지만 제외되어야 한다
        mixedRent("banjeonse-l3", 25, "11680", "행당동", 85, 200_000_000L, 300_000L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(24, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(550_000_000L);
    }

    @Test
    void asOf보다_미래_거래는_시세_산출에서_제외된다() {
        complex(26, "11680", "상암동");
        // AS_OF(2026-09-01) 이전 — 포함되어야 한다
        rent(26, "11680", "상암동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        // AS_OF 이후 — 과거 시점 스냅샷을 재계산할 때 미래 데이터가 섞이면 안 된다
        rent(26, "11680", "상암동", 85, 900_000_000L, LocalDate.of(2026, 9, 10));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(26, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void L2까지_없으면_같은_법정동_같은_평형을_빌려_쓴다() {
        complex(5, "11680", "삼성동");
        complex(6, "11680", "삼성동");
        // 단지5의 85㎡는 오래된 거래뿐이고 다른 평형도 없다
        rent(5, "11680", "삼성동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 같은 법정동의 다른 단지 85㎡에 최근 거래
        rent(6, "11680", "삼성동", 85, 550_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(5, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(550_000_000L);
    }

    @Test
    void L3는_표본이_많아도_LOW를_넘지_못한다() {
        complex(7, "11680", "청담동");
        complex(8, "11680", "청담동");
        rent(7, "11680", "청담동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 20건을 두 값으로 절반씩 나눈다(50%/50% — F-31의 90% 문턱과 무관). 전부
        // 동일하면 그 자체가 10건+90% 오염 패턴이 되어 이 법정동·평형 풀 전체가
        // 제외되고, "표본이 많아도 L3는 LOW"라는 원래 의도와 무관하게 행 자체가
        // 안 생겨 실패한다.
        for (int i = 0; i < 10; i++) {
            rent(8, "11680", "청담동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        }
        for (int i = 0; i < 10; i++) {
            rent(8, "11680", "청담동", 85, 505_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(7, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(stat.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 표본_10건_이상의_L1은_HIGH다() {
        complex(9, "11680", "역삼동");
        // F-31 규칙과 무관함을 보장하려고 보증금을 절반씩 나눈다(50%/50% — 90% 문턱에
        // 한참 못 미친다). 전부 동일한 값이면 이 자체가 F-31이 잡아야 할 대량 갱신
        // 패턴이 되어버려, 원래 의도(표본 10건 이상이면 HIGH)를 검증할 수 없다.
        for (int i = 0; i < 6; i++) {
            rent(9, "11680", "역삼동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        }
        for (int i = 0; i < 6; i++) {
            rent(9, "11680", "역삼동", 85, 510_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        assertThat(statOf(9, 85).get("confidence")).isEqualTo(Confidence.HIGH.name());
    }

    @Test
    void 월세가_있는_계약은_시세_산출에서_제외된다() {
        complex(10, "11680", "논현동");
        rent(10, "11680", "논현동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        // 반전세 한 건을 직접 넣는다 (rent 헬퍼는 monthly_rent = 0 고정)
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES ('banjeonse-1',10,'11680-10','11680','논현동','1','단지',85,85,3,"
                        + " '2026-08-01', 100000000, 800000, FALSE, FALSE, NOW(), NOW())");

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(10, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void 한_단지가_서로_다른_법정동으로_흩어져_기록돼도_L3는_행이_하나만_생긴다() {
        // F-39 재현: MOLIT 원본은 법정동을 거래 단위로 기록하므로, 같은 단지·평형이라도
        // 거래마다 법정동이 다를 수 있다(강서구 마곡/방화 경계의 실제 사례).
        // L3의 target이 legal_dong까지 DISTINCT에 넣으면 같은 (complex_id, area_bucket)에
        // 행이 두 개 생겨 uk_rent_stat_key 위반으로 죽는다.
        complex(40, "11500", "마곡동"); // 대표(canonical) 법정동 = 마곡동
        // area_bucket 84는 두 단지 모두 window 밖(2025-01-01) 거래뿐이라 L1도 L2도 못 채운다.
        rent(40, "11500", "방화동", 84, 900_000_000L, LocalDate.of(2025, 1, 1));
        rent(40, "11500", "마곡동", 84, 900_000_000L, LocalDate.of(2025, 1, 1));

        // 이웃 단지: 방화동 쪽 dong 집계를 만든다 (window 안).
        complex(41, "11500", "방화동");
        rent(41, "11500", "방화동", 84, 700_000_000L, LocalDate.of(2026, 8, 1));

        // 이웃 단지: 마곡동 쪽 dong 집계를 만든다 (window 안).
        complex(42, "11500", "마곡동");
        rent(42, "11500", "마곡동", 84, 500_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE complex_id = 40 AND area_bucket = 84",
                Integer.class);
        assertThat(n).isEqualTo(1);

        Map<String, Object> stat = statOf(40, 84);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        // 단지의 대표 법정동(마곡동, 500_000_000)을 써야 한다. 다른 거래에 섞여 나온
        // 방화동 쪽(700_000_000)을 썼다면 이 값이 달랐을 것이다.
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void 한_단지_평형에_행이_하나만_생긴다() {
        complex(11, "11680", "도곡동");
        rent(11, "11680", "도곡동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(11, "11680", "도곡동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));

        builder.build(SNAPSHOT, AS_OF);

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE complex_id = 11 AND area_bucket = 85",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    // ---- F-31: 대형 임대사업자의 일괄 갱신(단일 보증금 값이 그룹을 지배)이 L1
    //      중위값을 왜곡하는 문제. 거래 10건 이상 + 한 보증금 값이 90% 이상이면
    //      그 값의 거래를 중위값 계산에서 제외한다. ----

    @Test
    void 오염된_그룹은_지배적_보증금을_제외한_나머지로_중위값을_계산한다() {
        complex(60, "11680", "신사동");
        // 대형 임대사업자의 일괄 갱신: 27건이 전부 같은 보증금(행정가) — 90% 정확히.
        for (int i = 0; i < 27; i++) {
            rent(60, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        // 진짜 시장 거래 3건
        rent(60, "11680", "신사동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(60, "11680", "신사동", 85, 520_000_000L, LocalDate.of(2026, 8, 1));
        rent(60, "11680", "신사동", 85, 540_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(60, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L1.name());
        // 지배적 보증금(9억)이 아니라 진짜 시장 거래 3건의 중위값(5.2억)이어야 한다.
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(520_000_000L);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(3);
        assertThat(stat.get("confidence")).isEqualTo(Confidence.MEDIUM.name());
    }

    @Test
    void 표본이_9건이면_지배적_보증금이_있어도_제외되지_않는다() {
        complex(61, "11680", "신사동");
        // 9건 전부 동일 — 집중도는 100%지만 표본이 10건 미만이라 규칙이 적용되지 않는다.
        for (int i = 0; i < 9; i++) {
            rent(61, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(61, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(9);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(900_000_000L);
    }

    @Test
    void 표본이_10건이면_지배적_보증금을_제외한다() {
        complex(62, "11680", "신사동");
        // 같은 집중 패턴(9건 동일)에 1건만 더해 표본을 10건으로 만들면 규칙이 켜져야 한다.
        for (int i = 0; i < 9; i++) {
            rent(62, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        rent(62, "11680", "신사동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(62, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void 집중도가_90퍼센트_미만이면_제외되지_않는다() {
        complex(63, "11680", "신사동");
        // 17/20 = 85% — 10건 이상이지만 90%에 못 미쳐 그대로 둔다.
        for (int i = 0; i < 17; i++) {
            rent(63, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        rent(63, "11680", "신사동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(63, "11680", "신사동", 85, 520_000_000L, LocalDate.of(2026, 8, 1));
        rent(63, "11680", "신사동", 85, 540_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(63, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(20);
        // 제외되지 않았으므로 20건 전체 기준 중위값(지배적 값인 9억)이 그대로 나온다.
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(900_000_000L);
    }

    @Test
    void 집중도가_정확히_90퍼센트면_제외된다() {
        complex(64, "11680", "신사동");
        // 18/20 = 90% 정확히 — 경계값이므로 제외되어야 한다.
        for (int i = 0; i < 18; i++) {
            rent(64, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        rent(64, "11680", "신사동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(64, "11680", "신사동", 85, 520_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(64, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(2);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(510_000_000L);
    }

    @Test
    void 제외_후_표본이_3건_미만이면_confidence가_LOW로_강등된다() {
        complex(65, "11680", "신사동");
        for (int i = 0; i < 9; i++) {
            rent(65, "11680", "신사동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        // 제외 후 남는 진짜 거래는 1건뿐 — 표본이 얇아졌으니 LOW로 강등돼야 한다.
        rent(65, "11680", "신사동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(65, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(stat.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 일반적인_그룹은_기존과_동일한_중위값을_유지한다() {
        complex(66, "11680", "신사동");
        // 12건, 전부 다른 보증금 — 어느 값도 그룹을 지배하지 않으므로 규칙과 무관하다.
        long[] deposits = {
                500_000_000L, 510_000_000L, 520_000_000L, 530_000_000L,
                540_000_000L, 550_000_000L, 560_000_000L, 570_000_000L,
                580_000_000L, 590_000_000L, 600_000_000L, 610_000_000L
        };
        for (long deposit : deposits) {
            rent(66, "11680", "신사동", 85, deposit, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(66, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(12);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(555_000_000L);
        assertThat(stat.get("confidence")).isEqualTo(Confidence.HIGH.name());
    }

    // ---- F-31b: 오염 제외는 거래 자체의 속성이지 레벨의 속성이 아니다. L1이 통째로
    //      비우고 넘어간 자리를 L2·L3가 원본(오염된) rent_deal로 다시 채우면 안 된다. ----

    @Test
    void 유일한_평형이_전부_오염된_단지는_L2로도_오염값을_내지_않는다() {
        // 오송역대광로제비앙그랜드센텀 재현: 유일한 평형(85)의 거래 전부가 동일 보증금.
        complex(80, "11680", "오송동");
        for (int i = 0; i < 15; i++) {
            rent(80, "11680", "오송동", 85, 255_000_000L, LocalDate.of(2026, 8, 1));
        }
        // 같은 법정동의 이웃 단지 — 진짜 시장 거래 3건. L3 폴백이 있어야 "행이 아예
        // 안 생김"과 "L3로 넘어가 진짜 값을 냄"을 구분해서 확인할 수 있다.
        complex(81, "11680", "오송동");
        rent(81, "11680", "오송동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(81, "11680", "오송동", 85, 520_000_000L, LocalDate.of(2026, 8, 1));
        rent(81, "11680", "오송동", 85, 540_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(80, 85);
        // L1도 L2도 못 채웠다 — 단지 80의 유일한 평형이 통째로 제외됐으니 L2가 쓸
        // ㎡당 단가 자체가 안 나온다. L3까지 내려가 이웃 단지의 진짜 값을 빌려 썼다.
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(3);
        // 이웃 단지 3건의 중위값(5.2억)이지, 오염값(2.55억)이 아니다.
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(520_000_000L);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isNotEqualTo(255_000_000L);
    }

    @Test
    void 법정동에_오염된_단지가_섞여있어도_다른_단지의_L3는_진짜_거래만_반영한다() {
        // 오염 유발 단지 — 이 단지 자신의 시세는 이 테스트의 관심사가 아니다.
        complex(82, "11680", "죽전동");
        for (int i = 0; i < 12; i++) {
            rent(82, "11680", "죽전동", 85, 900_000_000L, LocalDate.of(2026, 8, 1));
        }
        // 같은 법정동의 진짜 시세 기여 단지.
        complex(83, "11680", "죽전동");
        rent(83, "11680", "죽전동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(83, "11680", "죽전동", 85, 520_000_000L, LocalDate.of(2026, 8, 1));
        rent(83, "11680", "죽전동", 85, 540_000_000L, LocalDate.of(2026, 8, 1));
        // 대상 단지 — 자기 평형에 window 안 거래가 없어 L3로 내려가야 한다.
        complex(84, "11680", "죽전동");
        rent(84, "11680", "죽전동", 85, 999_000_000L, LocalDate.of(2025, 1, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(84, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        // 오염 단지의 12건이 풀에서 빠졌으므로 표본은 진짜 3건뿐이어야 한다
        // (안 빠졌다면 15건이 섞여 중위값이 9억 쪽으로 끌려갔을 것이다).
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(3);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(520_000_000L);
    }
}
