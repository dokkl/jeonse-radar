package com.jeonseradar.web.region;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 네트워크를 타는 테스트를 만들지 않는다 — Testcontainers로 로컬 MySQL을 띄워 붙인다.
 * 보일러플레이트는 {@code ComplexPageControllerTest}(Task 4)를 그대로 따른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RegionPageControllerTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 1, 0);
        snapshots.save(s);
    }

    private void complexIn(String code, long id, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, ?, '잠실동', '40', ?, ?, 2018)
                """, id, code + "-" + id, code, name, id + "-단지-" + code + "-1");
    }

    private void forecast(long complexId, long gap, int contracts, String conf) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, ?, 84, '202612', ?, 0, 1000000000, ?, ?, 0.1, 1, ?)
                """, SNAPSHOT, complexId, contracts, 1_000_000_000L - gap, gap, conf);
    }

    @Test
    void 시군구_페이지가_렌더링된다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(status().isOk())
                .andExpect(view().name("region"))
                .andExpect(content().string(containsString("송파구")))
                .andExpect(content().string(containsString("헬리오시티")));
    }

    @Test
    void 세_토큰_시군구는_하이픈_주소로_열린다() throws Exception {
        complexIn("41111", 1, "장안단지");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/경기도/수원시-장안구"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("수원시 장안구")));
    }

    @Test
    void 없는_지역은_404를_준다() throws Exception {
        mvc.perform(get("/역전세/서울특별시/없는구")).andExpect(status().isNotFound());
    }

    @Test
    void 랭킹이_비면_noindex를_넣는다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 1, "LOW"); // 문턱 미달

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("noindex")));
    }

    @Test
    void 랭킹이_있으면_noindex를_넣지_않는다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(content().string(not(containsString("noindex"))));
    }

    @Test
    void 시도_인덱스가_그_시도의_시군구를_나열한다() throws Exception {
        complexIn("11710", 1, "가단지");
        complexIn("11680", 2, "나단지");
        complexIn("41111", 3, "다단지");

        mvc.perform(get("/역전세/서울특별시"))
                .andExpect(status().isOk())
                .andExpect(view().name("sido"))
                .andExpect(content().string(containsString("송파구")))
                .andExpect(content().string(containsString("강남구")))
                .andExpect(content().string(not(containsString("장안구"))));
    }

    @Test
    void 시군구가_없는_시도는_인덱스가_곧_지역_페이지다() throws Exception {
        complexIn("36110", 1, "세종단지");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/세종특별자치시"))
                .andExpect(status().isOk())
                .andExpect(view().name("region"))
                .andExpect(content().string(containsString("세종단지")));
    }
}
