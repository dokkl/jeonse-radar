package com.jeonseradar.web;

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
 * 보일러플레이트는 {@code ComplexPageControllerTest}(3단계 Task 6)를 그대로 따른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SitemapControllerTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    /**
     * {@code complexWith}/{@code jdbc.update(...)}이 심는 slug("c{id}-11710-1")로부터 실제로
     * 나오는 퍼센트 인코딩된 전체 경로. 조각(fragment)이나 {@code UriUtils.encodePath} 재호출이
     * 아니라 리터럴이다 — id 1의 값은 실제 실패한 테스트 실행 출력
     * ({@code <loc>http://localhost:8080/%EB%8B%A8%EC%A7%80/1-c1-11710-1</loc>})에서 그대로
     * 가져왔고, 나머지는 "단지"만 한글이고 그 뒤는 전부 ASCII(id·하이픈·slug)인 같은 모양이라
     * 프리픽스 {@code /%EB%8B%A8%EC%A7%80/}만 고정하면 안전하다 (원장 P3-7: 예전에는 이 자리에
     * "/c{id}-11710-1"처럼 id 접두어가 빠진 조각을 썼다가 실제 출력과 어긋났다).
     */
    private static final java.util.Map<Long, String> COMPLEX_PATH = java.util.Map.of(
            1L, "/%EB%8B%A8%EC%A7%80/1-c1-11710-1",
            2L, "/%EB%8B%A8%EC%A7%80/2-c2-11710-1",
            3L, "/%EB%8B%A8%EC%A7%80/3-c3-11710-1",
            4L, "/%EB%8B%A8%EC%A7%80/4-c4-11710-1",
            5L, "/%EB%8B%A8%EC%A7%80/5-c5-11710-1");

    /**
     * {@code complexInRegion}/{@code complexWith}이 쓰는 지역코드들의 실제 전체 경로 리터럴
     * (원장 P3-7). "역전세" 접두어만 확인하는 조각 검사는 —id 접두어가 없는 지역 경로라
     * 복합_단지처럼 "빠진 접두어" 결함은 없지만— 어느 지역인지는 구분하지 못하므로, 같은
     * 원칙(리터럴을 통째로 고정한다)을 지역에도 적용해 시도·시군구까지 통째로 고정한다.
     */
    private static final java.util.Map<String, String> REGION_PATH = java.util.Map.of(
            "11710", "/%EC%97%AD%EC%A0%84%EC%84%B8/%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C/%EC%86%A1%ED%8C%8C%EA%B5%AC",
            "11680", "/%EC%97%AD%EC%A0%84%EC%84%B8/%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C/%EA%B0%95%EB%82%A8%EA%B5%AC",
            "11650", "/%EC%97%AD%EC%A0%84%EC%84%B8/%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C/%EC%84%9C%EC%B4%88%EA%B5%AC",
            "11620", "/%EC%97%AD%EC%A0%84%EC%84%B8/%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C/%EA%B4%80%EC%95%85%EA%B5%AC");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;
    @Autowired com.jeonseradar.web.complex.ComplexPageQuery complexPageQuery;
    @Autowired com.jeonseradar.web.region.RegionPageQuery regionPageQuery;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 0, 0);
        snapshots.save(s);
    }

    private void complexWith(long id, String name, String confidence) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, '11710', '잠실동', '40', ?, ?, 2018)
                """, id, "11710-" + id, name, "c" + id + "-11710-1");
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, ?, 84, 950000000, 30, 'L1', ?)
                """, SNAPSHOT, id, confidence);
    }

    /** {@code complexWith}과 달리 지역코드를 고를 수 있고 complex_rent_stat을 넣지 않는다. */
    private void complexInRegion(long id, String regionCode, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, ?, '잠실동', '40', ?, ?, 2018)
                """, id, regionCode + "-" + id, regionCode, name, id + "-단지-" + regionCode + "-1");
    }

    /** RegionPageQuery의 랭킹 문턱(원장 P3-6)을 넘나드는 maturity_forecast 한 행. */
    private void forecast(long complexId, long gapAmount, int contractCount, String confidence) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, ?, 84, '202612', ?, 0, 1000000000, ?, ?, 0.1, 1, ?)
                """, SNAPSHOT, complexId, contractCount, 1_000_000_000L - gapAmount, gapAmount, confidence);
    }

    @Test
    void robots는_sitemap을_가리킨다() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sitemap:")))
                .andExpect(content().string(containsString("/sitemap.xml")));
    }

    @Test
    void 색인_가능한_단지만_sitemap에_넣는다() throws Exception {
        complexWith(1, "헬리오시티", "HIGH");
        complexWith(2, "리센츠", "LOW");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(COMPLEX_PATH.get(1L))))
                .andExpect(content().string(not(containsString(COMPLEX_PATH.get(2L)))));
    }

    @Test
    void 지번뿐인_이름은_sitemap에_넣지_않는다() throws Exception {
        complexWith(1, "(828-76)", "HIGH");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(not(containsString(COMPLEX_PATH.get(1L)))));
    }

    @Test
    void 단지가_있는_지역은_sitemap에_넣는다() throws Exception {
        complexWith(1, "헬리오시티", "HIGH");
        // 지역도 자기 자신의 색인 문턱(RegionPage.indexable, 원장 P3-6)을 넘어야 sitemap에
        // 든다 — 랭킹에 오를 행이 하나도 없으면 이 단지만으로는 지역이 색인 대상이 아니다.
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(containsString(REGION_PATH.get("11710"))));
    }

    @Test
    void 랭킹에_오를_행이_없는_지역은_sitemap에_넣지_않는다() throws Exception {
        // 3단지가 있어도 maturity_forecast가 하나도 없으면 RegionPage.indexable()은 false다
        // (원장 P3-6) — "단지가 있다"만으로 지역을 sitemap에 올리면 그 지역 페이지 자신의
        // noindex, follow와 모순된다.
        complexWith(1, "헬리오시티", "HIGH");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(not(containsString(REGION_PATH.get("11710")))));
    }

    @Test
    void sitemap_수록_여부가_지역_페이지의_색인_판정과_일치한다() throws Exception {
        // 색인 규칙이 Java(RegionPage.indexable)와 SQL(여기) 두 곳에 있다 (원장 P3-6).
        // 집합 질의가 필요해 SQL을 없앨 수는 없으므로, 둘이 갈라지지 않는지를 대조로 고정한다.
        // 갈라지면 noindex인 지역 페이지를 sitemap이 제출하는 모순이 생긴다.
        complexInRegion(1, "11710", "가단지");
        forecast(1, 100_000_000L, 5, "MEDIUM");   // 문턱을 만족 → 색인 대상

        complexInRegion(2, "11680", "나단지");
        forecast(2, 900_000_000L, 1, "MEDIUM");   // 계약 건수 미달 → 랭킹에서 빠짐

        complexInRegion(3, "11650", "다단지");
        forecast(3, 900_000_000L, 5, "LOW");      // 신뢰도 미달 → 랭킹에서 빠짐

        complexInRegion(4, "11620", "라단지");    // maturity_forecast 행 자체가 없음

        String xml = mvc.perform(get("/sitemap.xml")).andReturn()
                .getResponse().getContentAsString();

        for (String code : java.util.List.of("11710", "11680", "11650", "11620")) {
            boolean inSitemap = xml.contains(REGION_PATH.get(code));
            boolean indexable = regionPageQuery.find(code).orElseThrow().indexable();
            org.assertj.core.api.Assertions.assertThat(inSitemap)
                    .as("지역 %s — sitemap 수록과 indexable()이 달라졌다", code)
                    .isEqualTo(indexable);
        }
    }

    @Test
    void sitemap_수록_여부가_단지_페이지의_색인_판정과_일치한다() throws Exception {
        // 색인 규칙이 Java(ComplexPageQuery.indexable)와 SQL(여기) 두 곳에 있다 (원장 C-1).
        // 집합 질의가 필요해 SQL을 없앨 수는 없으므로, 둘이 갈라지지 않는지를 대조로 고정한다.
        // 갈라지면 noindex인 페이지를 sitemap이 제출하는 모순이 생긴다.
        complexWith(1, "헬리오시티", "HIGH");     // 색인 대상
        complexWith(2, "리센츠", "MEDIUM");       // 색인 대상
        complexWith(3, "파크리오", "LOW");        // 신뢰도 미달
        complexWith(4, "(828-76)", "HIGH");       // 지번뿐인 이름
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (5, '11710-5', '11710', '잠실동', '40', '시세없음', 'c5-11710-1', 2018)
                """);                            // 시세 자체가 없음

        String xml = mvc.perform(get("/sitemap.xml")).andReturn()
                .getResponse().getContentAsString();

        for (long id = 1; id <= 5; id++) {
            boolean inSitemap = xml.contains(COMPLEX_PATH.get(id));
            boolean indexable = complexPageQuery.find(id).orElseThrow().indexable();
            org.assertj.core.api.Assertions.assertThat(inSitemap)
                    .as("단지 %d — sitemap 수록과 indexable()이 달라졌다", id)
                    .isEqualTo(indexable);
        }
    }

    @Test
    void 스냅샷이_없으면_503이다() throws Exception {
        snapshots.deleteAll();
        mvc.perform(get("/sitemap.xml")).andExpect(status().isServiceUnavailable());
    }
}
