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

    /**
     * 전세 시세(median_deposit)만 보는 신뢰도다 (스펙 §3.5). {@code ComplexPageQuery.indexable()},
     * {@code SitemapController}, {@code CoverageReporter}가 그대로 색인 가능 여부의 게이트로
     * 읽으므로, 전세가율 신뢰도를 여기 덮어쓰지 않는다 — 매매 표본이 얇다는 이유로 이미 색인된
     * 페이지가 빠지게 할 수 없다. 전세가율만의 신뢰도는 {@link #ratioConfidence}에 따로 둔다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Confidence confidence;

    /** 같은 단지×평형, 같은 6개월 창의 매매 중위가, 원 단위. 매매 표본이 없으면 null. */
    private Long medianSalePrice;

    /**
     * 전세가율 = medianDeposit / medianSalePrice. L3는 medianDeposit이 다른 단지(법정동)의
     * 값을 빌려온 것이라 이 단지의 medianSalePrice와 나누는 것이 서로 다른 두 단지를 비교하는
     * 셈이 된다 — L3 행은 항상 null이다 (medianSalePrice 자체는 이 단지 자신의 매매이므로
     * null이 아닐 수 있다).
     */
    private Double jeonseRatio;

    /**
     * 전세가율(jeonseRatio)만의 신뢰도 — 전세 쪽 confidence와 매매 표본 수 기준 등급(§3.5
     * 사다리: 10건+ HIGH, 3~9건 MEDIUM, 1~2건 LOW) 중 낮은 쪽이다
     * ({@link com.jeonseradar.metric.service.MaturityForecastBuilder}가 then/current 양쪽에
     * 적용하는 것과 같은 규칙). jeonseRatio가 null인 L3 행에서는 이 값도 항상 null이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Confidence ratioConfidence;

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
    public Long getMedianSalePrice() { return medianSalePrice; }
    public Double getJeonseRatio() { return jeonseRatio; }
    public Confidence getRatioConfidence() { return ratioConfidence; }
}
