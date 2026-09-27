package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 갱신 계약에서 실제로 반환된 보증금 감액 집계 (단지×평형).
 *
 * complex_rent_stat과 grain은 같지만(snapshot_date, complex_id, area_bucket) 별도
 * 테이블로 둔 이유가 두 가지 있다. 첫째, complex_rent_stat은 "현재 시세"를 뜻하고 이
 * 테이블은 "과거에 실제로 일어난 사실"이다 — 성격이 다르다. 둘째, complex_rent_stat은
 * 시세를 계산할 수 있었던 (단지,평형)만 갖는데, 감액 이력은 있지만 현재 시세가 없는
 * (단지,평형)도 있을 수 있다 — 같은 테이블에 얹으면 그 사실이 묻힌다.
 *
 * rent_deal.prev_deposit은 정부 API가 갱신 계약에 함께 보고하는 "그 이전 보증금"이다.
 * deposit &lt; prev_deposit이면 임대인이 차액을 실제로 돌려준 것 — 추정이 아니라 이미
 * 일어난 사실이므로 confidence 컬럼이 없다.
 */
@Entity
@Table(name = "renewal_reduction_stat",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_renewal_reduction_key",
                columnNames = {"snapshot_date", "complex_id", "area_bucket"}),
        indexes = @Index(name = "idx_renewal_reduction_lookup",
                columnList = "snapshot_date,complex_id"))
public class RenewalReductionStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private Long complexId;

    @Column(nullable = false)
    private Integer areaBucket;

    /** 이전 보증금이 함께 보고된 갱신 계약 건수 (monthly_rent = 0 AND prev_deposit > 0) */
    private Integer renewalCount;

    /** 그중 deposit &lt; prev_deposit인 건수 */
    private Integer reductionCount;

    /** 감액분(prev_deposit - deposit)의 중위값, 원 단위. reductionCount건 기준 */
    private Long medianReduction;

    /** 감액이 있었던 거래 중 가장 최근 deal_date */
    private LocalDate latestReductionDate;

    protected RenewalReductionStat() {
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public Long getComplexId() { return complexId; }
    public Integer getAreaBucket() { return areaBucket; }
    public Integer getRenewalCount() { return renewalCount; }
    public Integer getReductionCount() { return reductionCount; }
    public Long getMedianReduction() { return medianReduction; }
    public LocalDate getLatestReductionDate() { return latestReductionDate; }
}
