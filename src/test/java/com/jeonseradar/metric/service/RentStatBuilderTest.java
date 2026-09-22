package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
import com.jeonseradar.metric.entity.SourceLevel;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RentStatBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired
    RentStatBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM rent_deal");
        jdbcTemplate.update("DELETE FROM complex");
    }

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
        for (int i = 0; i < 10; i++) {
            rent(22, "11680", "반포동", 60, 360_000_000L, LocalDate.of(2026, 8, 1));
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
        for (int i = 0; i < 20; i++) {
            rent(8, "11680", "청담동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(7, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(stat.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 표본_10건_이상의_L1은_HIGH다() {
        complex(9, "11680", "역삼동");
        for (int i = 0; i < 12; i++) {
            rent(9, "11680", "역삼동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
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
}
