package com.jeonseradar.web.region;

import java.time.LocalDate;
import java.util.List;

public record RegionPage(
        String regionCode,
        String sido,
        String regionName,
        String sidoSegment,
        String sigunguSegment,
        LocalDate snapshotDate,
        int complexCount,
        long totalMaturityContracts,
        long negativeGapContracts,
        List<RankRow> ranking) {

    public record RankRow(
            long complexId, String slug, String displayName, String legalDong,
            int areaBucket, String maturityYm, int contractCount,
            long gapAmount, double gapRate, int renewalExhaustedCount, String confidence) {

        /** 인코딩 이유는 RegionPage.path() 주석 참고 (원장 P3-3). */
        public String path() {
            return org.springframework.web.util.UriUtils.encodePath(
                    "/단지/" + complexId + "-" + slug, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 퍼센트 인코딩된 경로. HTTP 헤더는 ISO-8859-1이라 한글을 날것으로 넣으면
     * 톰캣이 Location 헤더를 통째로 버린다 (원장 P3-3, 실앱에서 실제로 발생).
     * sitemap과 canonical 링크도 같은 값을 쓰므로 여기서 한 번만 인코딩한다.
     * URLEncoder는 공백을 +로, /를 %2F로 바꾸므로 경로에 쓰면 안 된다.
     */
    public String path() {
        String raw = sigunguSegment == null
                ? "/역전세/" + sidoSegment
                : "/역전세/" + sidoSegment + "/" + sigunguSegment;
        return org.springframework.web.util.UriUtils.encodePath(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    public String title() {
        return regionName + " 전세 만기·역전세 현황 (" + snapshotDate.getYear() + ")";
    }

    /** 랭킹에 올릴 게 하나도 없는 지역은 색인하지 않는다. */
    public boolean indexable() {
        return !ranking.isEmpty();
    }
}
