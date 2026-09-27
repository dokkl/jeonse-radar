package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 갱신 계약에서 실제로 반환된 보증금 감액을 단지×평형 단위로 집계한다.
 *
 * complex_rent_stat의 median_deposit은 폴백 사다리를 거친 추정값이고, 그래서 confidence가
 * 붙는다. 여기서 쓰는 rent_deal.prev_deposit은 다르다 — 정부 API가 갱신 계약에 함께
 * 보고하는 "그 이전 보증금"이고, deposit &lt; prev_deposit이면 임대인이 차액을 실제로
 * 돌려준 것이다. 이미 일어난 사실이므로 폴백도 confidence 상한도 필요 없다.
 *
 * <p><b>F-31의 CLEAN_DEALS_CTE(대량 행정 갱신 제외)를 여기에는 적용하지 않는다.</b> 그
 * 필터가 존재하는 이유는 "일괄 갱신으로 정해진 단일 보증금은 시장 거래가 아니다"였다 —
 * RentStatBuilder가 계산하는 median_deposit은 시세, 즉 "이 시장에서 지금 얼마에
 * 거래되는가"를 뜻하므로 행정가가 섞이면 그 답이 왜곡된다. 하지만 이 빌더는 시세를
 * 계산하지 않는다. "이 갱신 계약에서 임대인이 실제로 돈을 돌려줬는가"라는 사실을 셀
 * 뿐이고, 그 임대인이 대형 임대사업자이고 보증금이 행정적으로 일괄 책정됐다는 사실이
 * "그래서 돈을 돌려준 적 없다"로 바뀌지는 않는다 — 오히려 그런 사업자일수록 다수
 * 세대를 같은 조건으로 갱신하며 실제로 돈을 돌려주는 사례이기 쉽다.
 *
 * <p><b>날짜 창을 두지 않는다.</b> RentStatBuilder는 "지금 시세"를 내야 하므로 최근
 * 6개월로 좁히지만, 이 집계는 "지금까지 실제로 무슨 일이 있었는가"의 전체 기록이 목적이다.
 * latest_reduction_date 컬럼이 최신성을 대신 전달하므로 별도 window가 필요 없다.
 */
@Service
public class RenewalReductionBuilder {

    private final JdbcTemplate jdbcTemplate;

    public RenewalReductionBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param snapshotDate 집계 결과에 찍을 스냅샷 날짜
     * @return 삽입된 행 수
     */
    @Transactional
    public int build(LocalDate snapshotDate) {
        String sql = """
                INSERT INTO renewal_reduction_stat
                  (snapshot_date, complex_id, area_bucket, renewal_count, reduction_count,
                   median_reduction, latest_reduction_date)
                WITH renewals AS (
                  SELECT complex_id, area_bucket, deposit, prev_deposit, deal_date
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND prev_deposit > 0
                ),
                reductions AS (
                  SELECT complex_id, area_bucket, (prev_deposit - deposit) AS reduction, deal_date
                  FROM renewals
                  WHERE deposit < prev_deposit
                ),
                ranked AS (
                  SELECT complex_id, area_bucket, reduction,
                         ROW_NUMBER() OVER (
                           PARTITION BY complex_id, area_bucket ORDER BY reduction) rn,
                         COUNT(*)     OVER (
                           PARTITION BY complex_id, area_bucket)                    cnt
                  FROM reductions
                ),
                reduction_agg AS (
                  SELECT complex_id, area_bucket,
                         CAST(AVG(reduction) AS SIGNED) AS median_reduction,
                         MAX(cnt) AS reduction_count
                  FROM ranked
                  WHERE %s
                  GROUP BY complex_id, area_bucket
                ),
                renewal_agg AS (
                  SELECT complex_id, area_bucket, COUNT(*) AS renewal_count
                  FROM renewals
                  GROUP BY complex_id, area_bucket
                ),
                latest_agg AS (
                  SELECT complex_id, area_bucket, MAX(deal_date) AS latest_reduction_date
                  FROM reductions
                  GROUP BY complex_id, area_bucket
                )
                SELECT ?, ra.complex_id, ra.area_bucket, re.renewal_count, ra.reduction_count,
                       ra.median_reduction, la.latest_reduction_date
                FROM reduction_agg ra
                JOIN renewal_agg re ON re.complex_id = ra.complex_id AND re.area_bucket = ra.area_bucket
                JOIN latest_agg la  ON la.complex_id = ra.complex_id AND la.area_bucket = ra.area_bucket
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate);
    }
}
