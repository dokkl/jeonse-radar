package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 1단계 종료 조건. 만기 예측 전체가 계약기간 필드에 의존하므로
 * 실제 결측·파싱실패 비율을 실측해야 2단계 설계를 확정할 수 있다 (스펙 §6 검증 게이트).
 */
@Service
public class DataQualityReporter {

    private static final Logger log = LoggerFactory.getLogger(DataQualityReporter.class);

    public record QualityReport(
            long totalRent,
            long estimatedTerm,
            double estimatedRatio,
            long pureJeonse,
            long renewalRightUsed,
            long complexCount
    ) {
    }

    private final JdbcTemplate jdbcTemplate;

    public DataQualityReporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public QualityReport report() {
        long total = count("SELECT COUNT(*) FROM rent_deal");
        long estimated = count("SELECT COUNT(*) FROM rent_deal WHERE term_estimated = TRUE");
        long pureJeonse = count("SELECT COUNT(*) FROM rent_deal WHERE monthly_rent = 0");
        long renewalUsed = count("SELECT COUNT(*) FROM rent_deal WHERE renewal_right_used = TRUE");
        long complexes = count("SELECT COUNT(*) FROM complex");

        double ratio = total == 0 ? 0.0 : (double) estimated / total;

        QualityReport report = new QualityReport(
                total, estimated, ratio, pureJeonse, renewalUsed, complexes);
        log.info("데이터 품질 리포트: {}", report);
        return report;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
