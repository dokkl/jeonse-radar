package com.jeonseradar.metric.service;

/**
 * MySQL 8에는 MEDIAN()도 PERCENTILE_CONT()도 없다. 윈도 함수로 직접 만든다.
 *
 * ROW_NUMBER로 순위를 매기고 COUNT로 전체 개수를 구한 뒤, 가운데 순위만 남긴다.
 * 홀수면 FLOOR와 CEIL이 같은 값을 가리켜 한 행, 짝수면 두 행이 남으므로
 * AVG로 평균을 내면 두 경우가 한 식으로 처리된다.
 *
 * 평균이 아니라 중위값을 쓰는 이유: 전세 보증금은 소수의 대형 평형이나 이상 거래에
 * 크게 끌려가므로, 평균은 "이 단지에서 실제로 계약되는 금액"을 대표하지 못한다.
 */
public final class MedianSql {

    private MedianSql() {
    }

    /** 가운데 순위만 남기는 조건. ranked CTE 안에 rn, cnt 컬럼이 있다고 가정한다. */
    public static final String MIDDLE_ROWS = "rn IN (FLOOR((cnt + 1) / 2), CEIL((cnt + 1) / 2))";
}
