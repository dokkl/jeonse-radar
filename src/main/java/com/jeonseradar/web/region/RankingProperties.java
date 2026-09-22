package com.jeonseradar.web.region;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지역 페이지 랭킹에 올릴 최소 기준 (원장 R-5, F-40).
 *
 * 2026-09-22 실측: 갭 상위 50위 중 44개가 계약 1건짜리였고, 1건 그룹의 최대 갭은 35억,
 * 5건 이상 그룹은 9억이었다. 극단값이 시장이 아니라 표본 빈약의 산물이므로 문턱이 필요하다.
 * 최종 값은 전국 데이터로 2단계 Task 7에서 정하므로 설정으로 뺀다.
 */
@ConfigurationProperties(prefix = "jeonse.ranking")
public record RankingProperties(int minContractCount, String minConfidence, int limit) {

    public RankingProperties {
        if (minContractCount < 1) {
            throw new IllegalArgumentException("minContractCount는 1 이상이어야 한다: " + minContractCount);
        }
        if (!java.util.List.of("HIGH", "MEDIUM", "LOW").contains(minConfidence)) {
            throw new IllegalArgumentException("minConfidence는 HIGH·MEDIUM·LOW 중 하나여야 한다: " + minConfidence);
        }
    }

    /** SQL IN 절에 넣을, 문턱 이상인 신뢰도 목록. */
    public java.util.List<String> acceptedConfidences() {
        return switch (minConfidence) {
            case "HIGH" -> java.util.List.of("HIGH");
            case "MEDIUM" -> java.util.List.of("HIGH", "MEDIUM");
            default -> java.util.List.of("HIGH", "MEDIUM", "LOW");
        };
    }
}
