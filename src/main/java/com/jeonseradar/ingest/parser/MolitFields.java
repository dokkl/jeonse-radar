package com.jeonseradar.ingest.parser;

/**
 * 국토교통부 실거래가 API 응답 필드명.
 * API 개편으로 바뀔 수 있으므로 필드명 문자열은 이 클래스에만 존재한다.
 * 실제 응답과 다르면 여기만 고친다.
 */
public final class MolitFields {

    private MolitFields() {
    }

    // 공통
    /** 단지 일련번호 (예: 11110-2519). 채움률 100%. 단지 식별의 기준 키다. */
    public static final String APT_SEQ = "aptSeq";
    public static final String SGG_CD = "sggCd";
    public static final String UMD_NM = "umdNm";
    public static final String JIBUN = "jibun";
    public static final String BONBUN = "bonbun";
    public static final String BUBUN = "bubun";
    public static final String APT_NM = "aptNm";
    public static final String EXCLU_USE_AR = "excluUseAr";
    public static final String FLOOR = "floor";
    public static final String BUILD_YEAR = "buildYear";
    public static final String DEAL_YEAR = "dealYear";
    public static final String DEAL_MONTH = "dealMonth";
    public static final String DEAL_DAY = "dealDay";

    // 매매
    public static final String DEAL_AMOUNT = "dealAmount";
    public static final String DEALING_GBN = "dealingGbn";
    public static final String ESTATE_AGENT_SGG_NM = "estateAgentSggNm";
    public static final String RGST_DATE = "rgstDate";
    public static final String CDEAL_TYPE = "cdealType";
    public static final String CDEAL_DAY = "cdealDay";

    // 전월세
    public static final String DEPOSIT = "deposit";
    public static final String MONTHLY_RENT = "monthlyRent";
    public static final String CONTRACT_TERM = "contractTerm";
    public static final String CONTRACT_TYPE = "contractType";
    public static final String USE_RR_RIGHT = "useRRRight";
    public static final String PRE_DEPOSIT = "preDeposit";
    public static final String PRE_MONTHLY_RENT = "preMonthlyRent";
}
