package com.jeonseradar.metric.service;

import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RenewalReductionBuilderTest extends IntegrationTestSupport {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    RenewalReductionBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** complex 한 건을 만든다. */
    private void complex(long id, String regionCode, String legalDong) {
        jdbcTemplate.update(
                "INSERT INTO complex (id, region_code, apt_seq, legal_dong, jibun, display_name, slug) "
                        + "VALUES (?,?,?,?,'1','단지',?)",
                id, regionCode, regionCode + "-" + id, legalDong, "slug-" + id);
    }

    /**
     * 갱신 계약 한 건. prevDeposit이 0/null이면 "이전 보증금 없음"을, deposit과
     * prevDeposit이 같으면 "동일 유지"를, deposit이 더 크면 "증액"을 표현한다.
     */
    private void renewal(String dealHash, long complexId, String regionCode, String legalDong,
                         int areaBucket, long deposit, Long prevDeposit, LocalDate dealDate) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " prev_deposit, term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?, '1','단지',?,?,3,?,?,0,?,FALSE,FALSE,NOW(),NOW())",
                dealHash, complexId, regionCode + "-" + complexId, regionCode, legalDong,
                java.math.BigDecimal.valueOf(areaBucket), areaBucket, dealDate, deposit, prevDeposit);
    }

    /** 월세가 있는 계약(반전세) 한 건. 순수 전세 필터 검증용. */
    private void mixedRenewal(String dealHash, long complexId, String regionCode, String legalDong,
                              int areaBucket, long deposit, long monthlyRent, Long prevDeposit,
                              LocalDate dealDate) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " prev_deposit, term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?, '1','단지',?,?,3,?,?,?,?,FALSE,FALSE,NOW(),NOW())",
                dealHash, complexId, regionCode + "-" + complexId, regionCode, legalDong,
                java.math.BigDecimal.valueOf(areaBucket), areaBucket, dealDate, deposit, monthlyRent,
                prevDeposit);
    }

    private Map<String, Object> statOf(long complexId, int areaBucket) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM renewal_reduction_stat WHERE complex_id = ? AND area_bucket = ?",
                complexId, areaBucket);
    }

    private boolean rowExists(long complexId, int areaBucket) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM renewal_reduction_stat WHERE complex_id = ? AND area_bucket = ?",
                Integer.class, complexId, areaBucket);
        return n != null && n > 0;
    }

    @Test
    void 감액이_있는_그룹은_renewal_count와_reduction_count와_중위값과_최신일자를_기록한다() {
        complex(1, "11680", "대치동");
        // 감액 2건 + 증액 1건 = renewal_count 3, reduction_count 2
        renewal("r1", 1, "11680", "대치동", 85, 400_000_000L, 500_000_000L,
                LocalDate.of(2026, 6, 1));   // 감액 1억
        renewal("r2", 1, "11680", "대치동", 85, 450_000_000L, 500_000_000L,
                LocalDate.of(2026, 8, 1));   // 감액 5천만 (가장 최근)
        renewal("r3", 1, "11680", "대치동", 85, 550_000_000L, 500_000_000L,
                LocalDate.of(2026, 7, 1));   // 증액 — reduction 아님

        builder.build(SNAPSHOT);

        Map<String, Object> stat = statOf(1, 85);
        assertThat(((Number) stat.get("renewal_count")).intValue()).isEqualTo(3);
        assertThat(((Number) stat.get("reduction_count")).intValue()).isEqualTo(2);
        // 감액분: 1억, 5천만 → 중위값(홀수 아님, 2건) = 평균 = 7500만
        assertThat(((Number) stat.get("median_reduction")).longValue()).isEqualTo(75_000_000L);
        assertThat(((java.sql.Date) stat.get("latest_reduction_date")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void 표본이_짝수면_가운데_두_값의_평균이다() {
        // 중위값 로직 자체를 서로 다른 금액 4건으로 검증한다 — 가운데 두 값의 평균이
        // 한쪽 값만 취하는 구현이었다면 이 테스트가 실패한다.
        complex(2, "11680", "대치동");
        renewal("e1", 2, "11680", "대치동", 85, 900_000_000L, 1_000_000_000L,
                LocalDate.of(2026, 1, 1));   // 감액 1억
        renewal("e2", 2, "11680", "대치동", 85, 880_000_000L, 1_000_000_000L,
                LocalDate.of(2026, 2, 1));   // 감액 1.2억
        renewal("e3", 2, "11680", "대치동", 85, 860_000_000L, 1_000_000_000L,
                LocalDate.of(2026, 3, 1));   // 감액 1.4억
        renewal("e4", 2, "11680", "대치동", 85, 840_000_000L, 1_000_000_000L,
                LocalDate.of(2026, 4, 1));   // 감액 1.6억

        builder.build(SNAPSHOT);

        // 정렬: 1.0억, 1.2억, 1.4억, 1.6억 → 가운데 두 값(1.2억, 1.4억)의 평균 = 1.3억
        Map<String, Object> stat = statOf(2, 85);
        assertThat(((Number) stat.get("reduction_count")).intValue()).isEqualTo(4);
        assertThat(((Number) stat.get("median_reduction")).longValue()).isEqualTo(130_000_000L);
    }

    @Test
    void 전부_증액이거나_동일하면_행이_생기지_않는다() {
        complex(3, "11680", "잠실동");
        renewal("n1", 3, "11680", "잠실동", 85, 500_000_000L, 500_000_000L,
                LocalDate.of(2026, 8, 1)); // 동일 유지
        renewal("n2", 3, "11680", "잠실동", 85, 520_000_000L, 500_000_000L,
                LocalDate.of(2026, 7, 1)); // 증액

        builder.build(SNAPSHOT);

        assertThat(rowExists(3, 85)).isFalse();
    }

    @Test
    void 월세가_있는_계약과_이전_보증금이_없는_계약은_제외된다() {
        complex(4, "11680", "논현동");
        // 진짜 감액 1건
        renewal("real-1", 4, "11680", "논현동", 85, 400_000_000L, 500_000_000L,
                LocalDate.of(2026, 8, 1));
        // 반전세: monthly_rent > 0 → 제외
        mixedRenewal("mixed-1", 4, "11680", "논현동", 85, 100_000_000L, 500_000L,
                200_000_000L, LocalDate.of(2026, 8, 1));
        // prev_deposit이 없는(신규) 계약 → 갱신이 아니므로 제외
        renewal("new-1", 4, "11680", "논현동", 85, 300_000_000L, null,
                LocalDate.of(2026, 8, 1));
        // prev_deposit = 0인 계약 → 이전 보증금 없음과 동일하게 취급, 제외
        renewal("zero-1", 4, "11680", "논현동", 85, 300_000_000L, 0L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT);

        Map<String, Object> stat = statOf(4, 85);
        assertThat(((Number) stat.get("renewal_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("reduction_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_reduction")).longValue()).isEqualTo(100_000_000L);
    }

    @Test
    void 같은_스냅샷을_다시_빌드해도_행이_중복되지_않는다() {
        // RenewalReductionBuilder 자신은 clearExisting을 모른다 — 이 테스트는 그 전제를
        // 명시적으로 남긴다: 같은 snapshot_date로 두 번 build()하면 재삽입 전에 먼저
        // 지워야 한다(SnapshotBuilder.clearExisting의 책임). 여기서는 그 전제를 직접
        // 재현해, 빌더가 스스로 멱등적이지 않다는 사실과 지운 뒤에는 정확히 한 행만
        // 남는다는 사실을 함께 고정한다.
        complex(5, "11680", "신사동");
        renewal("dup-1", 5, "11680", "신사동", 85, 400_000_000L, 500_000_000L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT);
        jdbcTemplate.update("DELETE FROM renewal_reduction_stat WHERE snapshot_date = ?", SNAPSHOT);
        builder.build(SNAPSHOT);

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM renewal_reduction_stat WHERE snapshot_date = ? AND complex_id = ?"
                        + " AND area_bucket = ?",
                Integer.class, SNAPSHOT, 5L, 85);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void 지우지_않고_같은_스냅샷을_두_번_빌드하면_유니크_제약에_걸린다() {
        // 위 테스트의 반대쪽 절반: clearExisting 없이 재실행하면 실패해야 한다는 것을
        // 확인해, "왜 SnapshotBuilder.clearExisting에 이 테이블을 반드시 추가해야
        // 하는지"를 이 빌더 자신의 테스트 안에서도 보여준다.
        complex(6, "11680", "신사동");
        renewal("dup-2", 6, "11680", "신사동", 85, 400_000_000L, 500_000_000L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT);

        assertThatThrownBy(() -> builder.build(SNAPSHOT)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void 서로_다른_평형은_각각_행이_생긴다() {
        complex(7, "11680", "청담동");
        renewal("a1", 7, "11680", "청담동", 59, 300_000_000L, 350_000_000L,
                LocalDate.of(2026, 8, 1));
        renewal("a2", 7, "11680", "청담동", 85, 500_000_000L, 600_000_000L,
                LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT);

        assertThat(rowExists(7, 59)).isTrue();
        assertThat(rowExists(7, 85)).isTrue();
    }
}
