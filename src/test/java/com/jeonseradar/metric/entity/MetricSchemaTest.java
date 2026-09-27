package com.jeonseradar.metric.entity;

import com.jeonseradar.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MetricSchemaTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private boolean tableExists(String name) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, name);
        return n != null && n > 0;
    }

    @Test
    void 집계_테이블_다섯_개가_생성된다() {
        assertThat(tableExists("metric_snapshot")).isTrue();
        assertThat(tableExists("complex_rent_stat")).isTrue();
        assertThat(tableExists("maturity_forecast")).isTrue();
        assertThat(tableExists("deal_flag")).isTrue();
        assertThat(tableExists("renewal_reduction_stat")).isTrue();
    }

    @Test
    void 스냅샷과_단지평형_조합에_유니크_제약이_걸린다() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'complex_rent_stat' "
                        + "AND constraint_type = 'UNIQUE'",
                Integer.class);

        assertThat(n).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 표본이_10건_이상이고_L1이면_HIGH다() {
        assertThat(Confidence.of(10, SourceLevel.L1)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.of(37, SourceLevel.L1)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void 표본이_3에서_9건이면_MEDIUM이다() {
        assertThat(Confidence.of(3, SourceLevel.L1)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(9, SourceLevel.L1)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void 표본이_1에서_2건이면_LOW다() {
        assertThat(Confidence.of(1, SourceLevel.L1)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(2, SourceLevel.L1)).isEqualTo(Confidence.LOW);
    }

    @Test
    void 폴백_계층이_낮으면_표본이_많아도_상한에_묶인다() {
        // L2는 상한이 MEDIUM, L3는 상한이 LOW (스펙 §3.5)
        assertThat(Confidence.of(100, SourceLevel.L2)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(100, SourceLevel.L3)).isEqualTo(Confidence.LOW);
    }

    @Test
    void 표본이_적으면_계층이_높아도_표본_쪽이_이긴다() {
        // 둘 중 낮은 쪽을 채택한다
        assertThat(Confidence.of(1, SourceLevel.L2)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(5, SourceLevel.L2)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void 표본이_0이면_산출_불가이므로_LOW다() {
        assertThat(Confidence.of(0, SourceLevel.L1)).isEqualTo(Confidence.LOW);
    }
}
