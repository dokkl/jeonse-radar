package com.jeonseradar.deal.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전월세 API의 계약기간 필드를 만기월로 변환한다.
 *
 * 실측(2026-09-21, 18,287건) 형식 분포:
 *   YY.MM~YY.MM   73.6%  (예: "25.05~27.05")
 *   공백           26.0%  (공백 한 칸으로 온다)
 *   ~YY.MM          0.4%  (시작월 없이 종료월만 — 종료월은 유효하다)
 *   YYYYMM~YYYYMM   0.0%  (존재하지 않지만 방어적으로 읽는다)
 *
 * 결측·형식오류 시 주택임대차보호법 기본 2년으로 추정하되 반드시 estimated=true를 남긴다.
 * 추정치를 확정치처럼 노출하면 한 번 틀렸을 때 서비스 신뢰가 사라진다 (스펙 §3.2).
 */
public final class ContractTermParser {

    /** "25.05~27.05" — 시작월은 선택적이다 ("~26.03" 형태가 실측 0.4% 존재) */
    private static final Pattern SHORT_TERM =
            Pattern.compile("^\\s*(?:(\\d{2})\\.(\\d{2}))?\\s*~\\s*(\\d{2})\\.(\\d{2})\\s*$");

    /** "202505~202705" — 실측 0%지만 방어적으로 지원 */
    private static final Pattern LONG_TERM =
            Pattern.compile("^\\s*(\\d{6})\\s*~\\s*(\\d{6})\\s*$");

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int DEFAULT_MONTHS = 24;
    private static final int CENTURY = 2000;

    public record ContractTerm(String startYm, String endYm, boolean estimated) {
    }

    private ContractTermParser() {
    }

    public static ContractTerm parse(String raw, LocalDate dealDate) {
        if (raw == null || raw.isBlank()) {
            return estimate(dealDate);
        }

        Matcher shortMatch = SHORT_TERM.matcher(raw);
        if (shortMatch.matches()) {
            YearMonth start = shortMatch.group(1) == null
                    ? null
                    : toYearMonth(CENTURY + Integer.parseInt(shortMatch.group(1)),
                                  Integer.parseInt(shortMatch.group(2)));
            YearMonth end = toYearMonth(CENTURY + Integer.parseInt(shortMatch.group(3)),
                                        Integer.parseInt(shortMatch.group(4)));
            return build(start, end, shortMatch.group(1) != null, dealDate);
        }

        Matcher longMatch = LONG_TERM.matcher(raw);
        if (longMatch.matches()) {
            YearMonth start = toYearMonth(longMatch.group(1));
            YearMonth end = toYearMonth(longMatch.group(2));
            return build(start, end, true, dealDate);
        }

        return estimate(dealDate);
    }

    /**
     * 종료월을 얻었으면 확정으로 본다. 시작월은 없어도 무방하다 —
     * 만기 예측에 필요한 것은 종료월뿐이다.
     */
    private static ContractTerm build(YearMonth start, YearMonth end,
                                      boolean startRequired, LocalDate dealDate) {
        if (end == null || (startRequired && start == null)) {
            return estimate(dealDate);
        }
        if (start != null && end.isBefore(start)) {
            return estimate(dealDate);
        }
        return new ContractTerm(start == null ? null : start.format(YM), end.format(YM), false);
    }

    private static ContractTerm estimate(LocalDate dealDate) {
        YearMonth start = YearMonth.from(dealDate);
        YearMonth end = start.plusMonths(DEFAULT_MONTHS);
        return new ContractTerm(start.format(YM), end.format(YM), true);
    }

    private static YearMonth toYearMonth(int year, int month) {
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static YearMonth toYearMonth(String ym) {
        try {
            return YearMonth.parse(ym, YM);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
