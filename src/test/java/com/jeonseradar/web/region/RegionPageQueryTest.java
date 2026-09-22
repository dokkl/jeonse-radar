package com.jeonseradar.web.region;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RegionPageQueryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired RegionPageQuery query;
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

    private void complex(long id, String code, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, ?, '잠실동', '40', ?, ?, 2018)
                """, id, code + "-" + id, code, name, id + "-단지-" + code + "-1");
    }

    private void forecast(long complexId, String ym, long gap, int contracts, String conf) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, ?, 84, ?, ?, 0, 1000000000, ?, ?, 0.1, 1, ?)
                """, SNAPSHOT, complexId, ym, contracts, 1_000_000_000L - gap, gap, conf);
    }

    @Test
    void 단지가_없는_지역은_비어있다() {
        assertThat(query.find("11710")).isEmpty();
    }

    @Test
    void 지역_이름과_기준일을_준다() {
        complex(1, "11710", "헬리오시티");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.regionName()).isEqualTo("송파구");
        assertThat(page.sido()).isEqualTo("서울특별시");
        assertThat(page.snapshotDate()).isEqualTo(SNAPSHOT);
    }

    @Test
    void 랭킹은_갭_내림차순이다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 300_000_000L, 5, "MEDIUM");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("나단지", "가단지");
    }

    @Test
    void 계약_건수가_문턱_미만이면_랭킹에서_빠진다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 1, "MEDIUM"); // 갭은 크지만 계약 1건

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("가단지");
    }

    @Test
    void 신뢰도가_문턱_미만이면_랭킹에서_빠진다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 9, "LOW");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("가단지");
    }

    @Test
    void 갭이_음수인_행은_역전세가_아니므로_랭킹에_없다() {
        complex(1, "11710", "가단지");
        forecast(1, "202612", -100_000_000L, 5, "MEDIUM");

        assertThat(query.find("11710").orElseThrow().ranking()).isEmpty();
    }

    @Test
    void 요약은_거른_행이_아니라_전체를_센다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 1, "LOW"); // 랭킹에선 빠지지만 집계엔 든다

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).hasSize(1);
        assertThat(page.totalMaturityContracts()).isEqualTo(6);
        assertThat(page.negativeGapContracts()).isEqualTo(6);
    }

    @Test
    void 시도의_시군구_코드를_준다() {
        complex(1, "11710", "가단지");
        complex(2, "11680", "나단지");
        complex(3, "41111", "다단지");

        assertThat(query.sigunguCodesOf("서울특별시")).containsExactlyInAnyOrder("11710", "11680");
    }
}
