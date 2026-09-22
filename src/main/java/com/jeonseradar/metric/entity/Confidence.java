package com.jeonseradar.metric.entity;

/**
 * 시세 산출의 신뢰 수준 (스펙 §3.5).
 *
 * 점수가 아니라 세 단계다. 임의 가중치로 "신뢰도 62점" 같은 수치를 만들지 않는다 (스펙 §3.7).
 * 3단계에서 LOW는 noindex 게이트로도 쓰인다 (스펙 §4.1).
 */
public enum Confidence {

    LOW(0),
    MEDIUM(1),
    HIGH(2);

    private final int rank;

    Confidence(int rank) {
        this.rank = rank;
    }

    /**
     * 표본 수 기준과 폴백 계층 상한 중 **낮은 쪽**을 채택한다.
     *
     * 표본이 아무리 많아도 L3로 내려갔다면 그것은 다른 동네 시세를 빌려 쓴 것이므로
     * HIGH일 수 없고, 반대로 L1이라도 표본이 2건이면 중위값이 사실상 노이즈다.
     */
    public static Confidence of(int sampleCount, SourceLevel level) {
        Confidence bySample = fromSampleCount(sampleCount);
        Confidence cap = level.cap();
        return bySample.rank <= cap.rank ? bySample : cap;
    }

    private static Confidence fromSampleCount(int sampleCount) {
        if (sampleCount >= 10) {
            return HIGH;
        }
        if (sampleCount >= 3) {
            return MEDIUM;
        }
        return LOW;
    }
}
