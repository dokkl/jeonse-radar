package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 단지×평형 현재 전세 시세를 집계한다 (스펙 §3.5).
 *
 * 세 계층을 차례로 채우되, 앞 계층이 채운 (단지,평형)은 뒤 계층이 건드리지 않는다.
 * 행 단위로 자바에서 돌면 수백만 건에서 감당이 안 되므로 INSERT ... SELECT로 처리한다.
 *
 * 대상 집합은 "순수 전세 계약이 한 번이라도 있었던 (단지, 평형)" 전체다.
 * 만기 예측을 할 대상이 곧 시세가 필요한 대상이기 때문이다.
 */
@Service
public class RentStatBuilder {

    private static final int WINDOW_MONTHS = 6;

    private final JdbcTemplate jdbcTemplate;

    public RentStatBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param snapshotDate 집계 결과에 찍을 스냅샷 날짜
     * @param asOf         "최근 6개월"의 기준점. 테스트가 시간을 고정할 수 있도록 인자로 받는다.
     * @return 삽입된 행 수
     */
    @Transactional
    public int build(LocalDate snapshotDate, LocalDate asOf) {
        LocalDate windowStart = asOf.minusMonths(WINDOW_MONTHS);
        int inserted = insertL1(snapshotDate, windowStart);
        inserted += insertL2(snapshotDate, windowStart);
        inserted += insertL3(snapshotDate, windowStart);
        return inserted;
    }

    /** L1: 같은 단지·평형의 최근 6개월 보증금 중위값. */
    private int insertL1(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH ranked AS (
                  SELECT complex_id, area_bucket, deposit,
                         ROW_NUMBER() OVER (PARTITION BY complex_id, area_bucket ORDER BY deposit) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id, area_bucket)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ?
                )
                SELECT ?, complex_id, area_bucket,
                       CAST(AVG(deposit) AS SIGNED), MAX(cnt), 'L1',
                       CASE WHEN MAX(cnt) >= 10 THEN 'HIGH'
                            WHEN MAX(cnt) >= 3  THEN 'MEDIUM'
                            ELSE 'LOW' END
                FROM ranked
                WHERE %s
                GROUP BY complex_id, area_bucket
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, windowStart, snapshotDate);
    }

    /**
     * L2: 같은 단지 전체 평형의 ㎡당 단가 중위값 × 해당 평형.
     *
     * 같은 단지라도 평형이 다르면 절대 금액이 다르므로, 단가로 환산해야 빌려 쓸 수 있다.
     * L2는 상한이 MEDIUM이라 표본이 아무리 많아도 HIGH가 되지 않는다.
     */
    private int insertL2(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH target AS (
                  SELECT DISTINCT r.complex_id, r.area_bucket
                  FROM rent_deal r
                  WHERE r.monthly_rent = 0
                    AND NOT EXISTS (
                      SELECT 1 FROM complex_rent_stat s
                      WHERE s.snapshot_date = ? AND s.complex_id = r.complex_id
                        AND s.area_bucket = r.area_bucket)
                ),
                ranked AS (
                  SELECT complex_id, deposit / area AS unit_price,
                         ROW_NUMBER() OVER (PARTITION BY complex_id ORDER BY deposit / area) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id)                          cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ? AND area > 0
                ),
                unit AS (
                  SELECT complex_id, AVG(unit_price) AS median_unit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s GROUP BY complex_id
                )
                SELECT ?, t.complex_id, t.area_bucket,
                       CAST(u.median_unit * t.area_bucket AS SIGNED), u.cnt, 'L2',
                       CASE WHEN u.cnt >= 3 THEN 'MEDIUM' ELSE 'LOW' END
                FROM target t JOIN unit u ON u.complex_id = t.complex_id
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, snapshotDate);
    }

    /** L3: 같은 법정동·평형. 남의 단지 시세를 빌려 쓰는 것이므로 상한이 LOW다. */
    private int insertL3(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH target AS (
                  SELECT DISTINCT r.complex_id, r.area_bucket, r.region_code, r.legal_dong
                  FROM rent_deal r
                  WHERE r.monthly_rent = 0
                    AND NOT EXISTS (
                      SELECT 1 FROM complex_rent_stat s
                      WHERE s.snapshot_date = ? AND s.complex_id = r.complex_id
                        AND s.area_bucket = r.area_bucket)
                ),
                ranked AS (
                  SELECT region_code, legal_dong, area_bucket, deposit,
                         ROW_NUMBER() OVER (
                           PARTITION BY region_code, legal_dong, area_bucket ORDER BY deposit) rn,
                         COUNT(*) OVER (
                           PARTITION BY region_code, legal_dong, area_bucket)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ?
                ),
                dong AS (
                  SELECT region_code, legal_dong, area_bucket,
                         AVG(deposit) AS median_deposit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s
                  GROUP BY region_code, legal_dong, area_bucket
                )
                SELECT ?, t.complex_id, t.area_bucket,
                       CAST(d.median_deposit AS SIGNED), d.cnt, 'L3', 'LOW'
                FROM target t
                JOIN dong d ON d.region_code = t.region_code
                           AND d.legal_dong  = t.legal_dong
                           AND d.area_bucket = t.area_bucket
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, snapshotDate);
    }
}
