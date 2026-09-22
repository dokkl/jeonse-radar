package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 매매 거래별 검증 플래그 (스펙 §3.7). 사실만 담으며 판단을 담지 않는다. */
@Entity
@Table(name = "deal_flag",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_deal_flag_key",
                columnNames = {"snapshot_date", "deal_hash", "flag_type"}),
        indexes = @Index(name = "idx_deal_flag_lookup", columnList = "snapshot_date,complex_id"))
public class DealFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(length = 64, nullable = false)
    private String dealHash;

    /** 조회 편의를 위해 비정규화한다. 3단계가 단지 페이지에서 바로 읽는다. */
    private Long complexId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private FlagType flagType;

    private LocalDateTime detectedAt;

    protected DealFlag() {
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public String getDealHash() { return dealHash; }
    public Long getComplexId() { return complexId; }
    public FlagType getFlagType() { return flagType; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
}
