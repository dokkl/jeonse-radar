package com.jeonseradar.deal.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rent_deal",
        uniqueConstraints = @UniqueConstraint(name = "uk_rent_hash", columnNames = "deal_hash"),
        indexes = {
                @Index(name = "idx_rent_complex_bucket", columnList = "complex_id,area_bucket,deal_date"),
                @Index(name = "idx_rent_maturity", columnList = "complex_id,area_bucket,term_end_ym")
        })
public class RentDeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String dealHash;

    private Long complexId;

    /** 단지 일련번호. 실측 채움률 100% */
    @Column(length = 32)
    private String aptSeq;

    @Column(length = 5, nullable = false)
    private String regionCode;

    @Column(length = 64)
    private String legalDong;

    @Column(length = 32)
    private String jibun;

    @Column(length = 128)
    private String aptName;

    @Column(precision = 10, scale = 2)
    private BigDecimal area;

    private Integer areaBucket;

    private Integer floor;

    private LocalDate dealDate;

    /** 보증금, 원 단위 */
    private Long deposit;

    /** 월세, 원 단위. 0이면 순수 전세 — 역전세 산식의 대상 필터다 (스펙 §3.3) */
    private Long monthlyRent;

    private Integer buildYear;

    @Column(length = 8)
    private String contractType;

    /** 계약기간 원본 문자열. 파싱 실패 원인 추적과 결측률 실측에 필요하다 */
    @Column(length = 32)
    private String contractTermRaw;

    @Column(length = 6)
    private String termStartYm;

    @Column(length = 6)
    private String termEndYm;

    /** true면 계약기간 결측으로 계약일+24개월 추정한 값이다 (스펙 §3.2) */
    private Boolean termEstimated;

    /** 갱신권을 이미 쓴 계약은 다음 만기에 갱신권이 없다 — 유료화 핵심 지표 (스펙 §3.6) */
    private Boolean renewalRightUsed;

    private Long prevDeposit;

    private Long prevMonthlyRent;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected RentDeal() {
    }

    public static RentDeal of(String dealHash, Long complexId, String aptSeq, String regionCode,
                              String legalDong, String jibun, String aptName,
                              BigDecimal area, Integer areaBucket, Integer floor,
                              LocalDate dealDate, Long deposit, Long monthlyRent,
                              Integer buildYear, String contractType, String contractTermRaw,
                              String termStartYm, String termEndYm, Boolean termEstimated,
                              Boolean renewalRightUsed, Long prevDeposit, Long prevMonthlyRent) {
        RentDeal deal = new RentDeal();
        deal.dealHash = dealHash;
        deal.complexId = complexId;
        deal.aptSeq = aptSeq;
        deal.regionCode = regionCode;
        deal.legalDong = legalDong;
        deal.jibun = jibun;
        deal.aptName = aptName;
        deal.area = area;
        deal.areaBucket = areaBucket;
        deal.floor = floor;
        deal.dealDate = dealDate;
        deal.deposit = deposit;
        deal.monthlyRent = monthlyRent;
        deal.buildYear = buildYear;
        deal.contractType = contractType;
        deal.contractTermRaw = contractTermRaw;
        deal.termStartYm = termStartYm;
        deal.termEndYm = termEndYm;
        deal.termEstimated = termEstimated;
        deal.renewalRightUsed = renewalRightUsed;
        deal.prevDeposit = prevDeposit;
        deal.prevMonthlyRent = prevMonthlyRent;
        return deal;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getDealHash() { return dealHash; }
    public Long getComplexId() { return complexId; }
    public String getAptSeq() { return aptSeq; }
    public String getRegionCode() { return regionCode; }
    public String getLegalDong() { return legalDong; }
    public String getJibun() { return jibun; }
    public String getAptName() { return aptName; }
    public BigDecimal getArea() { return area; }
    public Integer getAreaBucket() { return areaBucket; }
    public Integer getFloor() { return floor; }
    public LocalDate getDealDate() { return dealDate; }
    public Long getDeposit() { return deposit; }
    public Long getMonthlyRent() { return monthlyRent; }
    public Integer getBuildYear() { return buildYear; }
    public String getContractType() { return contractType; }
    public String getContractTermRaw() { return contractTermRaw; }
    public String getTermStartYm() { return termStartYm; }
    public String getTermEndYm() { return termEndYm; }
    public Boolean getTermEstimated() { return termEstimated; }
    public Boolean getRenewalRightUsed() { return renewalRightUsed; }
    public Long getPrevDeposit() { return prevDeposit; }
    public Long getPrevMonthlyRent() { return prevMonthlyRent; }
}
