# 2단계: 지표 집계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1단계가 적재한 실거래 원본에서 단지×평형 전세 시세, 만기 도래 예측, 역전세 갭, 거래 검증 플래그를 야간 배치로 집계해 3단계 페이지가 읽을 스냅샷 테이블을 만든다.

**Architecture:** 원본 테이블(`rent_deal`/`trade_deal`)은 읽기만 하고, 집계 결과는 `snapshot_date`로 구분된 별도 테이블에 적재한다. 조회는 **완료로 표시된 최신 스냅샷만** 참조하므로 빌드 중간 상태가 화면에 새지 않는다. 산식이 바뀌면 원본에서 다시 계산하며 백필을 되돌릴 일이 없다.

**Tech Stack:** Spring Boot 3.4.x / JDK 21 / Gradle (Groovy DSL) / MySQL 8.0 / Spring Data JPA + JdbcTemplate / JUnit 5 / Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-21-jeonse-maturity-radar-design.md`

**선행 단계:** 1단계 수집 파이프라인 (`docs/superpowers/plans/2026-09-21-phase1-ingest-pipeline.md`) — 완료

## Global Constraints

모든 태스크의 요구사항에 암묵적으로 포함된다.

- JDK 21, Spring Boot 3.4.x, Gradle Groovy DSL, MySQL 8.0, JUnit 5 + Testcontainers.
- **네트워크를 타는 테스트를 만들지 않는다.** 이 단계는 외부 API를 호출하지 않는다. Testcontainers(로컬 Docker MySQL)는 정상이다.
- 금액은 **`BIGINT`, 원 단위**. 면적은 `DECIMAL(10,2)`, 버킷은 `INT`(1㎡ 반올림).
- **1단계 파일을 수정하지 않는다.** 집계는 `com.jeonseradar.metric` 새 패키지에 둔다. 원본 테이블은 읽기 전용으로 다룬다.
- `@UniqueConstraint(columnNames=...)`와 `@Index(columnList=...)`에는 **snake_case 물리 컬럼명**을 쓴다 (1단계 관례).
- **순수 전세만 시세·만기 산식의 대상이다** — `monthly_rent = 0` (스펙 §3.3). 반전세를 보증금만으로 비교하면 왜곡된다.
- **추정치를 확정치처럼 노출하지 않는다.** `term_estimated`가 섞인 집계는 `estimated_count`를 함께 싣는다 (스펙 §3.2).
- **신뢰도를 점수화하지 않는다.** `confidence`는 HIGH/MEDIUM/LOW 세 값의 열거형이며 임의 가중 점수를 만들지 않는다 (스펙 §3.7).
- 커밋 메시지: 한국어 한 줄 요약 + Conventional Commits 접두사, **빈 줄**, 그다음 정확히
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
  커밋 후 `git log -1 --format='%(trailers:key=Co-Authored-By,valueonly=true)'`로 값이 출력되는지 확인한다.
- 브랜치 생성·이름변경·삭제·전환·머지·푸시 금지. git 상태가 이상하면 멈추고 보고한다.
- **한글 테스트 메서드명은 숫자로 시작할 수 없다** — 자바 식별자 규칙이다.
  `61일째부터_...` 가 아니라 `계약_후_61일째부터_...` 처럼 앞에 낱말을 붙인다.

## 실측 근거 (2026-09-22, 백필 진행 중 6.3% 시점)

이 계획의 설계 판단은 실제 적재 데이터에서 측정한 값에 근거한다. 추측이 아니다.

**confidence 계층 분포** (최근 6개월, 순수 전세):

| 계층 | LOW (1~2건) | MEDIUM (3~9건) | HIGH (10건+) |
|------|------------|---------------|-------------|
| 단지×평형 (L1) | **54.5%** | 31.4% | 14.2% |
| 단지 전체 (L2) | **41.4%** | 31.0% | 27.6% |

**이 수치가 3단계를 좌우한다.** 스펙 §4.1이 "LOW면 `noindex`"이므로 단지 페이지의 약 41%가 색인에서 빠진다. 게다가 측정 시점의 적재분은 시군구 코드 순서상 서울에 쏠려 있어 거래가 많은 쪽이 과대표집됐다 — **전국을 다 받으면 LOW 비율은 더 오른다.**

그래서 Task 7에서 발행 가능 페이지 수를 명시적으로 측정한다. 3단계를 다 만든 뒤 "페이지 절반이 안 뜬다"를 발견하면 늦다.

**`deal_hash` 충돌 약 2%** — 동/호가 공개되지 않아 같은 층·같은 면적·같은 날·같은 금액의 서로 다른 호실이 한 행으로 합쳐진다(실측 1.7~2.2%). 따라서 **모든 건수 지표는 체계적으로 약 2% 과소 집계**된다. 이 계획은 이를 보정하지 않으며(보정 시도는 해시를 불안정하게 만든다), 대신 Task 7의 리포트에 명시한다.

---

## 파일 구조

```
src/main/java/com/jeonseradar/metric/
├── entity/
│   ├── Confidence.java              HIGH / MEDIUM / LOW
│   ├── SourceLevel.java             L1 / L2 / L3
│   ├── FlagType.java                CANCELED / UNREGISTERED / DIRECT
│   ├── SnapshotStatus.java          BUILDING / COMPLETE / FAILED
│   ├── MetricSnapshot.java          스냅샷 메타 (어느 것이 조회 대상인지)
│   ├── ComplexRentStat.java         단지×평형 현재 전세 시세
│   ├── MaturityForecast.java        단지×평형×만기월 예측
│   └── DealFlag.java                거래별 검증 플래그
├── repository/
│   ├── MetricSnapshotRepository.java
│   ├── ComplexRentStatRepository.java
│   ├── MaturityForecastRepository.java
│   └── DealFlagRepository.java
└── service/
    ├── MedianSql.java               MySQL 8에 MEDIAN이 없어 직접 만드는 공용 SQL 조각
    │                                (집계는 INSERT ... SELECT로 DB 안에서 끝나므로
    │                                 행을 자바로 끌어올리는 삽입 DAO가 따로 필요 없다)
    ├── RentStatBuilder.java         §3.5 confidence 폴백 계단
    ├── MaturityForecastBuilder.java §3.2 만기월 + §3.4 갭 + §3.6 갱신권
    ├── DealFlagBuilder.java         §3.7 플래그 판정
    ├── SnapshotBuilder.java         오케스트레이션 + 스냅샷 완료 표시
    ├── SnapshotScheduler.java       매일 04:00 KST
    └── CoverageReporter.java        발행 가능 페이지 실측 (3단계 진입 게이트)
```

---

### Task 1: 집계 스키마 — 열거형과 엔티티

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/entity/Confidence.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/SourceLevel.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/FlagType.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/SnapshotStatus.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/MetricSnapshot.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/ComplexRentStat.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/MaturityForecast.java`
- Create: `src/main/java/com/jeonseradar/metric/entity/DealFlag.java`
- Test: `src/test/java/com/jeonseradar/metric/entity/MetricSchemaTest.java`

