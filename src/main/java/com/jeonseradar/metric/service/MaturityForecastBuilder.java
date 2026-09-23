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
 *
 * F-31c: 계약 건수(contract_count·estimated_count·renewal_exhausted_count)는 원본
 * rent_deal 그대로 센다 — 대량 행정 갱신이라도 그 계약은 실재하고 실제로 만기가 돌아오며,
 * 이 숫자는 이 서비스가 보고하려는 바로 그 만기 물량(3단계에서 하한값으로 표기)이기
 * 때문이다. 대신 그 그룹의 then 쪽 표본 등급만 증거에 맞춰 강등한다 — 아래 bulk_renewal
 * 참고. 모집단을 깎는 것이 아니라 등급을 매기는 쪽이다.
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
                           PARTITION BY complex_id, area_bucket, term_end_ym)                  cnt,
                         COUNT(*) OVER (
                           PARTITION BY complex_id, area_bucket, term_end_ym, deposit)         dep_cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0
                    AND term_end_ym IS NOT NULL
                    AND term_end_ym >= ?
                ),
                -- F-31c: 한 보증금 값이 만기 그룹을 지배하면 대량 행정 갱신이다.
                -- 판정식은 RentStatBuilder.CLEAN_DEALS_CTE와 글자 그대로 같게 둔다
                -- (10건 이상 + 한 값이 90퍼센트 이상, 정수 비교). 다른 것은 그룹 키뿐이다 —
                -- 현재 시세는 (단지,평형)으로 묶고 여기는 만기월까지 묶는다. then 쪽
                -- 중위값을 만든 모집단이 그 그룹이기 때문이다.
                bulk_renewal AS (
                  SELECT DISTINCT complex_id, area_bucket, term_end_ym
                  FROM (
                    SELECT complex_id, area_bucket, term_end_ym, cnt AS grp_cnt, dep_cnt
                    FROM ranked
                  ) counted
                  WHERE grp_cnt >= 10 AND dep_cnt * 10 >= grp_cnt * 9
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
                       -- F-41: confidence는 현재 시세(s) 쪽만 보고 있었다. median_deposit_then은
                       -- 폴백이 없어 항상 "실제 체결가"이긴 하지만 (스펙 §3.4), 표본이 1건이면
                       -- 중위값이 아니라 그 한 건의 가격일 뿐이라 갭의 오차가 거기서 온다.
                       -- 스펙 §3.5의 "레벨과 표본 수 중 낮은 쪽을 채택한다"를 then 쪽에도 그대로
                       -- 적용해, 현재 쪽 confidence와 then 쪽 표본 등급(contract_count) 중
                       -- 더 낮은 쪽을 최종 confidence로 삼는다 — "표본 부족을 숨기지 않는다".
                       --
                       -- F-31c: 그 then 쪽 표본 등급이 "몇 건이냐"만 봤다. 대량 행정 갱신으로
                       -- 같은 보증금 17건이 한꺼번에 들어오면 contract_count는 17이지만 그것은
                       -- 독립된 관측 17개가 아니라 임대 의사결정 하나가 복제된 것이다. 게다가
                       -- 한 값이 90퍼센트 이상이면 중위값은 반드시 그 값이 되므로(가운데 순위가 그
                       -- 값의 구간 안에 들어온다), median_deposit_then은 시장가가 아니라
                       -- 행정가다 — 현재 시세는 CLEAN_DEALS_CTE로 정제된 시장가라 갭이 서로
                       -- 다른 모집단의 뺄셈이 된다. 계약은 실재하므로 지우지 않고(건수·금액은
                       -- 그대로) 등급만 증거에 맞춰 LOW로 내린다. 그러면 F-41의 "양쪽 중 낮은
                       -- 쪽"이 현재 쪽이 아무리 좋아도 행 전체를 LOW로 묶는다.
                       CASE
                         WHEN s.confidence = 'LOW'    OR c.contract_count < 3
                              OR b.complex_id IS NOT NULL                      THEN 'LOW'
                         WHEN s.confidence = 'MEDIUM' OR c.contract_count < 10 THEN 'MEDIUM'
                         ELSE 'HIGH'
                       END
                FROM counts c
                JOIN median_then m ON m.complex_id = c.complex_id
                                  AND m.area_bucket = c.area_bucket
                                  AND m.term_end_ym = c.term_end_ym
                LEFT JOIN bulk_renewal b ON b.complex_id  = c.complex_id
                                        AND b.area_bucket = c.area_bucket
                                        AND b.term_end_ym = c.term_end_ym
                JOIN complex_rent_stat s ON s.snapshot_date = ?
                                        AND s.complex_id   = c.complex_id
                                        AND s.area_bucket  = c.area_bucket
                WHERE m.median_deposit > 0
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, fromYm, fromYm, snapshotDate, snapshotDate);
    }
}
