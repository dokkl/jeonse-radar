package com.jeonseradar.web.region;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.PublishedSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RegionPageQuery {

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;
    private final RankingProperties ranking;

    public RegionPageQuery(JdbcTemplate jdbc, PublishedSnapshot published,
                           SigunguCatalog catalog, RankingProperties ranking) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
        this.ranking = ranking;
    }

    public Optional<RegionPage> find(String regionCode) {
        LocalDate snapshotDate = published.date();

        Integer complexCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complex WHERE region_code = ?", Integer.class, regionCode);
        if (complexCount == null || complexCount == 0) {
            return Optional.empty();
        }

        // 요약은 랭킹 필터를 적용하지 않는다. 거르는 것은 목록이지 사실이 아니다.
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT COALESCE(SUM(f.contract_count), 0) total,
                       COALESCE(SUM(CASE WHEN f.gap_amount > 0 THEN f.contract_count ELSE 0 END), 0) negative
                FROM maturity_forecast f JOIN complex c ON c.id = f.complex_id
                WHERE f.snapshot_date = ? AND c.region_code = ?
                """, snapshotDate, regionCode);

        String placeholders = String.join(",",
                java.util.Collections.nCopies(ranking.acceptedConfidences().size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(snapshotDate);
        args.add(regionCode);
        args.add(ranking.minContractCount());
        args.addAll(ranking.acceptedConfidences());
        args.add(ranking.limit());

        List<RegionPage.RankRow> rows = jdbc.query("""
                SELECT c.id, c.slug, c.display_name, c.legal_dong,
                       f.area_bucket, f.maturity_ym, f.contract_count,
                       f.gap_amount, f.gap_rate, f.renewal_exhausted_count, f.confidence
                FROM maturity_forecast f JOIN complex c ON c.id = f.complex_id
                WHERE f.snapshot_date = ? AND c.region_code = ?
                  AND f.gap_amount > 0
                  AND f.contract_count >= ?
                  AND f.confidence IN (%s)
                ORDER BY f.gap_amount DESC
                LIMIT ?
                """.formatted(placeholders),
                (rs, n) -> new RegionPage.RankRow(
                        rs.getLong("id"), rs.getString("slug"), rs.getString("display_name"),
                        rs.getString("legal_dong"), rs.getInt("area_bucket"),
                        rs.getString("maturity_ym"), rs.getInt("contract_count"),
                        rs.getLong("gap_amount"), rs.getDouble("gap_rate"),
                        rs.getInt("renewal_exhausted_count"), rs.getString("confidence")),
                args.toArray());

        RegionName name = RegionName.of(regionCode, catalog.nameOf(regionCode));
        return Optional.of(new RegionPage(
                regionCode, name.sido(), name.displayName(),
                name.sidoSegment(), name.sigunguSegment(), snapshotDate,
                complexCount,
                ((Number) summary.get("total")).longValue(),
                ((Number) summary.get("negative")).longValue(),
                rows));
    }

    /** 시도 인덱스 페이지용. 단지가 실제로 있는 시군구만 준다. */
    public List<String> sigunguCodesOf(String sido) {
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT region_code FROM complex ORDER BY region_code", String.class);
        return codes.stream()
                .filter(code -> sido.equals(RegionName.of(code, catalog.nameOf(code)).sido()))
                .toList();
    }
}
