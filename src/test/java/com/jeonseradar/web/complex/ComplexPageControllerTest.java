package com.jeonseradar.web.complex;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 네트워크를 타는 테스트를 만들지 않는다 — Testcontainers로 로컬 MySQL을 띄워 붙인다.
 * 보일러플레이트는 {@code ComplexPageQueryTest}(Task 3)를 그대로 따른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplexPageControllerTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    private void publishSnapshot() {
        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 0, 0);
        snapshots.save(s);
    }

    private void complexWith(String name, String confidence) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (1, '11710-1', '11710', '잠실동', '40', ?, 'helio-11710-1', 2018)
                """, name);
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, 1, 84, 950000000, 30, 'L1', ?)
                """, SNAPSHOT, confidence);
    }

    @Test
    void 발행된_스냅샷이_없으면_503을_준다() throws Exception {
        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 없는_단지는_404를_준다() throws Exception {
        publishSnapshot();
        mvc.perform(get("/단지/999-없음")).andExpect(status().isNotFound());
    }

    @Test
    void 단지_페이지가_렌더링된다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("complex"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("헬리오시티")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("전세 만기·역전세 현황")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<link rel=\"canonical\" href=\"http://localhost:8080/%EB%8B%A8%EC%A7%80/1-helio-11710-1\">")));
    }

    @Test
    void slug가_다르면_정규_주소로_301한다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        // 퍼센트 인코딩된 경로(레저 P3-3)가 정답이다 — "/단지/"는 항상 한글이라
        // 실제 서버가 보낼 수 있는 Location 값은 이 형태뿐이다. MockMvc는
        // Tomcat의 ISO-8859-1 헤더 인코딩을 거치지 않으므로 여기서는 raw 문자열도
        // 통과해 버려서 버그를 못 잡는다 — 그래서 실제 서버로 검증하는
        // ComplexPageRedirectEncodingTest가 따로 있다. 이 기대값을 보기 흉하다고
        // 원래의 raw 한글 문자열로 되돌리면 P3-3 버그가 그대로 재발한다.
        mvc.perform(get("/단지/1-옛날slug"))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/%EB%8B%A8%EC%A7%80/1-helio-11710-1"));
    }

    @Test
    void 신뢰도가_충분하면_noindex를_넣지_않는다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("noindex"))));
    }

    @Test
    void 신뢰도가_낮으면_noindex를_넣는다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "LOW");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("noindex")));
    }

    private void maturity(int contracts, int estimated, long depositThen, long currentDeposit, long gap) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, 1, 84, '202612', ?, ?, ?, ?, ?, 0.1, 2, 'HIGH')
                """, SNAPSHOT, contracts, estimated, depositThen, currentDeposit, gap);
    }

    @Test
    void 추정_만기가_섞인_계약_건수와_금액_단위가_표시된다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");
        maturity(12, 3, 1_000_000_000L, 900_000_000L, 100_000_000L);

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("12건 (추정 3건)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("100,000만원")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("90,000만원")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("+10,000만원")));
    }

    @Test
    void 추정_만기가_없으면_행에_추정_문구를_붙이지_않는다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");
        maturity(5, 0, 1_000_000_000L, 900_000_000L, 100_000_000L);

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("5건")))
                // 설명 문구는 표에 추정이 하나라도 있을 수 있다는 일반 안내라 항상 떠 있다 —
                // 여기서 검증하는 건 "5건" 뒤에 "(추정"이 붙지 않는다는 행 단위 표시다.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("5건 (추정"))));
    }

    @Test
    void 계약_건수가_하한값임을_표기한다() throws Exception {
        // 스펙 §1.3 개정(2026-09-23): deal_hash가 동/호 없이 자연키로 거래를 합치므로
        // contractCount·renewalExhaustedCount는 전국 기준 약 8.60%가 낮게 잡히는
        // 최소값이다. 이 사실을 열 헤더의 "*"와 설명 문단이 실제로 렌더링하는지 고정한다.
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");
        maturity(12, 3, 1_000_000_000L, 900_000_000L, 100_000_000L);

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("계약*")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("갱신권 소진*")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("최소값")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("동·호수")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("8.60%")));
    }
}
