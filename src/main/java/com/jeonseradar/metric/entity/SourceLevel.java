package com.jeonseradar.metric.entity;

/**
 * 시세 산출에 쓴 폴백 계층 (스펙 §3.5).
 *
 * 단지+평형에 표본이 없으면 단지 전체로, 그래도 없으면 법정동+평형으로 내려간다.
 * 내려갈수록 추정의 성격이 강해지므로 confidence 상한이 함께 내려간다.
 */
public enum SourceLevel {

    /** 단지 + 평형, 최근 6개월 */
    L1(Confidence.HIGH),

    /** 단지 전체 평형, ㎡당 단가 환산 */
    L2(Confidence.MEDIUM),

    /** 법정동 + 평형 */
    L3(Confidence.LOW);

    private final Confidence cap;

    SourceLevel(Confidence cap) {
        this.cap = cap;
    }

    public Confidence cap() {
        return cap;
    }
}
