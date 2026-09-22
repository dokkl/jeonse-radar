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