**Interfaces:**
- Consumes: 없음 (2단계 첫 태스크)
- Produces: 위 8개 타입. 이후 모든 태스크가 참조한다.
  - `Confidence.of(int sampleCount, SourceLevel level) -> Confidence` — 표본 수와 폴백 계층 중 **낮은 쪽**을 채택

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/entity/MetricSchemaTest.java`:

```java
package com.jeonseradar.metric.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MetricSchemaTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    private boolean tableExists(String name) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, name);
        return n != null && n > 0;
    }

    @Test
    void 집계_테이블_네_개가_생성된다() {
        assertThat(tableExists("metric_snapshot")).isTrue();
        assertThat(tableExists("complex_rent_stat")).isTrue();
        assertThat(tableExists("maturity_forecast")).isTrue();
        assertThat(tableExists("deal_flag")).isTrue();
    }

    @Test
    void 스냅샷과_단지평형_조합에_유니크_제약이_걸린다() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = 'complex_rent_stat' "
                        + "AND constraint_type = 'UNIQUE'",
                Integer.class);

        assertThat(n).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 표본이_10건_이상이고_L1이면_HIGH다() {
        assertThat(Confidence.of(10, SourceLevel.L1)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.of(37, SourceLevel.L1)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void 표본이_3에서_9건이면_MEDIUM이다() {
        assertThat(Confidence.of(3, SourceLevel.L1)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(9, SourceLevel.L1)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void 표본이_1에서_2건이면_LOW다() {
        assertThat(Confidence.of(1, SourceLevel.L1)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(2, SourceLevel.L1)).isEqualTo(Confidence.LOW);
    }

    @Test
    void 폴백_계층이_낮으면_표본이_많아도_상한에_묶인다() {
        // L2는 상한이 MEDIUM, L3는 상한이 LOW (스펙 §3.5)
        assertThat(Confidence.of(100, SourceLevel.L2)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(100, SourceLevel.L3)).isEqualTo(Confidence.LOW);
    }

    @Test
    void 표본이_적으면_계층이_높아도_표본_쪽이_이긴다() {
        // 둘 중 낮은 쪽을 채택한다
        assertThat(Confidence.of(1, SourceLevel.L2)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(5, SourceLevel.L2)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void 표본이_0이면_산출_불가이므로_LOW다() {
        assertThat(Confidence.of(0, SourceLevel.L1)).isEqualTo(Confidence.LOW);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*MetricSchemaTest'`
Expected: FAIL — `Confidence`, `SourceLevel` 등 타입이 없어 컴파일 에러

- [ ] **Step 3: 열거형 작성**

`src/main/java/com/jeonseradar/metric/entity/SourceLevel.java`:

```java
package com.jeonseradar.metric.entity;

/**
 * 시세 산출에 쓴 폴백 계층 (스펙 §3.5).
 *
 * 단지+평형에 표본이 없으면 단지 전체로, 그래도 없으면 법정동+평형으로 내려간다.
 * 내려갈수록 추정의 성격이 강해지므로 confidence 상한이 함께 내려간다.
 */
public enum SourceLevel {

    /** 단지 + 평형, 최근 6개월 */
    L1(Confidence.HIGH),

    /** 단지 전체 평형, ㎡당 단가 환산 */
    L2(Confidence.MEDIUM),

    /** 법정동 + 평형 */
    L3(Confidence.LOW);

    private final Confidence cap;

    SourceLevel(Confidence cap) {
        this.cap = cap;
    }

    public Confidence cap() {
        return cap;
    }
}
```

`src/main/java/com/jeonseradar/metric/entity/Confidence.java`:

```java
package com.jeonseradar.metric.entity;

/**
 * 시세 산출의 신뢰 수준 (스펙 §3.5).
 *
 * 점수가 아니라 세 단계다. 임의 가중치로 "신뢰도 62점" 같은 수치를 만들지 않는다 (스펙 §3.7).
 * 3단계에서 LOW는 noindex 게이트로도 쓰인다 (스펙 §4.1).
 */
public enum Confidence {

    LOW(0),
    MEDIUM(1),
    HIGH(2);

    private final int rank;

    Confidence(int rank) {
        this.rank = rank;
    }

    /**
     * 표본 수 기준과 폴백 계층 상한 중 **낮은 쪽**을 채택한다.
     *
     * 표본이 아무리 많아도 L3로 내려갔다면 그것은 다른 동네 시세를 빌려 쓴 것이므로
     * HIGH일 수 없고, 반대로 L1이라도 표본이 2건이면 중위값이 사실상 노이즈다.
     */
    public static Confidence of(int sampleCount, SourceLevel level) {
        Confidence bySample = fromSampleCount(sampleCount);
        Confidence cap = level.cap();
        return bySample.rank <= cap.rank ? bySample : cap;
    }

    private static Confidence fromSampleCount(int sampleCount) {
        if (sampleCount >= 10) {
            return HIGH;
        }
        if (sampleCount >= 3) {
            return MEDIUM;
        }
        return LOW;
    }
}
```

`src/main/java/com/jeonseradar/metric/entity/FlagType.java`:

```java
package com.jeonseradar.metric.entity;

/**
 * 매매 신고가 검증 플래그 (스펙 §3.7).
 *
 * 사실만 나타내며 판단을 담지 않는다. "자전거래 의심" 같은 단정은 만들지 않는다.
 */
public enum FlagType {

    /** 신고 후 해제된 거래 */
    CANCELED,

    /** 계약 후 60일이 지났는데 등기가 확인되지 않은 거래 */
    UNREGISTERED,

    /** 중개 없이 이루어진 직거래 */
    DIRECT
}
```

`src/main/java/com/jeonseradar/metric/entity/SnapshotStatus.java`:

```java
package com.jeonseradar.metric.entity;

public enum SnapshotStatus {

    /** 빌드 진행 중. 조회 대상이 아니다. */
    BUILDING,

    /** 빌드 완료. 조회는 이 상태의 최신 스냅샷만 본다. */
    COMPLETE,

    /** 빌드 실패. 직전 COMPLETE 스냅샷이 계속 서비스된다. */
    FAILED
}
```

- [ ] **Step 4: 스냅샷 메타 엔티티 작성**

`src/main/java/com/jeonseradar/metric/entity/MetricSnapshot.java`:

```java
package com.jeonseradar.metric.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 집계 스냅샷의 메타 정보.
 *
 * 집계 테이블에 snapshot_date만 두면 "이 스냅샷이 다 만들어진 것인지" 알 수 없어,
 * 빌드 도중의 반쪽짜리 결과가 화면에 샐 수 있다 (스펙 §1.7).
 * 조회 계층은 반드시 이 테이블에서 COMPLETE인 최신 snapshot_date를 먼저 찾는다.
 */
@Entity
@Table(name = "metric_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_metric_snapshot_date",
                columnNames = "snapshot_date"))
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private SnapshotStatus status;

    private Integer rentStatRows;
    private Integer maturityRows;
    private Integer dealFlagRows;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(length = 500)
    private String failureReason;

    protected MetricSnapshot() {
    }

    public static MetricSnapshot start(LocalDate snapshotDate) {
        MetricSnapshot s = new MetricSnapshot();
        s.snapshotDate = snapshotDate;
        s.status = SnapshotStatus.BUILDING;
        s.startedAt = LocalDateTime.now();
        return s;
    }

    public void complete(int rentStatRows, int maturityRows, int dealFlagRows) {
        this.status = SnapshotStatus.COMPLETE;
        this.rentStatRows = rentStatRows;
        this.maturityRows = maturityRows;
        this.dealFlagRows = dealFlagRows;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = SnapshotStatus.FAILED;
        this.failureReason = reason == null ? null
                : reason.substring(0, Math.min(reason.length(), 500));
        this.completedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public SnapshotStatus getStatus() { return status; }
    public Integer getRentStatRows() { return rentStatRows; }
    public Integer getMaturityRows() { return maturityRows; }
    public Integer getDealFlagRows() { return dealFlagRows; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getFailureReason() { return failureReason; }
}
```

- [ ] **Step 5: 집계 엔티티 3종 작성**

`src/main/java/com/jeonseradar/metric/entity/ComplexRentStat.java`:

```java
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
```

`src/main/java/com/jeonseradar/metric/entity/MaturityForecast.java`:

```java
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
```

`src/main/java/com/jeonseradar/metric/entity/DealFlag.java`:

```java
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
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*MetricSchemaTest'`
Expected: PASS (8개 테스트)

스키마 생성이 실패하면 `@UniqueConstraint`/`@Index`의 컬럼명이 snake_case인지부터 확인한다.

- [ ] **Step 7: 커밋**

```bash
git branch --show-current   # feat/phase2-metric 이어야 한다
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 집계 스냅샷 스키마 및 confidence 계층 추가

confidence는 표본 수 기준과 폴백 계층 상한 중 낮은 쪽을 채택한다.
표본이 많아도 L3로 내려갔다면 다른 동네 시세를 빌려 쓴 것이므로 HIGH일 수 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git log -1 --format='%(trailers:key=Co-Authored-By,valueonly=true)'
```

---

### Task 2: 전세 시세 집계 — confidence 폴백 계단

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/service/MedianSql.java`
- Create: `src/main/java/com/jeonseradar/metric/service/RentStatBuilder.java`
- Create: `src/main/java/com/jeonseradar/metric/repository/ComplexRentStatRepository.java`
- Test: `src/test/java/com/jeonseradar/metric/service/RentStatBuilderTest.java`

**Interfaces:**
- Consumes: Task 1의 `Confidence`, `SourceLevel`, `ComplexRentStat`
- Produces:
  - `RentStatBuilder.build(LocalDate snapshotDate, LocalDate asOf) -> int` (삽입 행 수)
  - `MedianSql.MEDIAN_BY_COMPLEX_BUCKET` 등 공용 SQL 상수

**MySQL 8에는 `MEDIAN()`도 `PERCENTILE_CONT()`도 없다.** 윈도 함수로 직접 만든다. 표본이 짝수면 가운데 두 값의 평균, 홀수면 가운데 값이다.

```sql
ROW_NUMBER() OVER (PARTITION BY ... ORDER BY deposit) rn,
COUNT(*)     OVER (PARTITION BY ...)                 cnt
...
WHERE rn IN (FLOOR((cnt+1)/2), CEIL((cnt+1)/2))
```

**폴백 계단의 대상 집합 정의** — 무엇에 대해 시세를 구할 것인가:

```
U = rent_deal에서 monthly_rent = 0 인 (complex_id, area_bucket) 전체 (기간 무관)
```

만기 예측을 할 대상이 곧 시세가 필요한 대상이기 때문이다. U의 각 원소에 대해:

| 계층 | 산출 방법 | 대상 |
|------|----------|------|
| L1 | 같은 단지·평형, 최근 6개월 보증금 중위값 | 최근 6개월 표본이 있는 것 |
| L2 | 같은 단지 전체 평형의 **㎡당 단가 중위값 × 해당 평형** | L1이 없는 것 |
| L3 | 같은 법정동·평형 보증금 중위값 | L1·L2 둘 다 없는 것 |

L2가 ㎡당 단가를 쓰는 이유: 같은 단지라도 평형이 다르면 절대 금액이 다르다. 단가로 환산해야 다른 평형의 거래를 빌려 쓸 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/RentStatBuilderTest.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
import com.jeonseradar.metric.entity.SourceLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RentStatBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Autowired
    RentStatBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM rent_deal");
        jdbcTemplate.update("DELETE FROM complex");
    }

    /** complex 한 건을 만들고 id를 돌려준다. */
    private long complex(long id, String regionCode, String legalDong) {
        jdbcTemplate.update(
                "INSERT INTO complex (id, region_code, apt_seq, legal_dong, jibun, display_name, slug) "
                        + "VALUES (?,?,?,?,'1','단지',?)",
                id, regionCode, regionCode + "-" + id, legalDong, "slug-" + id);
        return id;
    }

    /** 순수 전세 한 건. deposit은 원 단위. */
    private void rent(long complexId, String regionCode, String legalDong,
                      int areaBucket, long deposit, LocalDate dealDate) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?, '1','단지',?,?,3,?,?,0,FALSE,FALSE,NOW(),NOW())",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                complexId, regionCode + "-" + complexId, regionCode, legalDong,
                java.math.BigDecimal.valueOf(areaBucket), areaBucket, dealDate, deposit);
    }

    private Map<String, Object> statOf(long complexId, int areaBucket) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM complex_rent_stat WHERE complex_id = ? AND area_bucket = ?",
                complexId, areaBucket);
    }

    @Test
    void L1은_같은_단지_평형의_최근_6개월_중위값을_쓴다() {
        complex(1, "11680", "대치동");
        rent(1, "11680", "대치동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(1, "11680", "대치동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));
        rent(1, "11680", "대치동", 85, 700_000_000L, LocalDate.of(2026, 6, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(1, 85);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(600_000_000L);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(3);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L1.name());
    }

    @Test
    void 표본이_짝수면_가운데_두_값의_평균이다() {
        complex(2, "11680", "대치동");
        rent(2, "11680", "대치동", 85, 400_000_000L, LocalDate.of(2026, 8, 1));
        rent(2, "11680", "대치동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));

        builder.build(SNAPSHOT, AS_OF);

        assertThat(((Number) statOf(2, 85).get("median_deposit")).longValue())
                .isEqualTo(500_000_000L);
    }

    @Test
    void 거래일이_6개월보다_오래되면_L1_표본에서_빠진다() {
        complex(3, "11680", "대치동");
        rent(3, "11680", "대치동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));  // 6개월 밖
        rent(3, "11680", "대치동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(3, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void L1_표본이_없으면_같은_단지의_제곱미터당_단가로_환산한다() {
        complex(4, "11680", "대치동");
        // 85㎡는 오래된 거래만 있어 L1 불가
        rent(4, "11680", "대치동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 같은 단지 60㎡에 최근 거래 → ㎡당 600만원
        rent(4, "11680", "대치동", 60, 360_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(4, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L2.name());
        // ㎡당 600만원 x 85㎡ = 5.1억
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(510_000_000L);
    }

    @Test
    void L2까지_없으면_같은_법정동_같은_평형을_빌려_쓴다() {
        complex(5, "11680", "삼성동");
        complex(6, "11680", "삼성동");
        // 단지5의 85㎡는 오래된 거래뿐이고 다른 평형도 없다
        rent(5, "11680", "삼성동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        // 같은 법정동의 다른 단지 85㎡에 최근 거래
        rent(6, "11680", "삼성동", 85, 550_000_000L, LocalDate.of(2026, 8, 1));

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(5, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(550_000_000L);
    }

    @Test
    void L3는_표본이_많아도_LOW를_넘지_못한다() {
        complex(7, "11680", "청담동");
        complex(8, "11680", "청담동");
        rent(7, "11680", "청담동", 85, 900_000_000L, LocalDate.of(2025, 1, 1));
        for (int i = 0; i < 20; i++) {
            rent(8, "11680", "청담동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(7, 85);
        assertThat(stat.get("source_level")).isEqualTo(SourceLevel.L3.name());
        assertThat(stat.get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 표본_10건_이상의_L1은_HIGH다() {
        complex(9, "11680", "역삼동");
        for (int i = 0; i < 12; i++) {
            rent(9, "11680", "역삼동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        }

        builder.build(SNAPSHOT, AS_OF);

        assertThat(statOf(9, 85).get("confidence")).isEqualTo(Confidence.HIGH.name());
    }

    @Test
    void 월세가_있는_계약은_시세_산출에서_제외된다() {
        complex(10, "11680", "논현동");
        rent(10, "11680", "논현동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        // 반전세 한 건을 직접 넣는다 (rent 헬퍼는 monthly_rent = 0 고정)
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun,"
                        + " apt_name, area, area_bucket, floor, deal_date, deposit, monthly_rent,"
                        + " term_estimated, renewal_right_used, created_at, updated_at)"
                        + " VALUES ('banjeonse-1',10,'11680-10','11680','논현동','1','단지',85,85,3,"
                        + " '2026-08-01', 100000000, 800000, FALSE, FALSE, NOW(), NOW())");

        builder.build(SNAPSHOT, AS_OF);

        Map<String, Object> stat = statOf(10, 85);
        assertThat(((Number) stat.get("sample_count")).intValue()).isEqualTo(1);
        assertThat(((Number) stat.get("median_deposit")).longValue()).isEqualTo(500_000_000L);
    }

    @Test
    void 한_단지_평형에_행이_하나만_생긴다() {
        complex(11, "11680", "도곡동");
        rent(11, "11680", "도곡동", 85, 500_000_000L, LocalDate.of(2026, 8, 1));
        rent(11, "11680", "도곡동", 85, 600_000_000L, LocalDate.of(2026, 7, 1));

        builder.build(SNAPSHOT, AS_OF);

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE complex_id = 11 AND area_bucket = 85",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*RentStatBuilderTest'`
Expected: FAIL — `RentStatBuilder` 없음

- [ ] **Step 3: MedianSql 작성**

`src/main/java/com/jeonseradar/metric/service/MedianSql.java`:

```java
package com.jeonseradar.metric.service;

/**
 * MySQL 8에는 MEDIAN()도 PERCENTILE_CONT()도 없다. 윈도 함수로 직접 만든다.
 *
 * ROW_NUMBER로 순위를 매기고 COUNT로 전체 개수를 구한 뒤, 가운데 순위만 남긴다.
 * 홀수면 FLOOR와 CEIL이 같은 값을 가리켜 한 행, 짝수면 두 행이 남으므로
 * AVG로 평균을 내면 두 경우가 한 식으로 처리된다.
 *
 * 평균이 아니라 중위값을 쓰는 이유: 전세 보증금은 소수의 대형 평형이나 이상 거래에
 * 크게 끌려가므로, 평균은 "이 단지에서 실제로 계약되는 금액"을 대표하지 못한다.
 */
public final class MedianSql {

    private MedianSql() {
    }

    /** 가운데 순위만 남기는 조건. ranked CTE 안에 rn, cnt 컬럼이 있다고 가정한다. */
    public static final String MIDDLE_ROWS = "rn IN (FLOOR((cnt + 1) / 2), CEIL((cnt + 1) / 2))";
}
```

- [ ] **Step 4: Repository 작성**

`src/main/java/com/jeonseradar/metric/repository/ComplexRentStatRepository.java`:

```java
package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.ComplexRentStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ComplexRentStatRepository extends JpaRepository<ComplexRentStat, Long> {

    Optional<ComplexRentStat> findBySnapshotDateAndComplexIdAndAreaBucket(
            LocalDate snapshotDate, Long complexId, Integer areaBucket);

    List<ComplexRentStat> findBySnapshotDateAndComplexId(LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
```

- [ ] **Step 5: RentStatBuilder 작성**

`src/main/java/com/jeonseradar/metric/service/RentStatBuilder.java`:

```java
package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 단지×평형 현재 전세 시세를 집계한다 (스펙 §3.5).
 *
 * 세 계층을 차례로 채우되, 앞 계층이 채운 (단지,평형)은 뒤 계층이 건드리지 않는다.
 * 행 단위로 자바에서 돌면 수백만 건에서 감당이 안 되므로 INSERT ... SELECT로 처리한다.
 *
 * 대상 집합은 "순수 전세 계약이 한 번이라도 있었던 (단지, 평형)" 전체다.
 * 만기 예측을 할 대상이 곧 시세가 필요한 대상이기 때문이다.
 */
@Service
public class RentStatBuilder {

    private static final int WINDOW_MONTHS = 6;

    private final JdbcTemplate jdbcTemplate;

    public RentStatBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param snapshotDate 집계 결과에 찍을 스냅샷 날짜
     * @param asOf         "최근 6개월"의 기준점. 테스트가 시간을 고정할 수 있도록 인자로 받는다.
     * @return 삽입된 행 수
     */
    @Transactional
    public int build(LocalDate snapshotDate, LocalDate asOf) {
        LocalDate windowStart = asOf.minusMonths(WINDOW_MONTHS);
        int inserted = insertL1(snapshotDate, windowStart);
        inserted += insertL2(snapshotDate, windowStart);
        inserted += insertL3(snapshotDate, windowStart);
        return inserted;
    }

    /** L1: 같은 단지·평형의 최근 6개월 보증금 중위값. */
    private int insertL1(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH ranked AS (
                  SELECT complex_id, area_bucket, deposit,
                         ROW_NUMBER() OVER (PARTITION BY complex_id, area_bucket ORDER BY deposit) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id, area_bucket)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ?
                )
                SELECT ?, complex_id, area_bucket,
                       CAST(AVG(deposit) AS SIGNED), MAX(cnt), 'L1',
                       CASE WHEN MAX(cnt) >= 10 THEN 'HIGH'
                            WHEN MAX(cnt) >= 3  THEN 'MEDIUM'
                            ELSE 'LOW' END
                FROM ranked
                WHERE %s
                GROUP BY complex_id, area_bucket
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, windowStart, snapshotDate);
    }

    /**
     * L2: 같은 단지 전체 평형의 ㎡당 단가 중위값 × 해당 평형.
     *
     * 같은 단지라도 평형이 다르면 절대 금액이 다르므로, 단가로 환산해야 빌려 쓸 수 있다.
     * L2는 상한이 MEDIUM이라 표본이 아무리 많아도 HIGH가 되지 않는다.
     */
    private int insertL2(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH target AS (
                  SELECT DISTINCT r.complex_id, r.area_bucket
                  FROM rent_deal r
                  WHERE r.monthly_rent = 0
                    AND NOT EXISTS (
                      SELECT 1 FROM complex_rent_stat s
                      WHERE s.snapshot_date = ? AND s.complex_id = r.complex_id
                        AND s.area_bucket = r.area_bucket)
                ),
                ranked AS (
                  SELECT complex_id, deposit / area AS unit_price,
                         ROW_NUMBER() OVER (PARTITION BY complex_id ORDER BY deposit / area) rn,
                         COUNT(*)     OVER (PARTITION BY complex_id)                          cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ? AND area > 0
                ),
                unit AS (
                  SELECT complex_id, AVG(unit_price) AS median_unit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s GROUP BY complex_id
                )
                SELECT ?, t.complex_id, t.area_bucket,
                       CAST(u.median_unit * t.area_bucket AS SIGNED), u.cnt, 'L2',
                       CASE WHEN u.cnt >= 3 THEN 'MEDIUM' ELSE 'LOW' END
                FROM target t JOIN unit u ON u.complex_id = t.complex_id
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, snapshotDate);
    }

    /** L3: 같은 법정동·평형. 남의 단지 시세를 빌려 쓰는 것이므로 상한이 LOW다. */
    private int insertL3(LocalDate snapshotDate, LocalDate windowStart) {
        String sql = """
                INSERT INTO complex_rent_stat
                  (snapshot_date, complex_id, area_bucket, median_deposit, sample_count,
                   source_level, confidence)
                WITH target AS (
                  SELECT DISTINCT r.complex_id, r.area_bucket, r.region_code, r.legal_dong
                  FROM rent_deal r
                  WHERE r.monthly_rent = 0
                    AND NOT EXISTS (
                      SELECT 1 FROM complex_rent_stat s
                      WHERE s.snapshot_date = ? AND s.complex_id = r.complex_id
                        AND s.area_bucket = r.area_bucket)
                ),
                ranked AS (
                  SELECT region_code, legal_dong, area_bucket, deposit,
                         ROW_NUMBER() OVER (
                           PARTITION BY region_code, legal_dong, area_bucket ORDER BY deposit) rn,
                         COUNT(*) OVER (
                           PARTITION BY region_code, legal_dong, area_bucket)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND deal_date >= ?
                ),
                dong AS (
                  SELECT region_code, legal_dong, area_bucket,
                         AVG(deposit) AS median_deposit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s
                  GROUP BY region_code, legal_dong, area_bucket
                )
                SELECT ?, t.complex_id, t.area_bucket,
                       CAST(d.median_deposit AS SIGNED), d.cnt, 'L3', 'LOW'
                FROM target t
                JOIN dong d ON d.region_code = t.region_code
                           AND d.legal_dong  = t.legal_dong
                           AND d.area_bucket = t.area_bucket
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, snapshotDate, windowStart, snapshotDate);
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*RentStatBuilderTest'`
Expected: PASS (9개 테스트)

`L1_표본이_없으면...` 테스트가 실패하면 L2의 `NOT EXISTS`가 L1 삽입 **이후** 상태를 보는지 확인한다. 세 계층은 반드시 순서대로 실행되어야 한다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 전세 시세 집계 및 confidence 폴백 계단 추가

MySQL 8에 MEDIAN이 없어 ROW_NUMBER 윈도 함수로 중위값을 만든다.
평균이 아니라 중위값을 쓰는 이유는 보증금이 소수의 이상 거래에 크게 끌려가기 때문이다.

L2는 같은 단지의 ㎡당 단가로 환산해 다른 평형 거래를 빌려 쓰고,
L3는 같은 법정동을 빌려 쓴다. 내려갈수록 confidence 상한이 함께 내려간다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 만기 예측과 역전세 갭

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/service/MaturityForecastBuilder.java`
- Create: `src/main/java/com/jeonseradar/metric/repository/MaturityForecastRepository.java`
- Test: `src/test/java/com/jeonseradar/metric/service/MaturityForecastBuilderTest.java`

**Interfaces:**
- Consumes: Task 1의 `MaturityForecast`, Task 2가 채운 `complex_rent_stat`
- Produces: `MaturityForecastBuilder.build(LocalDate snapshotDate, String fromYm) -> int`

**반드시 Task 2 다음에 실행된다.** 현재 시세(`current_median_deposit`)를 `complex_rent_stat`에서 조인해 오기 때문이다.

산식 (스펙 §3.4):

```
median_deposit_then      = 만기월이 m인 계약들의 보증금 중위값 (해당 단지·평형, monthly_rent = 0)
current_median_deposit   = complex_rent_stat의 median_deposit (Task 2가 폴백까지 적용한 값)
gap_amount               = median_deposit_then - current_median_deposit   (양수 = 역전세)
gap_rate                 = gap_amount / median_deposit_then
```

`median_deposit_then`은 과거 실제 체결가이므로 폴백이 필요 없다. 폴백은 현재 시세 쪽에만 적용되며, 그 결과가 이 행의 `confidence`를 결정한다.

`fromYm` 이전에 이미 만기가 지난 계약은 제외한다 — 지난 만기는 예측 대상이 아니다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/MaturityForecastBuilderTest.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MaturityForecastBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    MaturityForecastBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM maturity_forecast");
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM rent_deal");
    }

    /** 현재 시세를 직접 심는다. Task 2의 산출물을 흉내 내는 것이다. */
    private void currentStat(long complexId, int areaBucket, long median, String confidence) {
        jdbcTemplate.update(
                "INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,"
                        + " median_deposit, sample_count, source_level, confidence)"
                        + " VALUES (?,?,?,?,5,'L1',?)",
                SNAPSHOT, complexId, areaBucket, median, confidence);
    }

    /** 만기가 termEndYm인 순수 전세 한 건. */
    private void lease(long complexId, int areaBucket, long deposit, String termEndYm,
                       boolean estimated, boolean renewalUsed) {
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES (?,?,'11680-1','11680','대치동','1','단지',?,?,3,'2024-12-01',?,0,?,?,?,NOW(),NOW())",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                complexId, java.math.BigDecimal.valueOf(areaBucket), areaBucket,
                deposit, termEndYm, estimated, renewalUsed);
    }

    private Map<String, Object> forecastOf(long complexId, int areaBucket, String ym) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM maturity_forecast WHERE complex_id = ? AND area_bucket = ?"
                        + " AND maturity_ym = ?", complexId, areaBucket, ym);
    }

    @Test
    void 만기월별로_건수와_당시_보증금_중위값을_집계한다() {
        currentStat(1, 85, 400_000_000L, "HIGH");
        lease(1, 85, 500_000_000L, "202612", false, false);
        lease(1, 85, 600_000_000L, "202612", false, false);
        lease(1, 85, 700_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(1, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(3);
        assertThat(((Number) f.get("median_deposit_then")).longValue()).isEqualTo(600_000_000L);
    }

    @Test
    void 역전세면_갭이_양수다() {
        currentStat(2, 85, 400_000_000L, "HIGH");
        lease(2, 85, 600_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(2, 85, "202612");
        // 당시 6억 - 현재 4억 = 2억
        assertThat(((Number) f.get("gap_amount")).longValue()).isEqualTo(200_000_000L);
        assertThat(((Number) f.get("gap_rate")).doubleValue()).isCloseTo(
                0.3333, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void 시세가_올랐으면_갭이_음수다() {
        currentStat(3, 85, 700_000_000L, "HIGH");
        lease(3, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        assertThat(((Number) forecastOf(3, 85, "202612").get("gap_amount")).longValue())
                .isEqualTo(-200_000_000L);
    }

    @Test
    void 추정으로_만기를_잡은_건수를_따로_센다() {
        currentStat(4, 85, 400_000_000L, "HIGH");
        lease(4, 85, 500_000_000L, "202612", true, false);
        lease(4, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(4, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(2);
        assertThat(((Number) f.get("estimated_count")).intValue()).isEqualTo(1);
    }

    @Test
    void 갱신권을_이미_쓴_건수를_따로_센다() {
        currentStat(5, 85, 400_000_000L, "HIGH");
        lease(5, 85, 500_000_000L, "202612", false, true);
        lease(5, 85, 500_000_000L, "202612", false, true);
        lease(5, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Map<String, Object> f = forecastOf(5, 85, "202612");
        assertThat(((Number) f.get("contract_count")).intValue()).isEqualTo(3);
        assertThat(((Number) f.get("renewal_exhausted_count")).intValue()).isEqualTo(2);
    }

    @Test
    void 이미_지난_만기는_예측_대상이_아니다() {
        currentStat(6, 85, 400_000_000L, "HIGH");
        lease(6, 85, 500_000_000L, "202601", false, false);   // fromYm 이전
        lease(6, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT maturity_ym FROM maturity_forecast WHERE complex_id = 6");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("maturity_ym")).isEqualTo("202612");
    }

    @Test
    void confidence는_현재_시세_쪽에서_가져온다() {
        currentStat(7, 85, 400_000_000L, "LOW");
        lease(7, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        assertThat(forecastOf(7, 85, "202612").get("confidence")).isEqualTo(Confidence.LOW.name());
    }

    @Test
    void 현재_시세가_없으면_행을_만들지_않는다() {
        // Task 2가 어떤 이유로든 시세를 못 낸 (단지,평형)은 갭을 계산할 수 없다
        lease(8, 85, 500_000_000L, "202612", false, false);

        builder.build(SNAPSHOT, "202610");

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maturity_forecast WHERE complex_id = 8", Integer.class);
        assertThat(n).isZero();
    }

    @Test
    void 월세가_있는_계약은_만기_집계에서_제외된다() {
        currentStat(9, 85, 400_000_000L, "HIGH");
        lease(9, 85, 500_000_000L, "202612", false, false);
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES ('banj-9',9,'11680-9','11680','대치동','1','단지',85,85,3,"
                        + " '2024-12-01',100000000,800000,'202612',FALSE,FALSE,NOW(),NOW())");

        builder.build(SNAPSHOT, "202610");

        assertThat(((Number) forecastOf(9, 85, "202612").get("contract_count")).intValue())
                .isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*MaturityForecastBuilderTest'`
Expected: FAIL — `MaturityForecastBuilder` 없음

- [ ] **Step 3: Repository 작성**

`src/main/java/com/jeonseradar/metric/repository/MaturityForecastRepository.java`:

```java
package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.MaturityForecast;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MaturityForecastRepository extends JpaRepository<MaturityForecast, Long> {

    List<MaturityForecast> findBySnapshotDateAndComplexIdOrderByMaturityYm(
            LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
```

- [ ] **Step 4: MaturityForecastBuilder 작성**

`src/main/java/com/jeonseradar/metric/service/MaturityForecastBuilder.java`:

```java
package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 단지×평형×만기월 예측을 집계한다 (스펙 §3.2 / §3.4 / §3.6).
 *
 * 반드시 RentStatBuilder 다음에 실행된다 — 현재 시세를 complex_rent_stat에서 조인하기 때문이다.
 * 현재 시세가 없는 (단지,평형)은 갭을 계산할 수 없으므로 행을 만들지 않는다.
 *
 * 동/호가 공개되지 않아 개별 물건을 추적할 수 없으므로 모든 값은 집계 단위다 (스펙 §3.1).
 * "이 집이 역전세"가 아니라 "이 단지 이 평형에 만기가 N건 돌아오고 차액이 평균 얼마"다.
 */
@Service
public class MaturityForecastBuilder {

    private final JdbcTemplate jdbcTemplate;

    public MaturityForecastBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param snapshotDate 집계 결과에 찍을 스냅샷 날짜
     * @param fromYm       이 월부터의 만기만 예측한다 (yyyyMM). 지난 만기는 대상이 아니다.
     * @return 삽입된 행 수
     */
    @Transactional
    public int build(LocalDate snapshotDate, String fromYm) {
        String sql = """
                INSERT INTO maturity_forecast
                  (snapshot_date, complex_id, area_bucket, maturity_ym, contract_count,
                   estimated_count, median_deposit_then, current_median_deposit,
                   gap_amount, gap_rate, renewal_exhausted_count, confidence)
                WITH ranked AS (
                  SELECT complex_id, area_bucket, term_end_ym, deposit,
                         term_estimated, renewal_right_used,
                         ROW_NUMBER() OVER (
                           PARTITION BY complex_id, area_bucket, term_end_ym ORDER BY deposit) rn,
                         COUNT(*) OVER (
                           PARTITION BY complex_id, area_bucket, term_end_ym)                  cnt
                  FROM rent_deal
                  WHERE monthly_rent = 0
                    AND term_end_ym IS NOT NULL
                    AND term_end_ym >= ?
                ),
                median_then AS (
                  SELECT complex_id, area_bucket, term_end_ym,
                         AVG(deposit) AS median_deposit, MAX(cnt) AS cnt
                  FROM ranked WHERE %s
                  GROUP BY complex_id, area_bucket, term_end_ym
                ),
                counts AS (
                  SELECT complex_id, area_bucket, term_end_ym,
                         COUNT(*)                        AS contract_count,
                         SUM(term_estimated)             AS estimated_count,
                         SUM(renewal_right_used)         AS renewal_exhausted_count
                  FROM rent_deal
                  WHERE monthly_rent = 0 AND term_end_ym IS NOT NULL AND term_end_ym >= ?
                  GROUP BY complex_id, area_bucket, term_end_ym
                )
                SELECT ?, c.complex_id, c.area_bucket, c.term_end_ym,
                       c.contract_count, c.estimated_count,
                       CAST(m.median_deposit AS SIGNED),
                       s.median_deposit,
                       CAST(m.median_deposit AS SIGNED) - s.median_deposit,
                       (CAST(m.median_deposit AS SIGNED) - s.median_deposit) / m.median_deposit,
                       c.renewal_exhausted_count,
                       s.confidence
                FROM counts c
                JOIN median_then m ON m.complex_id = c.complex_id
                                  AND m.area_bucket = c.area_bucket
                                  AND m.term_end_ym = c.term_end_ym
                JOIN complex_rent_stat s ON s.snapshot_date = ?
                                        AND s.complex_id   = c.complex_id
                                        AND s.area_bucket  = c.area_bucket
                WHERE m.median_deposit > 0
                """.formatted(MedianSql.MIDDLE_ROWS);
        return jdbcTemplate.update(sql, fromYm, fromYm, snapshotDate, snapshotDate);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*MaturityForecastBuilderTest'`
Expected: PASS (9개 테스트)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 만기 예측 및 역전세 갭 집계 추가

당시 보증금은 과거 실제 체결가라 폴백이 필요 없고, 폴백은 현재 시세 쪽에만 적용된다.
그 결과가 예측 행의 confidence를 결정한다.

갱신권을 이미 쓴 건수를 따로 세는 이유는 그 계약이 다음 만기에 갱신권이 없어
실제로 시장에 나올 확률이 높기 때문이다 (스펙 §3.6).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 거래 검증 플래그

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/service/DealFlagBuilder.java`
- Create: `src/main/java/com/jeonseradar/metric/repository/DealFlagRepository.java`
- Test: `src/test/java/com/jeonseradar/metric/service/DealFlagBuilderTest.java`

**Interfaces:**
- Consumes: Task 1의 `DealFlag`, `FlagType`
- Produces: `DealFlagBuilder.build(LocalDate snapshotDate, LocalDate asOf) -> int`

판정 규칙 (스펙 §3.7). **사실만 담고 판단을 담지 않는다.**

| flag_type | 조건 |
|-----------|------|
| CANCELED | `cancel_yn = 'O'` |
| UNREGISTERED | `registered_date IS NULL` **AND** `계약일 + 60일 < asOf` **AND** `cancel_yn != 'O'` |
| DIRECT | `deal_type = '직거래'` |

60일 조건이 핵심이다. 잔금과 등기에 시간이 걸리므로 **최근 거래에 등기일자가 없는 것은 정상**이며, 이를 플래그하면 멀쩡한 거래를 의심스럽게 보이게 만든다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/DealFlagBuilderTest.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.FlagType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DealFlagBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 22);

    @Autowired
    DealFlagBuilder builder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM deal_flag");
        jdbcTemplate.update("DELETE FROM trade_deal");
    }

    private void trade(String hash, LocalDate dealDate, LocalDate registeredDate,
                       String cancelYn, String dealType) {
        jdbcTemplate.update(
                "INSERT INTO trade_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deal_amount,"
                        + " deal_type, registered_date, cancel_yn, created_at, updated_at)"
                        + " VALUES (?,1,'11680-1','11680','대치동','1','단지',85,85,3,?,1000000000,"
                        + " ?,?,?,NOW(),NOW())",
                hash, dealDate, dealType, registeredDate, cancelYn);
    }

    private List<String> flagsOf(String hash) {
        return jdbcTemplate.queryForList(
                "SELECT flag_type FROM deal_flag WHERE deal_hash = ? ORDER BY flag_type",
                String.class, hash);
    }

    @Test
    void 해제된_거래는_CANCELED다() {
        trade("h1", LocalDate.of(2026, 3, 1), null, "O", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h1")).contains(FlagType.CANCELED.name());
    }

    @Test
    void 계약_후_60일이_지나고_등기가_없으면_UNREGISTERED다() {
        trade("h2", LocalDate.of(2026, 3, 1), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h2")).contains(FlagType.UNREGISTERED.name());
    }

    @Test
    void 계약_후_60일이_안_지났으면_등기가_없어도_플래그하지_않는다() {
        // 잔금과 등기에 시간이 걸리므로 최근 거래에 등기가 없는 것은 정상이다
        trade("h3", LocalDate.of(2026, 9, 1), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h3")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 정확히_60일째는_아직_플래그하지_않는다() {
        trade("h4", AS_OF.minusDays(60), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h4")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 계약_후_61일째부터_플래그한다() {
        trade("h5", AS_OF.minusDays(61), null, "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h5")).contains(FlagType.UNREGISTERED.name());
    }

    @Test
    void 등기가_있으면_UNREGISTERED가_아니다() {
        trade("h6", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h6")).doesNotContain(FlagType.UNREGISTERED.name());
    }

    @Test
    void 해제된_거래는_등기가_없어도_UNREGISTERED가_아니다() {
        // 해제됐으면 등기가 없는 것이 당연하다. 두 번 낙인찍지 않는다.
        trade("h7", LocalDate.of(2026, 3, 1), null, "O", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h7")).containsExactly(FlagType.CANCELED.name());
    }

    @Test
    void 직거래는_DIRECT다() {
        trade("h8", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "직거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h8")).containsExactly(FlagType.DIRECT.name());
    }

    @Test
    void 중개거래는_DIRECT가_아니다() {
        trade("h9", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1), "N", "중개거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h9")).isEmpty();
    }

    @Test
    void 한_거래에_여러_플래그가_붙을_수_있다() {
        trade("h10", LocalDate.of(2026, 3, 1), null, "N", "직거래");

        builder.build(SNAPSHOT, AS_OF);

        assertThat(flagsOf("h10"))
                .containsExactly(FlagType.DIRECT.name(), FlagType.UNREGISTERED.name());
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*DealFlagBuilderTest'`
Expected: FAIL — `DealFlagBuilder` 없음

- [ ] **Step 3: Repository 작성**

`src/main/java/com/jeonseradar/metric/repository/DealFlagRepository.java`:

```java
package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.DealFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DealFlagRepository extends JpaRepository<DealFlag, Long> {

    List<DealFlag> findBySnapshotDateAndComplexId(LocalDate snapshotDate, Long complexId);

    long countBySnapshotDate(LocalDate snapshotDate);
}
```

- [ ] **Step 4: DealFlagBuilder 작성**

`src/main/java/com/jeonseradar/metric/service/DealFlagBuilder.java`:

```java
package com.jeonseradar.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 매매 거래 검증 플래그를 집계한다 (스펙 §3.7).
 *
 * 사실만 담는다. "자전거래 의심" 같은 판단은 만들지 않으며, 신뢰도 점수도 매기지 않는다.
 * 화면에는 "신고 후 해제된 거래", "등기 미확인(계약 후 191일 경과)"처럼 사실로만 표기한다.
 */
@Service
public class DealFlagBuilder {

    /** 잔금과 등기에 걸리는 기간. 이보다 최근 거래에 등기가 없는 것은 정상이다. */
    private static final int REGISTRATION_GRACE_DAYS = 60;

    private final JdbcTemplate jdbcTemplate;

    public DealFlagBuilder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int build(LocalDate snapshotDate, LocalDate asOf) {
        int n = insertCanceled(snapshotDate);
        n += insertUnregistered(snapshotDate, asOf);
        n += insertDirect(snapshotDate);
        return n;
    }

    private int insertCanceled(LocalDate snapshotDate) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'CANCELED', NOW()
                FROM trade_deal WHERE cancel_yn = 'O'
                """, snapshotDate);
    }

    /**
     * 해제된 거래는 제외한다 — 해제됐으면 등기가 없는 것이 당연하므로
     * 같은 사실에 두 번 낙인을 찍지 않는다.
     */
    private int insertUnregistered(LocalDate snapshotDate, LocalDate asOf) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'UNREGISTERED', NOW()
                FROM trade_deal
                WHERE registered_date IS NULL
                  AND (cancel_yn IS NULL OR cancel_yn <> 'O')
                  AND DATE_ADD(deal_date, INTERVAL ? DAY) < ?
                """, snapshotDate, REGISTRATION_GRACE_DAYS, asOf);
    }

    private int insertDirect(LocalDate snapshotDate) {
        return jdbcTemplate.update("""
                INSERT INTO deal_flag (snapshot_date, deal_hash, complex_id, flag_type, detected_at)
                SELECT ?, deal_hash, complex_id, 'DIRECT', NOW()
                FROM trade_deal WHERE deal_type = '직거래'
                """, snapshotDate);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*DealFlagBuilderTest'`
Expected: PASS (10개 테스트)

`정확히_60일째는...`과 `61일째부터...`가 함께 통과해야 한다. 한쪽만 통과하면 부등호가 틀린 것이다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 매매 거래 검증 플래그 집계 추가

사실만 담고 판단을 담지 않는다. 신뢰도 점수를 만들지 않는 이유는
임의 가중치로 뽑은 숫자가 근거가 없고 특정 거래를 낙인찍기 때문이다.

등기 미확인은 계약 후 60일이 지난 건만 판정한다. 잔금과 등기에 시간이 걸리므로
최근 거래에 등기가 없는 것은 정상이며, 이를 플래그하면 멀쩡한 거래가 의심스러워 보인다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 스냅샷 오케스트레이션과 교체 의미론

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/repository/MetricSnapshotRepository.java`
- Create: `src/main/java/com/jeonseradar/metric/service/SnapshotBuilder.java`
- Test: `src/test/java/com/jeonseradar/metric/service/SnapshotBuilderTest.java`

**Interfaces:**
- Consumes: Task 2 `RentStatBuilder`, Task 3 `MaturityForecastBuilder`, Task 4 `DealFlagBuilder`
- Produces:
  - `SnapshotBuilder.build(LocalDate asOf) -> MetricSnapshot`
  - `MetricSnapshotRepository.findLatestComplete() -> Optional<MetricSnapshot>`

**이 태스크가 지키는 것은 하나다: 빌드 중간 상태가 화면에 새지 않는 것** (스펙 §1.7).

집계 테이블에 `snapshot_date`만 두면 "이 스냅샷이 다 만들어졌는지" 알 수 없다. 조회 계층은 반드시 `metric_snapshot`에서 **`COMPLETE`인 최신 스냅샷**을 먼저 찾고, 그 `snapshot_date`로만 집계 테이블을 읽는다. 빌드가 실패하면 직전 완료 스냅샷이 계속 서비스된다.

실행 순서는 고정이다: **시세 → 만기 → 플래그**. 만기가 시세를 조인하므로 뒤집을 수 없다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/SnapshotBuilderTest.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.entity.SnapshotStatus;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SnapshotBuilderTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    SnapshotBuilder builder;

    @Autowired
    MetricSnapshotRepository snapshotRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM maturity_forecast");
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM deal_flag");
        jdbcTemplate.update("DELETE FROM metric_snapshot");
        jdbcTemplate.update("DELETE FROM rent_deal");
        jdbcTemplate.update("DELETE FROM trade_deal");
        jdbcTemplate.update("DELETE FROM complex");
    }

    private void seedOneComplexWithLease() {
        jdbcTemplate.update(
                "INSERT INTO complex (id, region_code, apt_seq, legal_dong, jibun, display_name, slug)"
                        + " VALUES (1,'11680','11680-1','대치동','1','단지','slug-1')");
        jdbcTemplate.update(
                "INSERT INTO rent_deal (deal_hash, complex_id, apt_seq, region_code, legal_dong,"
                        + " jibun, apt_name, area, area_bucket, floor, deal_date, deposit,"
                        + " monthly_rent, term_end_ym, term_estimated, renewal_right_used,"
                        + " created_at, updated_at)"
                        + " VALUES ('seed-1',1,'11680-1','11680','대치동','1','단지',85,85,3,"
                        + " ?,500000000,0,'202812',FALSE,FALSE,NOW(),NOW())",
                LocalDate.now().minusMonths(1));
    }

    @Test
    void 빌드가_끝나면_스냅샷이_COMPLETE로_표시된다() {
        seedOneComplexWithLease();

        MetricSnapshot s = builder.build(LocalDate.now());

        assertThat(s.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
        assertThat(s.getCompletedAt()).isNotNull();
    }

    @Test
    void 완료된_스냅샷만_조회_대상이_된다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());

        // 빌드 중인 스냅샷을 직접 심는다 — 더 최신이지만 COMPLETE가 아니다
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at)"
                        + " VALUES (?, 'BUILDING', NOW())", LocalDate.now().plusDays(1));

        MetricSnapshot latest = snapshotRepository.findLatestComplete().orElseThrow();

        assertThat(latest.getSnapshotDate()).isEqualTo(LocalDate.now());
        assertThat(latest.getStatus()).isEqualTo(SnapshotStatus.COMPLETE);
    }

    @Test
    void 실패한_스냅샷도_조회_대상이_아니다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());

        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, failure_reason)"
                        + " VALUES (?, 'FAILED', NOW(), '테스트')", LocalDate.now().plusDays(1));

        assertThat(snapshotRepository.findLatestComplete().orElseThrow().getSnapshotDate())
                .isEqualTo(LocalDate.now());
    }

    @Test
    void 완료된_스냅샷이_하나도_없으면_비어있다() {
        assertThat(snapshotRepository.findLatestComplete()).isEmpty();
    }

    @Test
    void 스냅샷에_각_테이블의_행수가_기록된다() {
        seedOneComplexWithLease();

        MetricSnapshot s = builder.build(LocalDate.now());

        assertThat(s.getRentStatRows()).isEqualTo(1);
        assertThat(s.getMaturityRows()).isEqualTo(1);
        assertThat(s.getDealFlagRows()).isZero();
    }

    @Test
    void 같은_날_다시_빌드하면_이전_행을_지우고_다시_만든다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());
        builder.build(LocalDate.now());

        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE snapshot_date = ?",
                Integer.class, LocalDate.now());

        assertThat(n).isEqualTo(1);
    }

    @Test
    void 빌드가_실패하면_FAILED_기록이_남는다() {
        // 존재하지 않는 컬럼을 참조하게 만들 수는 없으므로, 시세가 없는 상태에서
        // 만기 집계만 돌려도 예외는 나지 않는다. 대신 rent_deal에 complex_id가
        // FK를 깨는 값을 넣어 빌드 중 예외를 유도한다.
        jdbcTemplate.update("DROP TABLE IF EXISTS complex_rent_stat_backup");
        jdbcTemplate.execute("RENAME TABLE complex_rent_stat TO complex_rent_stat_backup");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(LocalDate.now()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("RENAME TABLE complex_rent_stat_backup TO complex_rent_stat");
        }

        // 핵심: 예외가 다시 던져졌어도 FAILED 기록이 살아남아야 한다.
        // 오케스트레이터가 @Transactional이면 이 기록이 함께 롤백되어 사라진다.
        MetricSnapshot s = snapshotRepository.findBySnapshotDate(LocalDate.now()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SnapshotStatus.FAILED);
        assertThat(s.getFailureReason()).isNotBlank();
    }

    @Test
    void 실패한_스냅샷은_조회_대상에서_빠진다() {
        assertThat(snapshotRepository.findLatestComplete()).isEmpty();
    }

    @Test
    void 같은_날_재빌드해도_스냅샷_행이_늘지_않는다() {
        seedOneComplexWithLease();
        builder.build(LocalDate.now());
        builder.build(LocalDate.now());

        assertThat(snapshotRepository.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*SnapshotBuilderTest'`
Expected: FAIL — `SnapshotBuilder`, `MetricSnapshotRepository` 없음

- [ ] **Step 3: Repository 작성**

`src/main/java/com/jeonseradar/metric/repository/MetricSnapshotRepository.java`:

```java
package com.jeonseradar.metric.repository;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.entity.SnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    Optional<MetricSnapshot> findBySnapshotDate(LocalDate snapshotDate);

    /**
     * 조회 계층이 쓰는 단 하나의 진입점.
     * BUILDING이나 FAILED는 더 최신이어도 무시된다 — 반쪽짜리 집계가 화면에 새지 않게 한다.
     *
     * JPQL의 LIMIT은 Hibernate 버전에 따라 지원이 갈리므로 파생 쿼리로 쓴다.
     */
    default Optional<MetricSnapshot> findLatestComplete() {
        return findFirstByStatusOrderBySnapshotDateDesc(SnapshotStatus.COMPLETE);
    }

    Optional<MetricSnapshot> findFirstByStatusOrderBySnapshotDateDesc(SnapshotStatus status);

    long countByStatus(SnapshotStatus status);
}
```

- [ ] **Step 4: SnapshotBuilder 작성**

`src/main/java/com/jeonseradar/metric/service/SnapshotBuilder.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 집계 스냅샷 하나를 처음부터 끝까지 만든다.
 *
 * 실행 순서는 고정이다: 시세 -> 만기 -> 플래그.
 * 만기 예측이 현재 시세를 조인하므로 뒤집을 수 없다.
 *
 * 빌드가 실패하면 스냅샷을 FAILED로 표시하고 예외를 다시 던진다.
 * 조회 계층은 COMPLETE만 보므로 직전 스냅샷이 계속 서비스된다 (스펙 §1.7).
 */
@Service
public class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final RentStatBuilder rentStatBuilder;
    private final MaturityForecastBuilder maturityForecastBuilder;
    private final DealFlagBuilder dealFlagBuilder;
    private final MetricSnapshotRepository snapshotRepository;
    private final JdbcTemplate jdbcTemplate;

    public SnapshotBuilder(RentStatBuilder rentStatBuilder,
                           MaturityForecastBuilder maturityForecastBuilder,
                           DealFlagBuilder dealFlagBuilder,
                           MetricSnapshotRepository snapshotRepository,
                           JdbcTemplate jdbcTemplate) {
        this.rentStatBuilder = rentStatBuilder;
        this.maturityForecastBuilder = maturityForecastBuilder;
        this.dealFlagBuilder = dealFlagBuilder;
        this.snapshotRepository = snapshotRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * **의도적으로 @Transactional을 붙이지 않는다.** 두 가지 이유가 있다.
     *
     * 첫째, 실패 기록이 살아남아야 한다. 하나의 트랜잭션으로 감싸면 catch 블록의
     * snapshot.fail() + save()가 곧바로 던지는 예외에 함께 롤백되어, 실패 원인을
     * 남기려고 쓴 코드가 스스로를 지운다.
     *
     * 둘째, 전국 규모에서는 수백만 행 삽입을 한 트랜잭션이 감싸게 되어 언두 로그가
     * 부풀고 빌드 내내 락을 쥔다.
     *
     * 원자성은 트랜잭션이 아니라 metric_snapshot의 status가 보장한다. 중간에 실패하면
     * 그 snapshot_date의 행이 일부 남지만 COMPLETE가 아니므로 아무도 읽지 않고,
     * 다음 빌드의 clearExisting이 지운다 (스펙 §1.7).
     */
    public MetricSnapshot build(LocalDate asOf) {
        LocalDate snapshotDate = asOf;
        MetricSnapshot snapshot = snapshotRepository.findBySnapshotDate(snapshotDate)
                .orElseGet(() -> MetricSnapshot.start(snapshotDate));

        // 재빌드도 BUILDING으로 되돌린 뒤 저장한다. 이걸 빼면 이미 COMPLETE인 날짜를
        // 재빌드하는 동안 clearExisting이 그 날짜 집계 행을 지우는데도 스냅샷은 COMPLETE로
        // 남아, 독자가 빈 테이블을 읽는다. 되돌려 두면 그 창 동안 findLatestComplete()가
        // 오늘을 건너뛰고 어제 스냅샷을 계속 서빙한다 (원장 F-33).
        snapshot.restart();
        snapshotRepository.save(snapshot);

        try {
            // 정리도 try 안에 있어야 한다. 밖에 두면 clearExisting이 던지는 예외가
            // catch를 건너뛰어 FAILED 기록이 남지 않는다 (원장 F-32).
            clearExisting(snapshotDate);

            int rentStatRows = rentStatBuilder.build(snapshotDate, asOf);
            int maturityRows = maturityForecastBuilder.build(
                    snapshotDate, YearMonth.from(asOf).format(YM));
            int dealFlagRows = dealFlagBuilder.build(snapshotDate, asOf);

            snapshot.complete(rentStatRows, maturityRows, dealFlagRows);
            log.info("집계 스냅샷 완료 {}: 시세 {}행, 만기 {}행, 플래그 {}행",
                    snapshotDate, rentStatRows, maturityRows, dealFlagRows);
        } catch (RuntimeException e) {
            snapshot.fail(e.toString());
            snapshotRepository.save(snapshot);
            log.error("집계 스냅샷 실패 {}: {}", snapshotDate, e.toString());
            throw e;
        }

        return snapshotRepository.save(snapshot);
    }

    /** 같은 날 재빌드하면 이전 결과를 지우고 다시 만든다. 부분 중복을 남기지 않는다. */
    private void clearExisting(LocalDate snapshotDate) {
        jdbcTemplate.update("DELETE FROM maturity_forecast WHERE snapshot_date = ?", snapshotDate);
        jdbcTemplate.update("DELETE FROM complex_rent_stat WHERE snapshot_date = ?", snapshotDate);
        jdbcTemplate.update("DELETE FROM deal_flag WHERE snapshot_date = ?", snapshotDate);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*SnapshotBuilderTest'`
Expected: PASS (9개 테스트)

`빌드가_실패하면_FAILED_기록이_남는다`가 실패하면 `SnapshotBuilder.build()`에
`@Transactional`이 붙어 있는지부터 확인한다. 붙어 있으면 실패 기록이 함께 롤백된다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 집계 스냅샷 오케스트레이션 및 완료 표시 추가

조회 계층은 COMPLETE인 최신 스냅샷만 본다. 빌드가 실패하면 직전 스냅샷이
계속 서비스되므로 반쪽짜리 집계가 화면에 새지 않는다.

실행 순서는 시세 -> 만기 -> 플래그로 고정된다. 만기가 현재 시세를 조인하기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 야간 집계 스케줄러

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/service/SnapshotScheduler.java`
- Create: `src/main/java/com/jeonseradar/metric/service/SnapshotCommand.java`
- Test: `src/test/java/com/jeonseradar/metric/service/SnapshotSchedulerTest.java`

**Interfaces:**
- Consumes: Task 5 `SnapshotBuilder`
- Produces: `SnapshotScheduler.nightlyBuild()` (cron 04:00 KST), `SnapshotCommand` (`--build-metrics`)

수집은 02:00, 집계는 04:00이다 (스펙 §4.7). 두 시간 간격을 두는 이유는 수집이 끝난 데이터를 집계해야 하기 때문이다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/SnapshotSchedulerTest.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.entity.MetricSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SnapshotSchedulerTest {

    private final SnapshotBuilder builder = mock(SnapshotBuilder.class);
    private final SnapshotScheduler scheduler = new SnapshotScheduler(builder);

    @Test
    void 야간_배치는_오늘_날짜로_스냅샷을_만든다() {
        when(builder.build(any())).thenReturn(mock(MetricSnapshot.class));

        scheduler.nightlyBuild();

        verify(builder, times(1)).build(LocalDate.now());
    }

    @Test
    void 빌드가_실패해도_스케줄러가_예외를_밖으로_던지지_않는다() {
        // 예외가 스케줄러 밖으로 나가면 이후 실행이 멈추는 구현이 있어 방어한다
        when(builder.build(any())).thenThrow(new RuntimeException("집계 실패"));

        scheduler.nightlyBuild();

        verify(builder, times(1)).build(any());
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*SnapshotSchedulerTest'`
Expected: FAIL — `SnapshotScheduler` 없음

- [ ] **Step 3: 스케줄러와 수동 진입점 작성**

`src/main/java/com/jeonseradar/metric/service/SnapshotScheduler.java`:

```java
package com.jeonseradar.metric.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 매일 04:00 KST 집계. 수집이 02:00이므로 두 시간 뒤에 돈다 (스펙 §4.7).
 *
 * 예외를 밖으로 던지지 않는다 — 스케줄러 스레드로 예외가 빠져나가면
 * 이후 실행이 조용히 멈추는 경우가 있다. 실패는 스냅샷이 FAILED로 남고
 * 직전 COMPLETE가 계속 서비스되므로, 다음 날 다시 시도하는 것으로 충분하다.
 */
@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final SnapshotBuilder builder;

    public SnapshotScheduler(SnapshotBuilder builder) {
        this.builder = builder;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void nightlyBuild() {
        log.info("야간 집계 시작");
        try {
            builder.build(LocalDate.now());
        } catch (RuntimeException e) {
            log.error("야간 집계 실패. 직전 스냅샷이 계속 서비스된다.", e);
        }
    }
}
```

`src/main/java/com/jeonseradar/metric/service/SnapshotCommand.java`:

```java
package com.jeonseradar.metric.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** `--build-metrics` 인자로 실행할 때만 집계한다. 평소에는 아무 일도 하지 않는다. */
@Component
public class SnapshotCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCommand.class);

    private final SnapshotBuilder builder;

    public SnapshotCommand(SnapshotBuilder builder) {
        this.builder = builder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("build-metrics")) {
            return;
        }
        var snapshot = builder.build(LocalDate.now());
        log.info("수동 집계 완료: {} 시세 {}행 만기 {}행 플래그 {}행",
                snapshot.getSnapshotDate(), snapshot.getRentStatRows(),
                snapshot.getMaturityRows(), snapshot.getDealFlagRows());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*SnapshotSchedulerTest'`
Expected: PASS (2개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric
git commit -m "$(cat <<'EOF'
feat: 야간 집계 스케줄러 및 수동 진입점 추가

스케줄러는 예외를 밖으로 던지지 않는다. 스케줄러 스레드로 예외가 빠져나가면
이후 실행이 조용히 멈추는 경우가 있고, 실패는 직전 스냅샷이 계속 서비스되므로
다음 날 재시도로 충분하다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 발행 가능 페이지 실측 — 3단계 진입 게이트

**Files:**
- Create: `src/main/java/com/jeonseradar/metric/service/CoverageReporter.java`
- Test: `src/test/java/com/jeonseradar/metric/service/CoverageReporterTest.java`
- Create: `docs/superpowers/reports/YYYY-MM-DD-phase2-coverage.md`

**Interfaces:**
- Consumes: Task 5가 만든 완료 스냅샷
- Produces: `CoverageReporter.report() -> CoverageReport`
  - `record CoverageReport(long totalComplexes, long indexable, double indexableRatio, long maturityRows, long lowConfidenceRows, long flaggedDeals)`

**이 태스크가 2단계의 종료 조건이자 3단계 착수 판단 근거다.**

3단계는 단지 페이지를 대량 발행해 검색 유입을 만드는 것이 전부인데, 스펙 §4.1이 **`confidence = LOW`면 `noindex`**라고 정해 두었다. 그러면 실제로 색인 가능한 페이지가 몇 장인지가 3단계의 성패를 좌우한다.

집계 6.3% 시점의 사전 실측에서는 **단지 단위 LOW가 41.4%** 였다. 게다가 그 표본은 시군구 코드 순서상 서울에 쏠려 거래가 많은 쪽이 과대표집됐으므로, **전국 전수에서는 LOW 비율이 더 오를 것으로 예상된다.**

페이지를 다 만든 뒤에 "절반이 색인에서 빠진다"를 발견하면 3단계 설계를 되돌려야 한다. 그래서 만들기 전에 잰다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/metric/service/CoverageReporterTest.java`:

```java
package com.jeonseradar.metric.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CoverageReporterTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired
    CoverageReporter reporter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM complex_rent_stat");
        jdbcTemplate.update("DELETE FROM maturity_forecast");
        jdbcTemplate.update("DELETE FROM deal_flag");
        jdbcTemplate.update("DELETE FROM metric_snapshot");
        jdbcTemplate.update(
                "INSERT INTO metric_snapshot (snapshot_date, status, started_at, completed_at)"
                        + " VALUES (?, 'COMPLETE', NOW(), NOW())", SNAPSHOT);
    }

    private void stat(long complexId, int bucket, String confidence) {
        jdbcTemplate.update(
                "INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,"
                        + " median_deposit, sample_count, source_level, confidence)"
                        + " VALUES (?,?,?,500000000,5,'L1',?)",
                SNAPSHOT, complexId, bucket, confidence);
    }

    @Test
    void 단지에_LOW가_아닌_평형이_하나라도_있으면_색인_가능이다() {
        stat(1, 85, "LOW");
        stat(1, 60, "HIGH");

        assertThat(reporter.report().indexable()).isEqualTo(1);
    }

    @Test
    void 모든_평형이_LOW인_단지는_색인_불가다() {
        stat(2, 85, "LOW");
        stat(2, 60, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(1);
        assertThat(r.indexable()).isZero();
    }

    @Test
    void 색인_가능_비율을_계산한다() {
        stat(3, 85, "HIGH");
        stat(4, 85, "MEDIUM");
        stat(5, 85, "LOW");
        stat(6, 85, "LOW");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isEqualTo(4);
        assertThat(r.indexable()).isEqualTo(2);
        assertThat(r.indexableRatio()).isEqualTo(0.5);
    }

    @Test
    void 완료된_스냅샷이_없으면_전부_0이다() {
        jdbcTemplate.update("DELETE FROM metric_snapshot");

        var r = reporter.report();
        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }

    @Test
    void 단지가_하나도_없으면_비율이_0이고_0으로_나누지_않는다() {
        var r = reporter.report();

        assertThat(r.totalComplexes()).isZero();
        assertThat(r.indexableRatio()).isZero();
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*CoverageReporterTest'`
Expected: FAIL — `CoverageReporter` 없음

- [ ] **Step 3: CoverageReporter 작성**

`src/main/java/com/jeonseradar/metric/service/CoverageReporter.java`:

```java
package com.jeonseradar.metric.service;

import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 3단계가 실제로 발행할 수 있는 페이지가 몇 장인지 잰다.
 *
 * 스펙 §4.1이 confidence = LOW면 noindex라고 정했으므로, 색인 가능한 단지 수가
 * 곧 3단계가 만들 수 있는 검색 유입의 상한이다. 페이지를 다 만든 뒤에
 * "절반이 색인에서 빠진다"를 발견하면 설계를 되돌려야 하므로 만들기 전에 잰다.
 *
 * 단지 단위 판정 기준: 그 단지의 평형 중 LOW가 아닌 것이 하나라도 있으면 색인 가능.
 * 단지 페이지는 여러 평형을 함께 보여주므로, 쓸 만한 평형이 하나라도 있으면
 * 페이지에 담을 내용이 있다.
 */
@Service
public class CoverageReporter {

    private static final Logger log = LoggerFactory.getLogger(CoverageReporter.class);

    public record CoverageReport(
            long totalComplexes,
            long indexable,
            double indexableRatio,
            long maturityRows,
            long lowConfidenceRows,
            long flaggedDeals
    ) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MetricSnapshotRepository snapshotRepository;

    public CoverageReporter(JdbcTemplate jdbcTemplate,
                            MetricSnapshotRepository snapshotRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
    }

    public CoverageReport report() {
        var snapshot = snapshotRepository.findLatestComplete().orElse(null);
        if (snapshot == null) {
            log.warn("완료된 집계 스냅샷이 없어 커버리지를 계산할 수 없다");
            return new CoverageReport(0, 0, 0.0, 0, 0, 0);
        }
        LocalDate d = snapshot.getSnapshotDate();

        long total = count(
                "SELECT COUNT(DISTINCT complex_id) FROM complex_rent_stat WHERE snapshot_date = ?", d);
        long indexable = count(
                "SELECT COUNT(DISTINCT complex_id) FROM complex_rent_stat "
                        + "WHERE snapshot_date = ? AND confidence <> 'LOW'", d);
        long maturityRows = count(
                "SELECT COUNT(*) FROM maturity_forecast WHERE snapshot_date = ?", d);
        long lowRows = count(
                "SELECT COUNT(*) FROM complex_rent_stat WHERE snapshot_date = ? AND confidence = 'LOW'", d);
        long flagged = count(
                "SELECT COUNT(DISTINCT deal_hash) FROM deal_flag WHERE snapshot_date = ?", d);

        double ratio = total == 0 ? 0.0 : (double) indexable / total;
        CoverageReport report = new CoverageReport(
                total, indexable, ratio, maturityRows, lowRows, flagged);
        log.info("발행 커버리지 ({}): {}", d, report);
        return report;
    }

    private long count(String sql, LocalDate snapshotDate) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, snapshotDate);
        return v == null ? 0L : v;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*CoverageReporterTest'`
Expected: PASS (5개 테스트)

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS (2단계 이전 115개 + 2단계 신규 42개 내외)

- [ ] **Step 6: 실데이터로 집계 실행**

로컬 MySQL에 1단계 백필 데이터가 있어야 한다.

```bash
cd /Users/hoon/dev/jeonse-radar
docker compose up -d --wait
./gradlew bootRun --args='--build-metrics --spring.main.web-application-type=none'
```

- [ ] **Step 7: 커버리지 실측 및 리포트 작성**

```bash
docker exec jeonse-mysql mysql --default-character-set=utf8mb4 -ujeonse -pjeonse jeonse -e "
SELECT snapshot_date, status, rent_stat_rows, maturity_rows, deal_flag_rows FROM metric_snapshot;

SELECT confidence, COUNT(*) rows_, ROUND(COUNT(*)/SUM(COUNT(*)) OVER ()*100,1) pct
FROM complex_rent_stat GROUP BY confidence ORDER BY rows_ DESC;

SELECT source_level, COUNT(*) rows_, ROUND(COUNT(*)/SUM(COUNT(*)) OVER ()*100,1) pct
FROM complex_rent_stat GROUP BY source_level ORDER BY rows_ DESC;

SELECT COUNT(DISTINCT complex_id) total_complexes,
       COUNT(DISTINCT CASE WHEN confidence <> 'LOW' THEN complex_id END) indexable
FROM complex_rent_stat;

SELECT maturity_ym, SUM(contract_count) contracts, SUM(renewal_exhausted_count) renewal_used,
       SUM(CASE WHEN gap_amount > 0 THEN 1 ELSE 0 END) negative_gap_buckets
FROM maturity_forecast GROUP BY maturity_ym ORDER BY maturity_ym LIMIT 12;

SELECT flag_type, COUNT(*) FROM deal_flag GROUP BY flag_type;
"
```

결과를 `docs/superpowers/reports/<실행일>-phase2-coverage.md`에 기록하고, 문서 첫 줄에 **3단계 착수 판정**을 한 문장으로 적는다.

판정 기준:

- **색인 가능 비율 ≥ 50%** → 3단계 진행. 스펙 §4.1의 noindex 게이트를 그대로 간다.
- **30~50%** → 3단계는 진행하되, 지역 페이지(`/역전세/{시도}/{시군구}`)를 단지 페이지보다 앞세운다. 지역 페이지는 여러 단지를 합치므로 표본이 모여 색인 가능성이 높다.
- **< 30%** → **3단계 설계를 재검토한다.** 단지 페이지 대량 발행 전략 자체가 성립하지 않으므로, 지역 단위로 입도를 올리거나 confidence 기준을 재검토해야 한다.

문서에는 반드시 다음을 함께 적는다:

- 집계 시점의 백필 완료율 (전국 전수가 아니면 그 사실)
- `deal_hash` 충돌로 **모든 건수 지표가 약 2% 과소 집계**된다는 점 (스펙 §1.3 실측 1.7~2.2%)
- 지역 편중이 있으면 그 사실 (시군구 코드 순서상 서울부터 적재되므로 부분 백필은 서울에 쏠린다)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jeonseradar/metric src/test/java/com/jeonseradar/metric docs/superpowers/reports
git commit -m "$(cat <<'EOF'
feat: 발행 커버리지 리포터 추가 및 3단계 진입 게이트 실측

confidence = LOW가 noindex이므로 색인 가능한 단지 수가 3단계 검색 유입의 상한이다.
페이지를 다 만든 뒤에 절반이 색인에서 빠지는 것을 발견하면 설계를 되돌려야 하므로
만들기 전에 잰다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## 2단계 완료 조건

- [ ] `./gradlew test` 전체 통과
- [ ] `--build-metrics`로 실데이터 집계가 완료되고 `metric_snapshot`에 COMPLETE 한 건이 남음
- [ ] 같은 날 두 번 집계해도 집계 테이블 행 수가 늘지 않음
- [ ] `docs/superpowers/reports/<실행일>-phase2-coverage.md`에 색인 가능 비율과 3단계 착수 판정이 기록됨

3단계 계획은 이 리포트의 색인 가능 비율을 근거로 작성한다. 비율이 30% 미만이면 3단계 계획을 쓰기 전에 스펙 §4를 먼저 재검토한다.
