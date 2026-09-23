package com.jeonseradar.web;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.region.RankingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * sitemap.xml과 robots.txt.
 *
 * <p>색인 판정은 여기서 다시 정의하지 않는다. 단지 색인 조건은
 * {@link com.jeonseradar.web.complex.ComplexPageQuery}의 {@code indexable(String, List)}
 * (Task 3)가, 지역 색인 조건은 {@link com.jeonseradar.web.region.RegionPage#indexable()}
 * (Task 6)이 Java로 이미 정의했다. sitemap은 전체를 {@code find()}로 한 건씩 훑을 수 없어
 * (집합 질의가 필요해서) 같은 조건을 SQL로 한 번 더 쓸 수밖에 없다 — 그래서 조건이 두 곳에
 * 있다. 두 곳이 갈라지면 noindex인 페이지를 sitemap이 제출하는 모순이 생기므로,
 * {@code SitemapControllerTest#sitemap_수록_여부가_단지_페이지의_색인_판정과_일치한다}와
 * {@code SitemapControllerTest#sitemap_수록_여부가_지역_페이지의_색인_판정과_일치한다}가
 * 각각의 경계 사례마다 둘을 직접 대조한다 (원장 P3-6).
 */
@Controller
public class SitemapController {

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;
    private final RankingProperties ranking;
    private final String baseUrl;

    public SitemapController(JdbcTemplate jdbc, PublishedSnapshot published, SigunguCatalog catalog,
                              RankingProperties ranking,
                              @Value("${jeonse.base-url:https://example.invalid}") String baseUrl) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
        this.ranking = ranking;
        this.baseUrl = baseUrl;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *
                Allow: /

                Sitemap: %s/sitemap.xml
                """.formatted(baseUrl);
    }

    /**
     * 색인 조건은 {@code ComplexPageQuery.indexable(displayName, stats)}와 한 줄씩 대응해야
     * 한다 (클래스 주석 참고):
     * <ul>
     *   <li>{@code JIBUN_ONLY.matcher(displayName).matches()}(전체가 {@code (...)} 형태)
     *       ↔ {@code NOT (LEFT(display_name,1)='(' AND RIGHT(display_name,1)=')')}.
     *       정규식 {@code ^\(.*\)$}를 MySQL REGEXP로 그대로 옮기면 문자열 리터럴 이스케이프가
     *       두 겹(자바 텍스트 블록 → MySQL 문자열 리터럴)으로 겹쳐 검증 없이는 신뢰하기 어렵다.
     *       "전체가 괄호로 감싸여 있다"는 조건은 "첫 글자가 '(' 이고 마지막 글자가 ')'"와
     *       정확히 같으므로, REGEXP 대신 이 동치 표현을 쓴다.</li>
     *   <li>{@code stats.stream().anyMatch(s -> !"LOW".equals(s.confidence()))} ↔
     *       {@code EXISTS (... AND (confidence IS NULL OR confidence <> 'LOW'))}.
     *       {@code confidence}가 NULL인 행에서도 자바 쪽 {@code !"LOW".equals(null)}이
     *       {@code true}이므로(즉 "LOW 아님"으로 집계), SQL도 NULL을 같은 쪽으로 맞춘다.</li>
     * </ul>
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        LocalDate snapshotDate = published.date();

        List<String> complexPaths = jdbc.query("""
                SELECT c.id, c.slug
                FROM complex c
                WHERE NOT (LEFT(c.display_name, 1) = '(' AND RIGHT(c.display_name, 1) = ')')
                  AND EXISTS (
                        SELECT 1 FROM complex_rent_stat s
                        WHERE s.snapshot_date = ?
                          AND s.complex_id = c.id
                          AND (s.confidence IS NULL OR s.confidence <> 'LOW'))
                ORDER BY c.id
                """,
                (rs, n) -> path("/단지/" + rs.getLong("id") + "-" + rs.getString("slug")),
                snapshotDate);

        // 지역 색인 조건은 RegionPage.indexable() (ranking이 비어있지 않다)과 같아야 한다
        // (원장 P3-6). ranking은 RegionPageQuery.find()가 아래와 정확히 같은 WHERE로 만든다:
        // gap_amount > 0 AND contract_count >= minContractCount AND confidence IN acceptedConfidences,
        // 같은 스냅샷 날짜·같은 region_code. LIMIT은 결과가 비어있는지 여부에 영향이 없으므로
        // (조건을 만족하는 행이 하나라도 있으면 LIMIT과 무관하게 비어있지 않다) 여기서는 뺀다.
        // 문턱 값은 RankingProperties를 주입받아 RegionPageQuery와 같은 소스를 쓴다 — 숫자를
        // 여기 다시 적으면 언젠가 그 값과 갈라진다.
        String placeholders = String.join(",",
                Collections.nCopies(ranking.acceptedConfidences().size(), "?"));
        List<Object> regionArgs = new ArrayList<>();
        regionArgs.add(snapshotDate);
        regionArgs.add(ranking.minContractCount());
        regionArgs.addAll(ranking.acceptedConfidences());

        List<String> regionPaths = jdbc.queryForList("""
                        SELECT DISTINCT c.region_code
                        FROM complex c
                        JOIN maturity_forecast f ON f.complex_id = c.id
                        WHERE f.snapshot_date = ?
                          AND f.gap_amount > 0
                          AND f.contract_count >= ?
                          AND f.confidence IN (%s)
                        ORDER BY c.region_code
                        """.formatted(placeholders),
                        String.class, regionArgs.toArray())
                .stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .map(name -> path(name.hasSigungu()
                        ? "/역전세/" + name.sidoSegment() + "/" + name.sigunguSegment()
                        : "/역전세/" + name.sidoSegment()))
                .distinct()
                .toList();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        regionPaths.forEach(p -> appendUrl(xml, p, snapshotDate));
        complexPaths.forEach(p -> appendUrl(xml, p, snapshotDate));
        xml.append("</urlset>\n");
        return xml.toString();
    }

    /**
     * 퍼센트 인코딩한다 (원장 P3-3). HTTP 헤더는 ISO-8859-1이라 한글 경로를 날것으로
     * {@code Location} 헤더에 실으면 Tomcat이 통째로 버리는 결함을 실제로 겪었다.
     * sitemap.xml의 {@code <loc>}도 sitemaps.org 스펙상 제대로 이스케이프된 URL이어야
     * 하므로 {@code encodePathSegment}가 아니라 {@code /}를 보존하는 {@code encodePath}를 쓴다.
     */
    private String path(String rawPath) {
        return UriUtils.encodePath(rawPath, StandardCharsets.UTF_8);
    }

    private void appendUrl(StringBuilder xml, String path, LocalDate lastmod) {
        xml.append("  <url><loc>").append(baseUrl).append(path)
           .append("</loc><lastmod>").append(lastmod).append("</lastmod></url>\n");
    }
}
