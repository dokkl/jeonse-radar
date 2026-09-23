package com.jeonseradar.metric.service;

import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 3단계가 실제로 발행할 수 있는 페이지가 몇 장인지 잰다.
 *
 * 스펙 §4.1이 confidence = LOW면 noindex라고 정했으므로, 색인 가능한 단지 수가
 * 곧 3단계가 만들 수 있는 검색 유입의 상한이다. 이 리포터는 스냅샷마다 그 숫자를
 * 다시 잴 수 있게 만들어, 손으로 짠 SQL 한 번으로 끝내지 않고 매 스냅샷을
 * 재현 가능하게 보고한다.
 *
 * <p><b>{@code indexable}은 사이트가 실제로 적용하는 색인 가능 판정 그대로다 — 두 조건
 * 전부.</b> {@link com.jeonseradar.web.complex.ComplexPageQuery#find}가 Java로 정의한
 * 판정(원장 R-4)과 정확히 같아야 한다:
 * <ol>
 *   <li>그 단지의 평형 중 confidence가 LOW가 아닌 것이 하나라도 있어야 한다
 *       (confidence가 NULL인 행도 "LOW 아님"으로 센다 — Java의
 *       {@code !"LOW".equals(s.confidence())}가 null에도 true이므로).</li>
 *   <li>{@code display_name}이 지번만 남은 이름(예: {@code (828-76)})이면 안 된다.
 *       "(828-76) 전세 만기·역전세 현황"이라는 제목의 페이지는 스펙이 막으려던 바로
 *       그것이다.</li>
 * </ol>
 * 확인 조건만 세면(1번만) 이름 조건을 빠뜨린 단지까지 "색인 가능"으로 잘못 세게 된다 —
 * 실제로 이 리포터가 한동안 그랬다. {@link com.jeonseradar.web.SitemapController}도 집합
 * 질의가 필요해 같은 두 조건을 SQL로 다시 쓴다; 정규식 {@code ^\(.*\)$}는 검증 없이
 * 신뢰하기 어려운 이스케이프 이슈(자바 텍스트 블록 → SQL 리터럴 → MySQL 3중)가 있어
 * sitemap과 마찬가지로 "첫 글자가 '(' 이고 마지막 글자가 ')'"라는 동치 표현을 쓴다.
 * {@code CoverageReporterTest}가 {@code ComplexPageQuery.find(id).indexable()}과
 * 이 카운트를 직접 대조해, 두 구현이 다시 갈라지면 그 자리에서 실패한다.
 */
@Service
public class CoverageReporter {

    private static final Logger log = LoggerFactory.getLogger(CoverageReporter.class);

    public record CoverageReport(
            long totalComplexes,
            long indexable,
            double indexableRatio,
            long maturityRows,
            long lowConfidenceRows,
            long flaggedDeals
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MetricSnapshotRepository snapshotRepository;

    public CoverageReporter(JdbcTemplate jdbcTemplate,
                            MetricSnapshotRepository snapshotRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
    }

    public CoverageReport report() {
        var snapshot = snapshotRepository.findLatestComplete().orElse(null);
        if (snapshot == null) {
            log.warn("완료된 집계 스냅샷이 없어 커버리지를 계산할 수 없다");
            return new CoverageReport(0, 0, 0.0, 0, 0, 0);
        }
        LocalDate d = snapshot.getSnapshotDate();

        long total = count(
                "SELECT COUNT(DISTINCT complex_id) FROM complex_rent_stat WHERE snapshot_date = ?", d);
        long indexable = count(
                "SELECT COUNT(*) FROM complex c "
                        + "WHERE NOT (LEFT(c.display_name, 1) = '(' AND RIGHT(c.display_name, 1) = ')') "
                        + "AND EXISTS (SELECT 1 FROM complex_rent_stat s "
                        + "WHERE s.snapshot_date = ? AND s.complex_id = c.id "
                        + "AND (s.confidence IS NULL OR s.confidence <> 'LOW'))", d);
        long maturityRows = count(
                "SELECT COUNT(*) FROM maturity_forecast WHERE snapshot_date = ?", d);
        long lowRows = count(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE snapshot_date = ? AND confidence = 'LOW'", d);
        long flagged = count(
                "SELECT COUNT(DISTINCT deal_hash) FROM deal_flag WHERE snapshot_date = ?", d);

        double ratio = total == 0 ? 0.0 : (double) indexable / total;
        CoverageReport report = new CoverageReport(
                total, indexable, ratio, maturityRows, lowRows, flagged);
        log.info("발행 커버리지 ({}): {}", d, report);
        return report;
    }

    private long count(String sql, LocalDate snapshotDate) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, snapshotDate);
        return v == null ? 0L : v;
    }
}
