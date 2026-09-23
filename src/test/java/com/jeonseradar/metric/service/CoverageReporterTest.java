package com.jeonseradar.metric.service;

import com.jeonseradar.support.IntegrationTestSupport;
import com.jeonseradar.web.complex.ComplexPage;
import com.jeonseradar.web.complex.ComplexPageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CoverageReporterTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    CoverageReporter reporter;

    @Autowired
    ComplexPageQuery complexPageQuery;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCompleteSnapshot() {
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, completed_at)"
                        + " VALUES (?, 'COMPLETE', NOW(), NOW())", SNAPSHOT);
    }

    /**
     * 실제 데이터에서 complex_rent_stat 행은 항상 실존하는 complex를 가리키므로,
     * indexable 판정(원장 R-4, display_name 조건 포함)을 검증하려면 이름을 가진
     * complex 행이 있어야 한다. 여러 번 불려도 안전하도록 INSERT IGNORE를 쓴다.
     */
    private void complex(long id, String displayName) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO complex (id, region_code, apt_seq, legal_dong, jibun,"
                        + " display_name, slug) VALUES (?,'11710',?,'테스트동',?,?,?)",
                id, "11710-" + id, String.valueOf(id), displayName, "slug-" + id);
    }

    private void stat(long complexId, int bucket, String confidence) {
        complex(complexId, "단지" + complexId);
        jdbcTemplate.update(
                "INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,"
                        + " median_deposit, sample_count, source_level, confidence)"
                        + " VALUES (?,?,?,500000000,5,'L1',?)",
                SNAPSHOT, complexId, bucket, confidence);
    }

    @Test
    void 단지에_LOW가_아닌_평형이_하나라도_있으면_색인_가능이다() {
        stat(1, 85, "LOW");
        stat(1, 60, "HIGH");

        assertThat(reporter.report().indexable()).isEqualTo(1);
    }

    @Test
    void 모든_평형이_LOW인_단지는_색인_불가다() {
        stat(2, 85, "LOW");
        stat(2, 60, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(1);
        assertThat(r.indexable()).isZero();
    }

    @Test
    void 색인_가능_비율을_계산한다() {
        stat(3, 85, "HIGH");
        stat(4, 85, "MEDIUM");
        stat(5, 85, "LOW");
        stat(6, 85, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(4);
        assertThat(r.indexable()).isEqualTo(2);
        assertThat(r.indexableRatio()).isEqualTo(0.5);
    }

    @Test
    void 완료된_스냅샷이_없으면_전부_0이다() {
        jdbcTemplate.update("DELETE FROM metric_snapshot");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }

    @Test
    void 단지가_하나도_없으면_비율이_0이고_0으로_나누지_않는다() {
        var r = reporter.report();

        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }

    @Test
    void 색인_판정이_ComplexPageQuery의_판정과_일치한다() {
        // 색인 규칙이 여기(SQL)와 ComplexPageQuery.indexable()(Java) 두 곳에 있다
        // (원장 R-4). SitemapControllerTest의 대조 테스트와 같은 모양으로, 경계
        // 사례마다 두 구현이 갈라지지 않는지 직접 맞춰본다 — 하나만 세면(예: confidence만)
        // 다른 쪽(이름) 조건을 빠뜨린 채로 조용히 갈라질 수 있다.
        complex(101, "헬리오시티");
        stat(101, 85, "MEDIUM");              // 색인 대상

        complex(102, "(123-4)");
        stat(102, 85, "MEDIUM");              // 지번뿐인 이름 — 신뢰도는 통과해도 배제

        complex(103, "리센츠");
        stat(103, 85, "LOW");                 // 신뢰도 미달

        complex(104, "시세없음단지");           // complex_rent_stat 행 자체가 없음

        List<Long> ids = List.of(101L, 102L, 103L, 104L);
        long expectedIndexable = ids.stream()
                .map(id -> complexPageQuery.find(id).orElseThrow())
                .filter(ComplexPage::indexable)
                .count();

        // 위 시나리오 자체가 기대와 다르게 짜이면(예: 대상이 하나도 안 됨) 대조가
        // 아무 의미 없이 통과해버리므로, 최소 하나는 색인 대상이어야 한다는 것도 고정한다.
        assertThat(expectedIndexable).isEqualTo(1);

        assertThat(reporter.report().indexable()).isEqualTo(expectedIndexable);
    }
}
