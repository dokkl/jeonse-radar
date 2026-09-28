package com.jeonseradar.web.root;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 루트 페이지는 링크 그래프의 뿌리다. 이 서비스는 SEO 유입만으로 배포되므로
 * 크롤러가 루트에서 시도 → 시군구 → 단지로 내려갈 수 있어야 한다.
 *
 * 보일러플레이트는 {@code RegionPageControllerTest}를 따른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RootPageControllerTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void 발행된_스냅샷을_심는다() {
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

    @Test
    void 데이터가_있는_시도를_전부_링크한다() throws Exception {
        complexIn("11680", 1, "가단지");   // 서울특별시 강남구
        complexIn("26110", 2, "나단지");   // 부산광역시 중구
        complexIn("41111", 3, "다단지");   // 경기도 수원시 장안구

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("서울특별시")))
                .andExpect(content().string(containsString("부산광역시")))
                .andExpect(content().string(containsString("경기도")));
    }

    /**
     * 같은 규칙이 두 곳에 산다 — 루트가 만드는 주소와 시도 인덱스가 받는 주소.
     * 이 프로젝트에서 그 패턴은 이미 여섯 번 사고를 냈으므로 실제로 따라가 본다.
     *
     * <p>주소를 문자열로 기대하지 않고 렌더링된 href를 그대로 꺼내 GET한다.
     * 한글 경로의 인코딩 방식을 테스트가 다시 구현하면, 그 구현이 어긋나는 날
     * 테스트는 통과하면서 링크만 깨진다.
     */
    @Test
    void 루트가_건_시도_링크를_따라가면_실제로_열린다() throws Exception {
        complexIn("11680", 1, "가단지");
        complexIn("26110", 2, "나단지");

        String html = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> hrefs = sidoLinks(html);
        org.assertj.core.api.Assertions.assertThat(hrefs)
                .as("서울·부산 두 시도를 링크해야 한다")
                .hasSize(2);

        for (String href : hrefs) {
            mvc.perform(get(URI.create(href)))
                    .andExpect(status().isOk());
        }
    }

    /** 루트의 시도 목록 안에 있는 href만 꺼낸다 (스타일시트·canonical 제외). */
    private static List<String> sidoLinks(String html) {
        Matcher block = Pattern.compile("<ul class=\"sido\">(.*?)</ul>", Pattern.DOTALL).matcher(html);
        if (!block.find()) {
            return List.of();
        }
        Matcher href = Pattern.compile("href=\"([^\"]+)\"").matcher(block.group(1));
        List<String> found = new ArrayList<>();
        while (href.find()) {
            found.add(href.group(1));
        }
        return found;
    }

    @Test
    void 데이터가_없는_시도는_링크하지_않는다() throws Exception {
        complexIn("11680", 1, "가단지");   // 서울만 심는다

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("서울특별시")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("제주특별자치도"))));
    }

    /**
     * 발행 관문을 안 거치면 COMPLETE 스냅샷이 없는 상태에서도 루트가 200으로
     * 시도들을 링크하고, 그 자식들은 전부 503이라 크롤러가 빈 사이트를 색인한다
     * (원장 R-6과 같은 이유).
     */
    @Test
    void 발행된_스냅샷이_없으면_503이다() throws Exception {
        complexIn("11680", 1, "가단지");
        snapshots.deleteAll();

        mvc.perform(get("/"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 수집_기준_수치를_보여준다() throws Exception {
        complexIn("11680", 1, "가단지");
        complexIn("11680", 2, "나단지");
        complexIn("26110", 3, "다단지");

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2개 시군구")))
                .andExpect(content().string(containsString("3개 단지")))
                .andExpect(content().string(containsString("2026-09-22")));
    }

    @Test
    void 루트를_정규_주소로_선언한다() throws Exception {
        complexIn("11680", 1, "가단지");

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rel=\"canonical\"")));
    }
}
