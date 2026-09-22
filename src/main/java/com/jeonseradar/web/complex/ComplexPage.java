package com.jeonseradar.web.complex;

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
        boolean indexable) {

    public record AreaStat(
            int areaBucket, long medianDeposit, int sampleCount,
            String sourceLevel, String confidence) {}

    public record MaturityRow(
            String maturityYm, int areaBucket, int contractCount, int estimatedCount,
            long medianDepositThen, long currentMedianDeposit,
            long gapAmount, double gapRate, int renewalExhaustedCount, String confidence) {}

    /** `/단지/{id}-{slug}` */
    public String path() {
        return "/단지/" + id + "-" + slug;
    }

    /** 스펙 §4.2: 단지명 단독으로는 대형 서비스를 이길 수 없으므로 수식어를 결합한다. */
    public String title() {
        return displayName + " 전세 만기·역전세 현황 (" + snapshotDate.getYear() + ")";
    }
}
