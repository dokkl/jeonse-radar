package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.FlagType;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DealFlagBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 22);

    @Autowired
    DealFlagBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM deal_flag");
        jdbcTemplate.update("DELETE FROM trade_deal");
    }

    private void trade(String hash, LocalDate dealDate, LocalDate registeredDate,
                       String cancelYn, String dealType) {
        jdbcTemplate.update(
                "INSERT INTO trade_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deal_amount,"
                        + " deal_type, registered_date, cancel_yn, created_at, updated_at)"
                        + " VALUES (?,1,'11680-1','11680','대치동','1','단지',85,85,3,?,1000000000,"
                        + " ?,?,?,NOW(),NOW())",
                hash, dealDate, dealType, registeredDate, cancelYn);
    }

    private List<String> flagsOf(String hash) {
        return jdbcTemplate.queryForList(
                "SELECT flag_type FROM deal_flag WHERE deal_hash = ? ORDER BY flag_type",
                String.class, hash);
    }

    @Test
    void 해제된_거래는_CANCELED다() {
        trade("h1", LocalDate.of(2026, 3, 1), null, "O", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h1")).contains(FlagType.CANCELED.name());
    }

    @Test
    void 계약_후_60일이_지나고_등기가_없으면_UNREGISTERED다() {
        trade("h2", LocalDate.of(2026, 3, 1), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h2")).contains(FlagType.UNREGISTERED.name());
    }

    @Test
    void 계약_후_60일이_안_지났으면_등기가_없어도_플래그하지_않는다() {
        // 잔금과 등기에 시간이 걸리므로 최근 거래에 등기가 없는 것은 정상이다
        trade("h3", LocalDate.of(2026, 9, 1), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h3")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 정확히_60일째는_아직_플래그하지_않는다() {
        trade("h4", AS_OF.minusDays(60), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h4")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 계약_후_61일째부터_플래그한다() {
        trade("h5", AS_OF.minusDays(61), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h5")).contains(FlagType.UNREGISTERED.name());
    }

    @Test
    void 등기가_있으면_UNREGISTERED가_아니다() {
        trade("h6", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h6")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 해제된_거래는_등기가_없어도_UNREGISTERED가_아니다() {
        // 해제됐으면 등기가 없는 것이 당연하다. 두 번 낙인찍지 않는다.
        trade("h7", LocalDate.of(2026, 3, 1), null, "O", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h7")).containsExactly(FlagType.CANCELED.name());
    }

    @Test
    void 직거래는_DIRECT다() {
        trade("h8", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "직거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h8")).containsExactly(FlagType.DIRECT.name());
    }

    @Test
    void 중개거래는_DIRECT가_아니다() {
        trade("h9", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h9")).isEmpty();
    }

    @Test
    void 한_거래에_여러_플래그가_붙을_수_있다() {
        trade("h10", LocalDate.of(2026, 3, 1), null, "N", "직거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h10"))
                .containsExactly(FlagType.DIRECT.name(), FlagType.UNREGISTERED.name());
    }
}
