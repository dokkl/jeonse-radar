package com.jeonseradar.web.complex;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 단지 페이지가 렌더링하는 전부. 엔티티를 뷰까지 끌고 가지 않는다 (원장 R-7) —
 * 템플릿이 볼 수 있는 것을 조회 시점에 고정하고, 지연 로딩이 뷰에서 터질 여지를 없앤다.
 */
public record ComplexPage(
        long id,
        String slug,
        String displayName,
        String legalDong,
        String regionName,
        Integer buildYear,
        LocalDate snapshotDate,
        List<AreaStat> areaStats,
        List<MaturityRow> maturities,
        List<ReductionRow> reductions,
        boolean indexable) {

    public record AreaStat(
            int areaBucket, long medianDeposit, int sampleCount,
            String sourceLevel, String confidence) {}

    public record MaturityRow(
            String maturityYm, int areaBucket, int contractCount, int estimatedCount,
            long medianDepositThen, long currentMedianDeposit,
            long gapAmount, double gapRate, int renewalExhaustedCount, String confidence) {}

    /**
     * 갱신 계약에서 실제로 반환된 보증금 감액 (단지×평형). {@code complex_rent_stat}·
     * {@code maturity_forecast}와 달리 추정이 아니라 이미 일어난 사실이라 confidence가
     * 없다 — {@link com.jeonseradar.metric.entity.RenewalReductionStat}의 문서를 보라.
     *
     * <p>{@code reductionCount}가 3 미만이면 medianReduction은 "중위값"이 아니라 그
     * 1~2건의 실제 반환액(또는 그 평균)이다 — 통계적 수식어를 붙일 만큼 표본이 안 된다.
     * 어느 쪽이든 렌더링은 같은 필드를 쓰고, 템플릿이 문구만 바꾼다.
     */
    public record ReductionRow(
            int areaBucket, int reductionCount, long medianReduction,
            LocalDate latestReductionDate) {}

    /**
     * `/단지/{id}-{slug}`.
     * <p>퍼센트 인코딩하는 이유(레저 P3-3): HTTP 헤더는 ISO-8859-1이라 한글을 그대로
     * {@code Location} 헤더에 실으면 Tomcat이 "invalid header"로 판단해 헤더째로
     * 제거해 버려 301만 남고 목적지가 사라진다. 호출부(리다이렉트)에서 매번 인코딩하는
     * 대신 값을 만드는 이곳에서 한 번에 인코딩해 둔다 — 이 값은 canonical 링크와
     * (추후) sitemap.xml에도 그대로 쓰이는데, 세 군데 모두 퍼센트 인코딩된 경로가 맞기
     * 때문이다. "단순화"한답시고 인코딩을 걷어내면 이 버그가 그대로 재발한다.
     */
    public String path() {
        String path = "/단지/" + id + "-" + slug;
        return UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }

    /** 스펙 §4.2: 단지명 단독으로는 대형 서비스를 이길 수 없으므로 수식어를 결합한다. */
    public String title() {
        return displayName + " 전세 만기·역전세 현황 (" + snapshotDate.getYear() + ")";
    }
}
