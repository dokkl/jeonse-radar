package com.jeonseradar.web.complex;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.PublishedSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ComplexPageQuery {

    /** 국토부 원자료에 단지명이 없어 지번만 남은 경우: `(828-76)`. 실측 300건. */
    private static final Pattern JIBUN_ONLY = Pattern.compile("^\\(.*\\)$");

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;

    public ComplexPageQuery(JdbcTemplate jdbc, PublishedSnapshot published, SigunguCatalog catalog) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
    }

    public Optional<ComplexPage> find(long id) {
        LocalDate snapshotDate = published.date();

        List<Map<String, Object>> head = jdbc.queryForList("""
                SELECT id, slug, display_name, legal_dong, region_code, build_year
                FROM complex WHERE id = ?
                """, id);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> c = head.get(0);

        List<ComplexPage.AreaStat> stats = jdbc.query("""
                SELECT area_bucket, median_deposit, sample_count, source_level, confidence
                FROM complex_rent_stat
                WHERE snapshot_date = ? AND complex_id = ?
                ORDER BY area_bucket DESC
                """,
                (rs, n) -> new ComplexPage.AreaStat(
                        rs.getInt("area_bucket"), rs.getLong("median_deposit"),
                        rs.getInt("sample_count"), rs.getString("source_level"),
                        rs.getString("confidence")),
                snapshotDate, id);

        List<ComplexPage.MaturityRow> maturities = jdbc.query("""
                SELECT maturity_ym, area_bucket, contract_count, estimated_count,
                       median_deposit_then, current_median_deposit,
                       gap_amount, gap_rate, renewal_exhausted_count, confidence
                FROM maturity_forecast
                WHERE snapshot_date = ? AND complex_id = ?
                ORDER BY maturity_ym, area_bucket
                """,
                (rs, n) -> new ComplexPage.MaturityRow(
                        rs.getString("maturity_ym"), rs.getInt("area_bucket"),
                        rs.getInt("contract_count"), rs.getInt("estimated_count"),
                        rs.getLong("median_deposit_then"), rs.getLong("current_median_deposit"),
                        rs.getLong("gap_amount"), rs.getDouble("gap_rate"),
                        rs.getInt("renewal_exhausted_count"), rs.getString("confidence")),
                snapshotDate, id);

        String displayName = (String) c.get("display_name");
        String regionCode = (String) c.get("region_code");
        RegionName region = RegionName.of(regionCode, catalog.nameOf(regionCode));

        return Optional.of(new ComplexPage(
                ((Number) c.get("id")).longValue(),
                (String) c.get("slug"),
                displayName,
                (String) c.get("legal_dong"),
                region.displayName(),
                c.get("build_year") == null ? null : ((Number) c.get("build_year")).intValue(),
                snapshotDate,
                stats,
                maturities,
                indexable(displayName, stats)));
    }

    /**
     * 스펙 §4.1: confidence가 LOW이거나 산출 불가면 noindex. 자동 생성된 얇은 페이지를
     * 대량 발행하면 검색엔진이 doorway page로 보고 사이트 전체 평가를 낮춘다.
     *
     * 지번뿐인 이름을 더 배제하는 것은 스펙에 없는 추가 조건이다 (원장 R-4).
     * "(828-76) 전세 만기·역전세 현황"이라는 제목의 페이지는 스펙이 막으려던 바로 그것이다.
     */
    private boolean indexable(String displayName, List<ComplexPage.AreaStat> stats) {
        if (JIBUN_ONLY.matcher(displayName).matches()) {
            return false;
        }
        return stats.stream().anyMatch(s -> !"LOW".equals(s.confidence()));
    }
}
