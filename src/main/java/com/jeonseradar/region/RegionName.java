package com.jeonseradar.region;

/**
 * 카탈로그의 한 덩어리 지역명을 시도와 시군구로 나누고 URL 세그먼트를 만든다.
 *
 * 실측된 세 갈래를 모두 다룬다 (2026-09-22 sigungu.csv 269행 기준):
 *   1토큰  세종특별자치시            1건  — 시군구가 없다
 *   2토큰  서울특별시 송파구        229건
 *   3토큰  경기도 수원시 장안구      39건  — 특례시·대도시의 구
 *
 * 3토큰을 마지막 토큰만으로 줄이지 않는 이유는 시도 안에서의 유일성을 가정해야 하기
 * 때문이다. 하이픈 결합은 그 가정이 필요 없다 (원장 R-2).
 */
public record RegionName(String code, String sido, String sigungu) {

    private static final String SEGMENT_DELIMITER = "-";

    public static RegionName of(String code, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            // 카탈로그에 없는 코드다. 행정구역 개편으로 새 코드가 들어오면 실제로 생긴다.
            // NPE 대신 무엇이 없는지 말한다.
            throw new IllegalArgumentException("카탈로그에 없는 시군구 코드: " + code);
        }
        String[] tokens = fullName.trim().split("\\s+");
        if (tokens.length == 1) {
            return new RegionName(code, tokens[0], null);
        }
        String sido = tokens[0];
        String sigungu = String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
        return new RegionName(code, sido, sigungu);
    }

    public boolean hasSigungu() {
        return sigungu != null;
    }

    public String sidoSegment() {
        return sido;
    }

    /** 공백을 하이픈으로 바꾼 URL 세그먼트. 시군구가 없으면 null. */
    public String sigunguSegment() {
        return sigungu == null ? null : sigungu.replace(" ", SEGMENT_DELIMITER);
    }

    /** URL 세그먼트를 원래 이름으로 되돌린다. */
    public static String sigunguFromSegment(String segment) {
        return segment.replace(SEGMENT_DELIMITER, " ");
    }

    /** 페이지에 쓰는 이름. 시군구가 있으면 시군구, 없으면 시도. */
    public String displayName() {
        return hasSigungu() ? sigungu : sido;
    }

    public String title() {
        return displayName() + " 역전세 현황";
    }
}
