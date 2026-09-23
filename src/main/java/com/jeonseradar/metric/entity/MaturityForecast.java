package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 단지×평형×만기월 예측 (스펙 §2, §3.2/§3.4/§3.6).
 *
 * 동/호가 공개되지 않아 개별 물건을 추적할 수 없으므로 모든 값은 집계 단위다 (스펙 §3.1).
 */
@Entity
@Table(name = "maturity_forecast",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_maturity_key",
                columnNames = {"snapshot_date", "complex_id", "area_bucket", "maturity_ym"}),
        indexes = {
                @Index(name = "idx_maturity_lookup", columnList = "snapshot_date,complex_id"),
                @Index(name = "idx_maturity_month", columnList = "snapshot_date,maturity_ym")
        })
public class MaturityForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private Long complexId;

    @Column(nullable = false)
    private Integer areaBucket;

    /** yyyyMM */
    @Column(length = 6, nullable = false)
    private String maturityYm;

    /**
     * 계약 건수 — <b>하한값</b>이다. 국토교통부 자료는 동/호를 제공하지 않아, 같은 단지·
     * 같은 평형·같은 층·같은 가격·같은 날 거래는 {@code deal_hash}에서 한 행으로 합쳐진다
     * (스펙 §1.3). 전국 기준 약 8.60%가 이렇게 합쳐지므로, 실제 계약 수는 이 값 이상이다.
     * 재수집으로 해결되지 않는 원천 데이터의 한계이니, 화면에 확정치처럼 표기하지 말 것.
     */
    private Integer contractCount;

    /** 그중 계약기간이 결측이라 계약일+24개월로 추정한 건수 (스펙 §3.2) */
    private Integer estimatedCount;

    /** 만기가 돌아오는 계약들의 당시 보증금 중위값, 원 단위 */
    private Long medianDepositThen;

    /** 같은 단지·평형의 현재 전세 시세, 원 단위 */
    private Long currentMedianDeposit;

    /** medianDepositThen - currentMedianDeposit. 양수면 역전세 */
    private Long gapAmount;

    /** gapAmount / medianDepositThen */
    private Double gapRate;

    /**
     * 갱신요구권을 이미 쓴 건수 — 다음 만기에 갱신권이 없다 (스펙 §3.6).
     * {@code contractCount}와 같은 이유(§1.3)로 <b>하한값</b>이다 — 유료 리드 상품이
     * 근거로 삼는 숫자이니 확정치처럼 표기하지 말 것.
     */
    private Integer renewalExhaustedCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Confidence confidence;

    protected MaturityForecast() {
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public Long getComplexId() { return complexId; }
    public Integer getAreaBucket() { return areaBucket; }
    public String getMaturityYm() { return maturityYm; }
    public Integer getContractCount() { return contractCount; }
    public Integer getEstimatedCount() { return estimatedCount; }
    public Long getMedianDepositThen() { return medianDepositThen; }
    public Long getCurrentMedianDeposit() { return currentMedianDeposit; }
    public Long getGapAmount() { return gapAmount; }
    public Double getGapRate() { return gapRate; }
    public Integer getRenewalExhaustedCount() { return renewalExhaustedCount; }
    public Confidence getConfidence() { return confidence; }
}
