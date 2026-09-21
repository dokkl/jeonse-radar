package com.jeonseradar.deal.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_deal",
        uniqueConstraints = @UniqueConstraint(name = "uk_trade_hash", columnNames = "deal_hash"),
        indexes = {
                @Index(name = "idx_trade_complex_bucket", columnList = "complex_id,area_bucket,deal_date"),
                @Index(name = "idx_trade_region_date", columnList = "region_code,deal_date")
        })
public class TradeDeal {

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

    /** 원 단위 */
    private Long dealAmount;

    private Integer buildYear;

    @Column(length = 16)
    private String dealType;

    @Column(length = 64)
    private String agentRegion;

    /** 가변 필드 — deal_hash에 포함하지 않는다 */
    private LocalDate registeredDate;

    /** 가변 필드 — deal_hash에 포함하지 않는다 */
    @Column(length = 1)
    private String cancelYn;

    /** 가변 필드 — deal_hash에 포함하지 않는다 */
    private LocalDate cancelDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected TradeDeal() {
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

    public static TradeDeal of(String dealHash, Long complexId, String aptSeq, String regionCode,
                               String legalDong, String jibun, String aptName,
                               BigDecimal area, Integer areaBucket, Integer floor,
                               LocalDate dealDate, Long dealAmount, Integer buildYear,
                               String dealType, String agentRegion,
                               LocalDate registeredDate, String cancelYn, LocalDate cancelDate) {
        TradeDeal deal = new TradeDeal();
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
        deal.dealAmount = dealAmount;
        deal.buildYear = buildYear;
        deal.dealType = dealType;
        deal.agentRegion = agentRegion;
        deal.registeredDate = registeredDate;
        deal.cancelYn = cancelYn;
        deal.cancelDate = cancelDate;
        return deal;
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
    public Long getDealAmount() { return dealAmount; }
    public Integer getBuildYear() { return buildYear; }
    public String getDealType() { return dealType; }
    public String getAgentRegion() { return agentRegion; }
    public LocalDate getRegisteredDate() { return registeredDate; }
    public String getCancelYn() { return cancelYn; }
    public LocalDate getCancelDate() { return cancelDate; }
}
