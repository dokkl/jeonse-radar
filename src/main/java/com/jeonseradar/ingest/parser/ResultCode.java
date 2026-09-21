package com.jeonseradar.ingest.parser;

public enum ResultCode {
    OK,
    NODATA,
    QUOTA_EXCEEDED,
    INVALID_KEY,
    ERROR;

    /**
     * 공공데이터 API는 같은 의미의 코드를 "00" / "000" / "0"처럼 다른 자리수로 반환한다.
     * 앞자리 0을 제거해 정규화한 뒤 판정한다.
     */
    public static ResultCode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ERROR;
        }
        String normalized = raw.trim().replaceFirst("^0+(?=\\d)", "");
        return switch (normalized) {
            case "0" -> OK;
            case "3" -> NODATA;
            case "22" -> QUOTA_EXCEEDED;
            case "30", "31", "32" -> INVALID_KEY;
            default -> ERROR;
        };
    }
}
