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

    /** 갱신요구권을 이미 쓴 건수 — 다음 만기에 갱신권이 없다 (스펙 §3.6) */
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
