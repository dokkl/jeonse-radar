package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 단지×평형×만기월 예측을 집계한다 (스펙 §3.2 / §3.4 / §3.6).
 *
 * 반드시 RentStatBuilder 다음에 실행된다 — 현재 시세를 complex_rent_stat에서 조인하기 때문이다.
 * 현재 시세가 없는 (단지,평형)은 갭을 계산할 수 없으므로 행을 만들지 않는다.
 *
 * 동/호가 공개되지 않아 개별 물건을 추적할 수 없으므로 모든 값은 집계 단위다 (스펙 §3.1).
 * "이 집이 역전세"가 아니라 "이 단지 이 평형에 만기가 N건 돌아오고 차액이 평균 얼마"다.
 */
@Service
public class MaturityForecastBuilder {

    private final JdbcTemplate jdbcTemplate;

    public MaturityForecastBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param snapshotDate 집계 결과에 찍을 스냅샷 날짜
     * @param fromYm       이 월부터의 만기만 예측한다 (yyyyMM). 지난 만기는 대상이 아니다.
     * @return 삽입된 행 수
     */
    @Transactional
    public int build(LocalDate snapshotDate, String fromYm) {
        String sql = """
                INSERT INTO maturity_forecast
                  (snapshot_date, complex_id, area_bucket, maturity_ym, contract_count,
                   estimated_count, median_deposit_then, current_median_deposit,
                   gap_amount, gap_rate, renewal_exhausted_count, confidence)
                WITH ranked AS (
                  SELECT complex_id, area_bucket, term_end_ym, deposit,
                         term_estimated, renewal_right_used,
                         ROW_NUMBER() OVER (
                           PARTITION BY complex_id, area_bucket, term_end_ym ORDER BY deposit) rn,
                         COUNT(*) OVER (
                           PARTITION BY complex_id, area_bucket, term_end_ym)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0
                    AND term_end_ym IS NOT NULL
                    AND term_end_ym >= ?
                ),
                median_then AS (
                  SELECT complex_id, area_bucket, term_end_ym,
                         AVG(deposit) AS median_deposit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s
                  GROUP BY complex_id, area_bucket, term_end_ym
                ),
                counts AS (
                  SELECT complex_id, area_bucket, term_end_ym,
                         COUNT(*)                        AS contract_count,
                         SUM(term_estimated)             AS estimated_count,
                         SUM(renewal_right_used)         AS renewal_exhausted_count
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND term_end_ym IS NOT NULL AND term_end_ym >= ?
                  GROUP BY complex_id, area_bucket, term_end_ym
                )
                SELECT ?, c.complex_id, c.area_bucket, c.term_end_ym,
                       c.contract_count, c.estimated_count,
                       CAST(m.median_deposit AS SIGNED),
                       s.median_deposit,
                       CAST(m.median_deposit AS SIGNED) - s.median_deposit,
                       (CAST(m.median_deposit AS SIGNED) - s.median_deposit) / m.median_deposit,
                       c.renewal_exhausted_count,
                       s.confidence
                FROM counts c
                JOIN median_then m ON m.complex_id = c.complex_id
                                  AND m.area_bucket = c.area_bucket
                                  AND m.term_end_ym = c.term_end_ym
                JOIN complex_rent_stat s ON s.snapshot_date = ?
                                        AND s.complex_id   = c.complex_id
                                        AND s.area_bucket  = c.area_bucket
                WHERE m.median_deposit > 0
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, fromYm, fromYm, snapshotDate, snapshotDate);
    }
}
