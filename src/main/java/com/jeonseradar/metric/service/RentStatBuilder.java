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
 *
 * F-31: 대형 임대사업자의 일괄 갱신(행정적으로 정해진 단일 보증금이 그룹을 지배)은
 * 시장 거래가 아니다. {@link #CLEAN_DEALS_CTE}로 정의한 오염 판정은 L1·L2·L3 세
 * 레벨 모두에서 같은 거래 집합을 제외한다 — L2가 단가를 환산할 때도, L3가 법정동
 * 시세를 빌려줄 때도 그 오염된 거래가 다시 섞여 들어오면 안 되기 때문이다.
 */
@Service
public class RentStatBuilder {

    private static final int WINDOW_MONTHS = 6;

    /**
     * F-31에서 제외 대상으로 정의한 거래를 뺀 "정제된" 순수 전세 거래 집합.
     *
     * 판정은 항상 원 거래의 (complex_id, area_bucket) 그룹 기준이다 — L2가 단지
     * 전체 평형을 훑거나 L3가 법정동 전체를 훑더라도, "이 거래가 오염됐는가"는 그
     * 거래가 원래 속한 단지·평형 그룹만 보고 판단한다(다른 단지의 같은 보증금과는
     * 무관하다). 그룹의 거래가 10건 이상이면서 그중 한 보증금 값이 90% 이상을
     * 차지하면 대량 갱신으로 보고 그 값의 거래를 제외한다. 90% 비교를
     * dep_cnt*10 >= grp_cnt*9 정수 비교로 하는 이유는 부동소수점 반올림 오차를
     * 피하기 위해서다.
     *
     * L1/L2/L3가 이 정의를 공유하도록 상수 하나로 뒀다. 다만 세 레벨은 각각
     * 독립된 INSERT ... SELECT 문(JdbcTemplate 호출 세 번)이라 진짜 SQL CTE 하나를
     * 세 문장이 물리적으로 공유할 수는 없다 — WITH 절은 그 문장 안에서만 산다.
     * 그래서 "한 곳에서 정의하고 세 번 텍스트로 끼워 넣는" 이 방식이 실용적인
     * 타협이다: 정의를 고치려면 이 상수 하나만 고치면 되고, 세 SQL은 항상 같은
     * 판정 로직을 쓴다는 게 보장된다. clean_deals가 내놓는 컬럼은 세 레벨이 각자
     * 쓰는 것의 합집합이다 — L1은 complex_id·area_bucket·deposit, L2는 추가로
     * area(㎡당 단가 환산용), L3는 추가로 region_code·legal_dong(법정동 집계용).
     */
    private static final String CLEAN_DEALS_CTE = """
            clean_deals AS (
              SELECT complex_id, area_bucket, region_code, legal_dong, deposit, area
              FROM (
                SELECT complex_id, area_bucket, region_code, legal_dong, deposit, area,
                       COUNT(*) OVER (PARTITION BY complex_id, area_bucket)          grp_cnt,
                       COUNT(*) OVER (PARTITION BY complex_id, area_bucket, deposit) dep_cnt
                FROM rent_deal
                WHERE monthly_rent = 0 AND deal_date >= ? AND deal_date <= ?
              ) counted
              WHERE NOT (grp_cnt >= 10 AND dep_cnt * 10 >= grp_cnt * 9)
            )""";

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
        int inserted = insertL1(snapshotDate, windowStart, asOf);
        inserted += insertL2(snapshotDate, windowStart, asOf);
        inserted += insertL3(snapshotDate, windowStart, asOf);
        return inserted;
    }

    /**
     * L1: 같은 단지·평형의 최근 6개월 보증금 중위값.
     *
     * {@link #CLEAN_DEALS_CTE}로 F-31 오염 거래를 제외한 뒤 중위값을 낸다(원본
     * rent_deal은 그대로 둔다). 제외 후 표본이 3건 미만으로 얇아지면 sample_count가
     * 그만큼 줄어 아래 CASE가 자동으로 LOW를 매긴다 — 별도 분기가 필요 없다.
     * 한 그룹이 통째로(90%+가 아니라 100%에 가깝게) 오염되어 있으면 제외 후 0건이
     * 남을 수 있고, 그러면 이 레벨은 그 (단지,평형)에 대해 아무 행도 만들지 않는다
     * — L2, 그래도 없으면 L3가 이어받는다(둘 다 같은 정의로 오염을 제외한다).
     */
    private int insertL1(LocalDate snapshotDate, LocalDate windowStart, LocalDate asOf) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH %s,
                ranked AS (
                  SELECT complex_id, area_bucket, deposit,
                         ROW_NUMBER() OVER (PARTITION BY complex_id, area_bucket ORDER BY deposit) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id, area_bucket)                  cnt
                  FROM clean_deals
                )
                SELECT ?, complex_id, area_bucket,
                       CAST(AVG(deposit) AS SIGNED), MAX(cnt), 'L1',
                       CASE WHEN MAX(cnt) >= 10 THEN 'HIGH'
                            WHEN MAX(cnt) >= 3  THEN 'MEDIUM'
                            ELSE 'LOW' END
                FROM ranked
                WHERE %s
                GROUP BY complex_id, area_bucket
                """.formatted(CLEAN_DEALS_CTE, MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, windowStart, asOf, snapshotDate);
    }

    /**
     * L2: 같은 단지 전체 평형의 ㎡당 단가 중위값 × 해당 평형.
     *
     * 같은 단지라도 평형이 다르면 절대 금액이 다르므로, 단가로 환산해야 빌려 쓸 수 있다.
     * L2는 상한이 MEDIUM이라 표본이 아무리 많아도 HIGH가 되지 않는다.
     *
     * F-31: 단가 환산도 {@link #CLEAN_DEALS_CTE}로 오염 거래를 뺀 뒤 계산한다. L1이
     * 통째로 오염돼 빈 채로 넘어온 (단지,평형)이라도, L2가 원본 rent_deal을 그대로
     * 다시 읽으면 같은 행정가를 MEDIUM 신뢰도만 붙여 재노출하게 된다 — 표본 수는
     * 그럴듯해 보이는데 값은 시장가가 아닌, 더 나쁜 결과다. 한 단지의 유일한 평형이
     * 통째로 오염돼 있으면(다른 평형이 없으면) 그 단지는 unit 자체가 안 나와 L2도
     * 채우지 못하고 L3로 넘어간다.
     */
    private int insertL2(LocalDate snapshotDate, LocalDate windowStart, LocalDate asOf) {
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
                %s,
                ranked AS (
                  SELECT complex_id, deposit / area AS unit_price,
                         ROW_NUMBER() OVER (PARTITION BY complex_id ORDER BY deposit / area) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id)                          cnt
                  FROM clean_deals
                  WHERE area > 0
                ),
                unit AS (
                  SELECT complex_id, AVG(unit_price) AS median_unit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s GROUP BY complex_id
                )
                SELECT ?, t.complex_id, t.area_bucket,
                       CAST(u.median_unit * t.area_bucket AS SIGNED), u.cnt, 'L2',
                       CASE WHEN u.cnt >= 3 THEN 'MEDIUM' ELSE 'LOW' END
                FROM target t JOIN unit u ON u.complex_id = t.complex_id
                """.formatted(CLEAN_DEALS_CTE, MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, asOf, snapshotDate);
    }

    /**
     * L3: 같은 법정동·평형. 남의 단지 시세를 빌려 쓰는 것이므로 상한이 LOW다.
     *
     * F-31: 법정동 집계도 {@link #CLEAN_DEALS_CTE}로 오염 거래를 뺀 뒤 만든다.
     * 대형 임대사업자 한 단지가 그 법정동·평형 거래량의 대부분을 차지하면, 그 단지
     * 자신뿐 아니라 같은 법정동의 다른 단지들이 빌려 쓰는 시세까지 행정가로
     * 끌려간다 — 오염 판정은 여전히 원 거래의 단지·평형 그룹 기준이고, 법정동
     * 단위로 다시 판정하지 않는다.
     */
    private int insertL3(LocalDate snapshotDate, LocalDate windowStart, LocalDate asOf) {
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
                %s,
                ranked AS (
                  SELECT region_code, legal_dong, area_bucket, deposit,
                         ROW_NUMBER() OVER (
                           PARTITION BY region_code, legal_dong, area_bucket ORDER BY deposit) rn,
                         COUNT(*) OVER (
                           PARTITION BY region_code, legal_dong, area_bucket)                  cnt
                  FROM clean_deals
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
                JOIN complex c ON c.id = t.complex_id
                JOIN dong d ON d.region_code = c.region_code
                           AND d.legal_dong  = c.legal_dong
                           AND d.area_bucket = t.area_bucket
                """.formatted(CLEAN_DEALS_CTE, MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, asOf, snapshotDate);
    }
}
