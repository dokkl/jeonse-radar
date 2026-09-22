package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/** 단지×평형 현재 전세 시세 (스펙 §2, §3.5). 순수 전세(monthly_rent = 0)만 대상이다. */
@Entity
@Table(name = "complex_rent_stat",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rent_stat_key",
                columnNames = {"snapshot_date", "complex_id", "area_bucket"}),
        indexes = @Index(name = "idx_rent_stat_lookup",
                columnList = "snapshot_date,complex_id"))
public class ComplexRentStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private Long complexId;

    @Column(nullable = false)
    private Integer areaBucket;

    /** 최근 6개월 순수 전세 보증금 중위값, 원 단위 */
    private Long medianDeposit;

    private Integer sampleCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private SourceLevel sourceLevel;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Confidence confidence;

    protected ComplexRentStat() {
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public Long getComplexId() { return complexId; }
    public Integer getAreaBucket() { return areaBucket; }
    public Long getMedianDeposit() { return medianDeposit; }
    public Integer getSampleCount() { return sampleCount; }
    public SourceLevel getSourceLevel() { return sourceLevel; }
    public Confidence getConfidence() { return confidence; }
}
