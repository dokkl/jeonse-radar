package com.jeonseradar.deal.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "complex",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_complex_apt_seq",
                columnNames = {"region_code", "apt_seq"}))
public class Complex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 5, nullable = false)
    private String regionCode;

    /**
     * 단지 일련번호 (예: 11110-2519). 실측 채움률 100%.
     * 지번은 서로 다른 단지 여럿을 한 값으로 합쳐버리므로(실측 43건) 식별 키로 쓸 수 없다.
     * 한 aptSeq가 여러 지번에 걸치는 사례와 한 aptSeq에 이름이 둘 이상인 사례는 실측 0건이다.
     */
    @Column(length = 32, nullable = false)
    private String aptSeq;

    /** 표시·검색용 속성. 식별 키가 아니다. */
    @Column(length = 64)
    private String legalDong;

    /** 표시·검색용 속성. 식별 키가 아니다. */
    @Column(length = 32)
    private String jibun;

    @Column(length = 128, nullable = false)
    private String displayName;

    private Integer buildYear;

    @Column(length = 160, unique = true)
    private String slug;

    protected Complex() {
    }

    public Complex(String regionCode, String aptSeq, String legalDong, String jibun,
                   String displayName, Integer buildYear, String slug) {
        this.regionCode = regionCode;
        this.aptSeq = aptSeq;
        this.legalDong = legalDong;
        this.jibun = jibun;
        this.displayName = displayName;
        this.buildYear = buildYear;
        this.slug = slug;
    }

    public Long getId() {
        return id;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public String getAptSeq() {
        return aptSeq;
    }

    public String getLegalDong() {
        return legalDong;
    }

    public String getJibun() {
        return jibun;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSlug() {
        return slug;
    }
}
