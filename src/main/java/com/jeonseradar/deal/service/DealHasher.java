package com.jeonseradar.deal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 국토교통부 API는 거래 고유 ID를 제공하지 않으므로 자연키 해시로 거래를 식별한다.
 *
 * 해시에는 절대 변하지 않는 필드만 넣는다.
 * 등기일자(rgstDate)와 해제여부(cdealType)는 나중에 값이 바뀌므로 포함하면 안 된다.
 * 포함하면 값이 바뀔 때마다 새 행이 생겨 같은 거래가 중복된다.
 */
public final class DealHasher {

    /**
     * 필드 구분자로 ASCII Unit Separator(0x1F)를 쓴다.
     * "|"는 지번·동명 등 실제 데이터에 등장할 수 있어 구분자 주입으로 서로 다른 두 거래가
     * 같은 해시를 낼 수 있었다(예: jibun="12|34" vs jibun="12", legalDong="34|...").
     * 0x1F는 제어문자라 국토부 API가 실어 나르는 텍스트 필드(동명, 지번 등)에 나타나지 않으므로
     * 이 위험이 구조적으로 사라진다.
     */
    private static final String SEP = "";

    private DealHasher() {
    }

    public static String hashTrade(String regionCode, String legalDong, String jibun,
                                   BigDecimal area, int floor, LocalDate dealDate,
                                   long dealAmount) {
        return sha256(String.join(SEP,
                "TRADE", nz(regionCode), nz(legalDong), nz(jibun),
                normalizeArea(area), String.valueOf(floor),
                dealDate.toString(), String.valueOf(dealAmount)));
    }

    public static String hashRent(String regionCode, String legalDong, String jibun,
                                  BigDecimal area, int floor, LocalDate dealDate,
                                  long deposit, long monthlyRent) {
        return sha256(String.join(SEP,
                "RENT", nz(regionCode), nz(legalDong), nz(jibun),
                normalizeArea(area), String.valueOf(floor),
                dealDate.toString(), String.valueOf(deposit), String.valueOf(monthlyRent)));
    }

    /** 84.97과 84.970이 같은 해시를 내도록 소수점 둘째자리로 고정한다. */
    private static String normalizeArea(BigDecimal area) {
        return area.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
