package com.jeonseradar.web.root;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.PublishedSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class RootPageQuery {

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;

    public RootPageQuery(JdbcTemplate jdbc, PublishedSnapshot published, SigunguCatalog catalog) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
    }

    /**
     * 시도 목록을 {@code RegionPageQuery.sigunguCodesOf}와 같은 소스(complex의 region_code)에서
     * 뽑는다. 다른 소스를 쓰면 루트가 링크한 시도를 시도 인덱스가 404로 받는 날이 온다.
     *
     * <p>발행 관문을 먼저 지난다 (원장 R-6). 안 거치면 COMPLETE 스냅샷이 하나도 없는
     * 상태에서도 루트가 200으로 시도들을 링크하고, 그 자식들은 전부 503이라 크롤러가
     * 빈 사이트를 색인한다.
     */
    public RootPage load() {
        LocalDate date = published.date();

        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT region_code FROM complex ORDER BY region_code", String.class);
        List<String> sidos = codes.stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)).sido())
                .distinct()
                .toList();

        Long complexCount = jdbc.queryForObject("SELECT COUNT(*) FROM complex", Long.class);
        return new RootPage(date, codes.size(), complexCount == null ? 0L : complexCount, sidos);
    }
}
