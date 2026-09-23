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

    /** maturity_forecast는 complex_rent_stat과 별도 테이블이다 — LOW 카운트가 섞이지 않는지
     *  검증하려면 이쪽에도 독립적으로 행을 심을 수 있어야 한다. */
    private void maturityRow(long complexId, int bucket, String maturityYm, String confidence) {
        complex(complexId, "단지" + complexId);
        jdbcTemplate.update(
                "INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,"
                        + " contract_count, estimated_count, median_deposit_then,"
                        + " current_median_deposit, gap_amount, gap_rate, renewal_exhausted_count,"
                        + " confidence)"
                        + " VALUES (?,?,?,?,5,0,500000000,500000000,0,0.0,0,?)",
                SNAPSHOT, complexId, bucket, maturityYm, confidence);
    }

    /** uk_deal_flag_key는 (snapshot_date, deal_hash, flag_type)이므로 같은 deal_hash에
     *  서로 다른 flag_type을 여러 번 붙일 수 있다 — flaggedDeals의 DISTINCT를 검증하려면
     *  바로 이 조합이 필요하다. */
    private void flag(String dealHash, long complexId, String flagType) {
        complex(complexId, "단지" + complexId);
        jdbcTemplate.update(
                "INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)"
                        + " VALUES (?,?,?,?,NOW())",
                SNAPSHOT, dealHash, complexId, flagType);
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
        assertThat(r.pricedComplexes()).isEqualTo(1);
        assertThat(r.indexable()).isZero();
    }

    @Test
    void 색인_가능_비율을_계산한다() {
        stat(3, 85, "HIGH");
        stat(4, 85, "MEDIUM");
        stat(5, 85, "LOW");
        stat(6, 85, "LOW");

        var r = reporter.report();
        assertThat(r.pricedComplexes()).isEqualTo(4);
        assertThat(r.indexable()).isEqualTo(2);
        assertThat(r.indexableRatio()).isEqualTo(0.5);
    }

    @Test
    void 완료된_스냅샷이_없으면_전부_0이다() {
        jdbcTemplate.update("DELETE FROM metric_snapshot");

        var r = reporter.report();
        assertThat(r.pricedComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }

    @Test
    void 단지가_하나도_없으면_비율이_0이고_0으로_나누지_않는다() {
        var r = reporter.report();

        assertThat(r.pricedComplexes()).isZero();
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

    @Test
    void 만기_예측_행수를_잰다() {
        maturityRow(10, 85, "202612", "MEDIUM");
        maturityRow(11, 60, "202701", "HIGH");

        assertThat(reporter.report().maturityRows()).isEqualTo(2);
    }

    @Test
    void 시세_LOW_행수는_complex_rent_stat_기준이며_만기예측_LOW와_섞이지_않는다() {
        // complex_rent_stat LOW는 1건뿐이다.
        stat(20, 85, "LOW");
        stat(20, 60, "HIGH");
        // maturity_forecast LOW는 3건 — 다른 테이블, 다른 개수다. lowConfidenceStatRows가
        // 이 테이블을 세거나 두 테이블을 합치면 1이 아닌 값(예: 3, 4)이 나온다.
        maturityRow(21, 85, "202612", "LOW");
        maturityRow(21, 60, "202701", "LOW");
        maturityRow(22, 85, "202612", "LOW");

        assertThat(reporter.report().lowConfidenceStatRows()).isEqualTo(1);
    }

    @Test
    void 플래그된_거래_수는_deal_hash_기준이며_원본_행수와_다르다() {
        // 같은 거래(deal-1)가 플래그 타입 두 개를 동시에 받는다 — 원본 행은 2개지만
        // 거래는 하나다. COUNT(*)로 "단순화"하면 이 테스트가 실패한다.
        flag("deal-1", 30, "CANCELED");
        flag("deal-1", 30, "DIRECT");
        flag("deal-2", 31, "UNREGISTERED");

        assertThat(reporter.report().flaggedDeals()).isEqualTo(2);

        Integer rawRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deal_flag WHERE snapshot_date = ?", Integer.class, SNAPSHOT);
        assertThat(rawRows).isEqualTo(3);
    }

    @Test
    void 더_최신이어도_COMPLETE가_아닌_스냅샷은_반영되지_않는다() {
        // F-33 계열: findLatestComplete()가 findAll().getLast() 같은 것으로 바뀌면
        // BUILDING/FAILED라도 더 최신 snapshot_date를 골라버릴 수 있다. 이 테스트가
        // 없으면 모든 테스트가 COMPLETE 스냅샷을 정확히 하나만 심으므로 그런 회귀를
        // 아무것도 잡아내지 못한다.
        stat(40, 85, "HIGH");
        stat(41, 85, "MEDIUM");

        // 더 최신인 BUILDING 스냅샷을 심는다 — 데이터는 전혀 넣지 않는다. 게이트가
        // 깨져 report()가 이 날짜를 보게 되면 pricedComplexes가 0으로 무너진다.
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at)"
                        + " VALUES (?, 'BUILDING', NOW())", SNAPSHOT.plusDays(1));

        assertThat(reporter.report().pricedComplexes()).isEqualTo(2);
    }
}
