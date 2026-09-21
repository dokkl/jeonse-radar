package com.jeonseradar.ingest.parser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class FieldConverter {

    private static final long MAN_WON = 10_000L;
    private static final DateTimeFormatter RGST_FORMAT = DateTimeFormatter.ofPattern("yy.MM.dd");

    private FieldConverter() {
    }

    /** API 금액은 만원 단위 + 콤마 + 좌측 공백으로 온다. 원 단위 long으로 변환한다. */
    public static long toWon(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String digits = raw.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) {
            return 0L;
        }
        return Long.parseLong(digits) * MAN_WON;
    }

    public static BigDecimal toArea(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
    }

    public static int toAreaBucket(BigDecimal area) {
        return area.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 단지 식별 키의 일부. jibun 필드가 있으면 그대로, 없으면 본번-부번을 조합한다.
     * 본번/부번은 4자리 zero-padding 되어 오므로 앞자리 0을 제거한다.
     */
    public static String toJibun(Map<String, String> item) {
        String jibun = item.get(MolitFields.JIBUN);
        if (jibun != null && !jibun.isBlank()) {
            return jibun.trim();
        }
        String bonbun = stripLeadingZeros(item.get(MolitFields.BONBUN));
        String bubun = stripLeadingZeros(item.get(MolitFields.BUBUN));
        if (bonbun.isEmpty()) {
            return "";
        }
        return bubun.isEmpty() || bubun.equals("0") ? bonbun : bonbun + "-" + bubun;
    }

    public static LocalDate toDealDate(Map<String, String> item) {
        int year = parseIntOrZero(item.get(MolitFields.DEAL_YEAR));
        int month = parseIntOrZero(item.get(MolitFields.DEAL_MONTH));
        int day = parseIntOrZero(item.get(MolitFields.DEAL_DAY));
        return LocalDate.of(year, month, day);
    }

    /** 등기일자는 미등기 시 공백으로 온다. 공백은 null(미등기)이며 0건 오류가 아니다. */
    public static LocalDate toRegisteredDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), RGST_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripLeadingZeros(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String stripped = raw.trim().replaceFirst("^0+(?=\\d)", "");
        return stripped.equals("0") ? "0" : stripped;
    }

    private static int parseIntOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Integer.parseInt(raw.trim());
    }
}
