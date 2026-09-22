# 3단계 공개 페이지 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 집계된 스냅샷을 읽어 단지 페이지와 지역 페이지를 Thymeleaf SSR로 발행하고, 신뢰도가 낮은 페이지는 색인에서 제외한다.

**Architecture:** 모든 읽기는 `metric_snapshot`에서 최신 `COMPLETE` 스냅샷 날짜를 해석하는 단일 진입점(`PublishedSnapshot`)을 거친다. 컨트롤러는 JdbcTemplate 기반 조회 서비스가 만든 불변 읽기 모델(record)만 받고, 템플릿은 그 record만 읽는다. 엔티티를 뷰까지 끌고 가지 않는다.

**Tech Stack:** Spring Boot 3.4.x, JDK 21, Thymeleaf, JdbcTemplate, MySQL 8.0, JUnit 5 + Testcontainers + MockMvc

**Spec:** `docs/superpowers/specs/2026-09-21-jeonse-maturity-radar-design.md` (§4 제품 표면)

## Global Constraints

- JDK 21, Spring Boot 3.4.x, Gradle Groovy DSL, MySQL 8.0, Thymeleaf, JPA + JdbcTemplate, JUnit 5 + Testcontainers.
- **네트워크를 타는 테스트를 만들지 않는다.**
- 1·2단계 파일(`deal.*`, `ingest.*`, `region.*`, `metric.*`)은 **읽기 전용**이다. 단, 새 읽기 전용 메서드를 리포지토리에 추가하는 것은 해당 태스크가 명시할 때만 허용한다.
- **한글 테스트 메서드명은 숫자로 시작할 수 없다** (Java identifier 규칙).
- 커밋 메시지: Conventional Commits 접두사 + 한글 요약, 빈 줄, 그리고 정확히
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
  — 이 값은 저장소 고정값이며 작업 모델이 무엇이든 바뀌지 않는다.
- 전체 스위트 통과를 주장하려면 `./gradlew test --rerun-tasks`로 돌리고 **테스트 수와 실행된 테스트 클래스 수**를 함께 보고한다. 소스와 대조할 때는 `grep -cE '^\s*@Test\b'`처럼 앵커를 건다 (`grep -o '@Test'`는 `@TestConfiguration`·`@TestPropertySource`까지 세어 과다 계상된다).
- 착수 시점 기준선: **175 tests / 22 classes**.

---

## 착수 전 실측 (2026-09-22, 부분 데이터 240/269 시군구)

계획의 모든 결정은 아래 실측 위에 서 있다. 추정이 아니라 로컬 DB를 직접 질의한 값이다.

| 항목 | 값 |
|------|-----|
| `complex` | 39,791 (slug NULL 0건, region_code·legal_dong NULL 0건) |
| `complex_rent_stat` | 63,426 (HIGH 7.8% / MEDIUM 33.5% / LOW 58.7%) |
| `maturity_forecast` | 470,663 (HIGH 1.6% / MEDIUM 19.2% / LOW 79.2%) |
| `deal_flag` | 17 (매매 백필 미완. 2일차에 채워진다) |
| 단지가 존재하는 시군구 | 240 |
| MEDIUM 이상 시세를 1개 이상 가진 단지 | 12,172 |
| **색인 가능 단지 추정** | **12,170** (39,791의 30.6%) |

색인 대상이 12,170장이라는 점이 중요하다. 스펙 §4.1이 경고한 "2~3만 장 일괄 발행"에 해당하지 않으므로, 게이트를 지킨 채로 1차 출시가 가능하다.

### 데이터 형태에서 나온 제약

1. **`display_name`이 지번뿐인 단지가 300개 있다** — `(828-76)`, `(1-102)` 같은 형태. 제목이 "(828-76) 전세 만기·역전세 현황"이 되면 SEO에 해롭고 사용자에게도 무의미하다.
2. **시군구 이름 토큰이 1·2·3개로 갈린다** — 세종특별자치시(1), 서울특별시 송파구(2, 229건), 경기도 수원시 장안구(3, 39건).
3. **시도는 16종**이며 `전남광주통합특별시`(코드 12xxx, 27개 시군구)가 포함된다. 광주광역시·전라남도가 따로 있지 않다. 카탈로그의 값을 그대로 쓴다.
4. `complex.slug` 형식은 `{일련}-{이름}-{지역코드}-{n}` (예: `1-10-11200-2675`, `1-st이지움-41171-3065`).

---

## 사전 판정 (Rulings)

이 계획을 쓰면서 내린 결정과, 틀렸을 때 비용.

**R-1. URL의 시도 세그먼트는 카탈로그의 공식 명칭을 그대로 쓴다.**
`서울` 같은 축약형을 쓰려면 매핑 테이블이 필요하고, 그 테이블은 `전남광주통합특별시` 같은 변경에 따라 썩는다. 공식 명칭은 매핑이 0이고 모호하지 않다.
*틀렸을 때 비용:* URL이 길어진다. 나중에 301로 축약형을 얹을 수 있다.

**R-2. 3토큰 시군구는 하이픈으로 잇는다.** `/역전세/경기도/수원시-장안구`.
마지막 토큰만 쓰면(`장안구`) 짧지만 시도 내 유일성을 가정해야 한다. 하이픈 결합은 그 가정이 필요 없다.
*틀렸을 때 비용:* URL이 조금 길다.

**R-3. 시도 인덱스 페이지를 만든다.** `/역전세/{시도}`가 그 시도의 시군구 목록을 보여준다.
세종특별자치시는 시군구가 없어 2세그먼트 URL이 성립하지 않는데, 인덱스 페이지가 그 자리를 자연스럽게 메운다. 덤으로 내부 링크가 생겨 §4.1의 "점진 공개"에 유리하다.
*틀렸을 때 비용:* 페이지 타입 하나가 는다. 16장뿐이다.

**R-4. 단지 페이지 색인 조건은 "MEDIUM 이상 시세가 1개 이상" + "지번뿐인 이름이 아님"이다.**
앞은 스펙 §4.1 그대로다. 뒤는 스펙에 없는 추가 조건인데, `(828-76)`을 제목으로 하는 페이지는 스펙이 막으려던 "얇은 자동 생성 페이지" 그 자체다. 300장에만 걸린다.
*틀렸을 때 비용:* 300장이 색인에서 빠진다. 조건 한 줄이라 되돌리기 쉽다.

**R-5. 랭킹 문턱은 설정값으로 빼고 기본값을 둔다** (`jeonse.ranking.min-contract-count=3`, `jeonse.ranking.min-confidence=MEDIUM`).
원장 F-40에서 측정했듯 갭 상위 50위 중 44개가 계약 1건짜리다. 문턱 없이 랭킹하면 지역 페이지 상단이 전부 이상치가 된다. 최종 값은 전국 데이터로 Task 7에서 정하므로, 코드에 박지 않고 설정으로 뺀다.
*틀렸을 때 비용:* 기본값이 보수적이면 목록이 짧아진다. 설정 한 줄로 바뀐다.

**R-6. 발행 스냅샷이 없으면 503을 준다.**
`metric_snapshot`에 COMPLETE가 하나도 없는 상태에서 200과 빈 페이지를 주면 검색엔진이 빈 페이지를 색인한다. 503 + `Retry-After`는 "일시적"이라는 정확한 신호다.
*틀렸을 때 비용:* 첫 배치 전에 접속하면 오류 페이지를 본다. 그게 맞다.

**R-7. 엔티티를 템플릿에 넘기지 않는다.** 조회 서비스가 record를 만들고 템플릿은 record만 본다.
지연 로딩이 뷰에서 터지는 사고를 원천 차단하고, 템플릿이 볼 수 있는 것을 조회 시점에 고정한다.
*틀렸을 때 비용:* record 정의가 늘어난다.

---

## 파일 구조

```
com.jeonseradar.web
├── PublishedSnapshot.java          발행된 스냅샷 날짜 해석 (모든 읽기의 단일 관문)
├── SnapshotUnavailableException.java
├── WebExceptionHandler.java        503·404 처리
├── complex/
│   ├── ComplexPageQuery.java       단지 페이지 조회 (JdbcTemplate)
│   ├── ComplexPage.java            읽기 모델 (record)
│   └── ComplexPageController.java
└── region/
    ├── RegionPageQuery.java
    ├── RegionPage.java
    └── RegionPageController.java

com.jeonseradar.region
└── RegionName.java                 시도/시군구 분해와 URL 세그먼트 (신규, region 패키지)

src/main/resources/static/
└── style.css                       공통 스타일 (템플릿이 link로 건다)

src/main/resources/templates/
├── complex.html
├── region.html
├── sido.html
└── error/unavailable.html
```

---

### Task 1: Thymeleaf 설정과 발행 스냅샷 관문

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/jeonseradar/web/PublishedSnapshot.java`
- Create: `src/main/java/com/jeonseradar/web/SnapshotUnavailableException.java`
- Create: `src/main/java/com/jeonseradar/web/WebExceptionHandler.java`
- Create: `src/main/resources/static/style.css`
- Create: `src/main/resources/templates/error/unavailable.html`
- Test: `src/test/java/com/jeonseradar/web/PublishedSnapshotTest.java`

**Interfaces:**
- Consumes: `MetricSnapshotRepository.findLatestComplete()` (2단계 Task 5)
- Produces: `PublishedSnapshot.date()` → `LocalDate`, 없으면 `SnapshotUnavailableException`

**왜 이게 첫 태스크인가.** 2단계가 보장한 "COMPLETE인 스냅샷만 읽힌다"는 명제는 읽는 쪽이 지켜야 완성된다. 조회 코드마다 `findLatestComplete()`를 부르면 언젠가 한 곳이 빠지고, 그 한 곳이 반쪽 스냅샷을 노출한다. 관문을 하나로 두고 모든 조회가 그것을 통과하게 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/PublishedSnapshotTest.java`:

```java
package com.jeonseradar.web;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ExtendWith(SpringExtension.class)
class PublishedSnapshotTest {

    @Autowired PublishedSnapshot published;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        snapshots.deleteAll();
    }

    @Test
    void 완료된_스냅샷이_없으면_예외를_던진다() {
        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 완료된_스냅샷이_있으면_그_날짜를_준다() {
        MetricSnapshot s = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        s.complete(10, 20, 30);
        snapshots.save(s);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void 빌드_중인_스냅샷은_발행으로_치지_않는다() {
        snapshots.save(MetricSnapshot.start(LocalDate.of(2026, 9, 22)));

        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 실패한_스냅샷은_발행으로_치지_않는다() {
        MetricSnapshot s = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        s.fail("집계 실패");
        snapshots.save(s);

        assertThatThrownBy(() -> published.date())
                .isInstanceOf(SnapshotUnavailableException.class);
    }

    @Test
    void 완료본이_여러_날이면_가장_최근을_준다() {
        MetricSnapshot older = MetricSnapshot.start(LocalDate.of(2026, 9, 20));
        older.complete(1, 1, 1);
        snapshots.save(older);

        MetricSnapshot newer = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        newer.complete(1, 1, 1);
        snapshots.save(newer);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void 더_최근_날짜가_실패해도_직전_완료본이_계속_서빙된다() {
        MetricSnapshot good = MetricSnapshot.start(LocalDate.of(2026, 9, 21));
        good.complete(1, 1, 1);
        snapshots.save(good);

        MetricSnapshot bad = MetricSnapshot.start(LocalDate.of(2026, 9, 22));
        bad.fail("집계 실패");
        snapshots.save(bad);

        assertThat(published.date()).isEqualTo(LocalDate.of(2026, 9, 21));
    }
}
```

마지막 테스트가 이 태스크의 존재 이유다. 2단계 전체가 "실패해도 직전 스냅샷이 계속 서빙된다"를 약속했는데, 그 약속은 읽는 쪽에서만 관찰할 수 있다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*PublishedSnapshotTest'`
Expected: FAIL — `PublishedSnapshot` 없음 (컴파일 오류)

- [ ] **Step 3: Thymeleaf 의존성 추가**

`build.gradle`의 `dependencies` 블록에서 `spring-boot-starter-web` 바로 아래에 추가한다:

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
```

- [ ] **Step 4: 관문과 예외 작성**

`src/main/java/com/jeonseradar/web/SnapshotUnavailableException.java`:

```java
package com.jeonseradar.web;

/** 발행 가능한(COMPLETE) 스냅샷이 아직 없다. 일시적 상태이므로 503으로 응답한다. */
public class SnapshotUnavailableException extends RuntimeException {

    public SnapshotUnavailableException() {
        super("발행된 스냅샷이 없다. 아직 집계가 한 번도 완료되지 않았다.");
    }
}
```

`src/main/java/com/jeonseradar/web/PublishedSnapshot.java`:

```java
package com.jeonseradar.web;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 공개 페이지가 읽어야 할 스냅샷 날짜를 해석한다. **모든 조회는 이 관문을 통과한다.**
 *
 * 2단계는 "COMPLETE인 스냅샷만 읽힌다"를 metric_snapshot.status로 보장했다. 그러나 그
 * 보장은 읽는 쪽이 status를 실제로 확인해야 완성된다. 조회 서비스마다 각자
 * findLatestComplete()를 부르게 두면 언젠가 한 곳이 빠지고, 그 한 곳이 반쪽 스냅샷을
 * 노출한다. 관문을 하나로 둔다.
 *
 * 캐시하지 않는다. 야간 배치가 새 스냅샷을 올리면 다음 요청부터 바로 반영돼야 하고,
 * 조회 한 번은 인덱스를 탄 단일 행 읽기라 캐시할 만큼 비싸지 않다.
 */
@Service
public class PublishedSnapshot {

    private final MetricSnapshotRepository snapshots;

    public PublishedSnapshot(MetricSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** @throws SnapshotUnavailableException 완료된 스냅샷이 하나도 없을 때 */
    public LocalDate date() {
        return snapshots.findLatestComplete()
                .map(MetricSnapshot::getSnapshotDate)
                .orElseThrow(SnapshotUnavailableException::new);
    }
}
```

- [ ] **Step 5: 503 처리와 공통 스타일 작성**

`src/main/java/com/jeonseradar/web/WebExceptionHandler.java`:

```java
package com.jeonseradar.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class WebExceptionHandler {

    /**
     * 503 + Retry-After. 200에 빈 페이지를 주면 검색엔진이 그 빈 페이지를 색인한다.
     * 503은 "일시적이니 나중에 다시 오라"는 정확한 신호다.
     */
    @ExceptionHandler(SnapshotUnavailableException.class)
    public ModelAndView unavailable() {
        ModelAndView mv = new ModelAndView("error/unavailable");
        mv.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        mv.addObject("retryAfter", 3600);
        return mv;
    }
}
```

`src/main/resources/static/style.css`:

```css
/* 전세 레이더 공통 스타일. 템플릿마다 복사하지 않고 이 파일 하나를 건다 (원장 C-2). */
body { font-family: -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", sans-serif;
       margin: 0; padding: 0 16px; line-height: 1.6; color: #1a1a1a; }
.wrap { max-width: 880px; margin: 0 auto; padding: 24px 0 64px; }
table { border-collapse: collapse; width: 100%; margin: 12px 0; }
th, td { border-bottom: 1px solid #e5e5e5; padding: 8px 6px; text-align: right; }
th:first-child, td:first-child { text-align: left; }
th { font-weight: 600; background: #fafafa; }
.conf { font-size: 12px; padding: 2px 6px; border-radius: 3px; }
.conf-HIGH { background: #e6f4ea; color: #1e7e34; }
.conf-MEDIUM { background: #fff4e5; color: #b26a00; }
.conf-LOW { background: #f1f1f1; color: #666; }
.gap-pos { color: #c0392b; font-weight: 600; }
.gap-neg { color: #1e7e34; }
.muted { color: #666; font-size: 14px; }
.cards { display: flex; gap: 16px; margin: 16px 0; flex-wrap: wrap; }
.card { border: 1px solid #e5e5e5; border-radius: 6px; padding: 12px 16px; min-width: 140px; }
.card b { display: block; font-size: 22px; }
ul.sigungu { columns: 3; list-style: none; padding: 0; }
ul.sigungu li { padding: 4px 0; break-inside: avoid; }
a { color: #1a5fb4; }
h1 { font-size: 24px; margin: 8px 0; }
h2 { font-size: 18px; margin: 32px 0 8px; }
@media (max-width: 600px) {
  ul.sigungu { columns: 2; }
  table { font-size: 14px; }
}
```

템플릿마다 `<style>` 블록을 복사하는 대신 이 파일 하나를 `<link>`로 건다. 3단계의 모든 템플릿이 같은 표·신뢰도 배지·카드를 쓰므로, 복사하면 세 벌이 되고 한 벌만 고치는 사고가 난다 (원장 C-2).

`src/main/resources/templates/error/unavailable.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ko">
<head>
  <meta charset="UTF-8">
  <title>집계 준비 중</title>
  <meta name="robots" content="noindex">
</head>
<body>
  <h1>집계 준비 중입니다</h1>
  <p>아직 발행된 집계 결과가 없습니다. 잠시 후 다시 확인해 주세요.</p>
</body>
</html>
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*PublishedSnapshotTest'`
Expected: PASS (6개)

- [ ] **Step 7: 전체 스위트와 커밋**

```bash
./gradlew test --rerun-tasks
git add build.gradle src/main/java/com/jeonseradar/web src/main/resources/templates src/main/resources/static src/test/java/com/jeonseradar/web
git commit -m "feat: Thymeleaf 설정과 발행 스냅샷 관문 추가"
```

---

### Task 2: 시도·시군구 이름 분해

**Files:**
- Create: `src/main/java/com/jeonseradar/region/RegionName.java`
- Test: `src/test/java/com/jeonseradar/region/RegionNameTest.java`

**Interfaces:**
- Consumes: `SigunguCatalog.nameOf(String code)` → `"서울특별시 송파구"` 같은 한 덩어리 문자열
- Produces: `RegionName.of(String code, String fullName)` → `RegionName`
  - `sido()`, `sigungu()`, `sidoSegment()`, `sigunguSegment()`, `title()`

**왜 별도 태스크인가.** 카탈로그는 `11710,서울특별시 송파구`처럼 시도와 시군구를 한 문자열로 갖고 있는데 URL은 둘로 나눠야 한다. 그리고 그 분해가 세 갈래다 — 1토큰(세종), 2토큰(229건), 3토큰(39건). 컨트롤러에 흩어두면 세 갈래 중 하나가 빠진다. 한 곳에 모으고 경계를 테스트로 고정한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/region/RegionNameTest.java`:

```java
package com.jeonseradar.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionNameTest {

    @Test
    void 두_토큰이면_시도와_시군구로_나뉜다() {
        RegionName n = RegionName.of("11710", "서울특별시 송파구");

        assertThat(n.sido()).isEqualTo("서울특별시");
        assertThat(n.sigungu()).isEqualTo("송파구");
        assertThat(n.sidoSegment()).isEqualTo("서울특별시");
        assertThat(n.sigunguSegment()).isEqualTo("송파구");
    }

    @Test
    void 세_토큰이면_뒤_둘을_하이픈으로_잇는다() {
        RegionName n = RegionName.of("41111", "경기도 수원시 장안구");

        assertThat(n.sido()).isEqualTo("경기도");
        assertThat(n.sigungu()).isEqualTo("수원시 장안구");
        assertThat(n.sigunguSegment()).isEqualTo("수원시-장안구");
    }

    @Test
    void 한_토큰이면_시군구가_없다() {
        RegionName n = RegionName.of("36110", "세종특별자치시");

        assertThat(n.sido()).isEqualTo("세종특별자치시");
        assertThat(n.sigungu()).isNull();
        assertThat(n.sigunguSegment()).isNull();
        assertThat(n.hasSigungu()).isFalse();
    }

    @Test
    void 통합_시도명도_그대로_쓴다() {
        RegionName n = RegionName.of("12110", "전남광주통합특별시 목포시");

        assertThat(n.sido()).isEqualTo("전남광주통합특별시");
        assertThat(n.sigungu()).isEqualTo("목포시");
    }

    @Test
    void URL_세그먼트에서_이름을_복원한다() {
        assertThat(RegionName.sigunguFromSegment("수원시-장안구")).isEqualTo("수원시 장안구");
        assertThat(RegionName.sigunguFromSegment("송파구")).isEqualTo("송파구");
    }

    @Test
    void 모르는_코드는_명확한_예외로_알린다() {
        // SigunguCatalog.nameOf()는 모르는 코드에 null을 준다. 행정구역 개편으로
        // 국토부가 새 코드를 쓰기 시작하면 실제로 일어난다 (전남광주통합특별시가 그 예).
        // NPE로 터지면 원인을 못 찾으므로 무엇이 없는지 말해주고 죽는다.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> RegionName.of("99999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99999");
    }

    @Test
    void 제목은_시군구가_있으면_시군구를_쓴다() {
        assertThat(RegionName.of("11710", "서울특별시 송파구").title())
                .isEqualTo("송파구 역전세 현황");
        assertThat(RegionName.of("36110", "세종특별자치시").title())
                .isEqualTo("세종특별자치시 역전세 현황");
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*RegionNameTest'`
Expected: FAIL — `RegionName` 없음

- [ ] **Step 3: 구현**

`src/main/java/com/jeonseradar/region/RegionName.java`:

```java
package com.jeonseradar.region;

/**
 * 카탈로그의 한 덩어리 지역명을 시도와 시군구로 나누고 URL 세그먼트를 만든다.
 *
 * 실측된 세 갈래를 모두 다룬다 (2026-09-22 sigungu.csv 269행 기준):
 *   1토큰  세종특별자치시            1건  — 시군구가 없다
 *   2토큰  서울특별시 송파구        229건
 *   3토큰  경기도 수원시 장안구      39건  — 특례시·대도시의 구
 *
 * 3토큰을 마지막 토큰만으로 줄이지 않는 이유는 시도 안에서의 유일성을 가정해야 하기
 * 때문이다. 하이픈 결합은 그 가정이 필요 없다 (원장 R-2).
 */
public record RegionName(String code, String sido, String sigungu) {

    private static final String SEGMENT_DELIMITER = "-";

    public static RegionName of(String code, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            // 카탈로그에 없는 코드다. 행정구역 개편으로 새 코드가 들어오면 실제로 생긴다.
            // NPE 대신 무엇이 없는지 말한다.
            throw new IllegalArgumentException("카탈로그에 없는 시군구 코드: " + code);
        }
        String[] tokens = fullName.trim().split("\\s+");
        if (tokens.length == 1) {
            return new RegionName(code, tokens[0], null);
        }
        String sido = tokens[0];
        String sigungu = String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
        return new RegionName(code, sido, sigungu);
    }

    public boolean hasSigungu() {
        return sigungu != null;
    }

    public String sidoSegment() {
        return sido;
    }

    /** 공백을 하이픈으로 바꾼 URL 세그먼트. 시군구가 없으면 null. */
    public String sigunguSegment() {
        return sigungu == null ? null : sigungu.replace(" ", SEGMENT_DELIMITER);
    }

    /** URL 세그먼트를 원래 이름으로 되돌린다. */
    public static String sigunguFromSegment(String segment) {
        return segment.replace(SEGMENT_DELIMITER, " ");
    }

    /** 페이지에 쓰는 이름. 시군구가 있으면 시군구, 없으면 시도. */
    public String displayName() {
        return hasSigungu() ? sigungu : sido;
    }

    public String title() {
        return displayName() + " 역전세 현황";
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*RegionNameTest'`
Expected: PASS (7개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jeonseradar/region/RegionName.java src/test/java/com/jeonseradar/region/RegionNameTest.java
git commit -m "feat: 시도·시군구 이름 분해와 URL 세그먼트 추가"
```

---

### Task 3: 단지 페이지 읽기 모델

**Files:**
- Create: `src/main/java/com/jeonseradar/web/complex/ComplexPage.java`
- Create: `src/main/java/com/jeonseradar/web/complex/ComplexPageQuery.java`
- Test: `src/test/java/com/jeonseradar/web/complex/ComplexPageQueryTest.java`

**Interfaces:**
- Consumes: `PublishedSnapshot.date()` (Task 1), `SigunguCatalog.nameOf(code)`, `RegionName.of(...)` (Task 2)
- Produces: `ComplexPageQuery.find(long id)` → `Optional<ComplexPage>`

**색인 판정이 여기 있는 이유.** 색인 여부는 데이터의 성질이지 컨트롤러의 사정이 아니다. 조회 시점에 정하면 sitemap(Task 7)도 같은 규칙을 재사용할 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/complex/ComplexPageQueryTest.java`:

```java
package com.jeonseradar.web.complex;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ComplexPageQueryTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired ComplexPageQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 1, 0);
        snapshots.save(s);
    }

    private void complex(long id, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, '11710', '잠실동', '40', ?, ?, 2018)
                """, id, "11710-" + id, name, id + "-단지-11710-1");
    }

    private void stat(long complexId, int bucket, long deposit, int n, String level, String conf) {
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT, complexId, bucket, deposit, n, level, conf);
    }

    @Test
    void 단지가_없으면_비어있다() {
        assertThat(query.find(999L)).isEmpty();
    }

    @Test
    void 평형별_시세를_넓은_평형_순으로_준다() {
        complex(1, "헬리오시티");
        stat(1, 59, 700_000_000L, 12, "L1", "HIGH");
        stat(1, 84, 950_000_000L, 30, "L1", "HIGH");

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.displayName()).isEqualTo("헬리오시티");
        assertThat(page.regionName()).isEqualTo("송파구");
        assertThat(page.snapshotDate()).isEqualTo(SNAPSHOT);
        assertThat(page.areaStats()).extracting(ComplexPage.AreaStat::areaBucket)
                .containsExactly(84, 59);
        assertThat(page.areaStats().get(0).medianDeposit()).isEqualTo(950_000_000L);
    }

    @Test
    void 다른_날짜_스냅샷의_행은_섞이지_않는다() {
        complex(1, "헬리오시티");
        stat(1, 84, 950_000_000L, 30, "L1", "HIGH");
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES ('2026-09-21', 1, 59, 1, 1, 'L1', 'HIGH')
                """);

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.areaStats()).hasSize(1);
        assertThat(page.areaStats().get(0).areaBucket()).isEqualTo(84);
    }

    @Test
    void 만기_행을_만기월_순으로_준다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 30, "L1", "HIGH");
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, 1, 84, '202612', 5, 1, 1000000000, 900000000, 100000000, 0.1, 2, 'MEDIUM'),
                       (?, 1, 84, '202611', 3, 0,  950000000, 900000000,  50000000, 0.05, 1, 'MEDIUM')
                """, SNAPSHOT, SNAPSHOT);

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.maturities()).extracting(ComplexPage.MaturityRow::maturityYm)
                .containsExactly("202611", "202612");
        assertThat(page.maturities().get(0).gapAmount()).isEqualTo(50_000_000L);
    }

    @Test
    void MEDIUM_이상_시세가_하나라도_있으면_색인_대상이다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 2, "L3", "LOW");
        stat(1, 59, 700_000_000L, 5, "L1", "MEDIUM");

        assertThat(query.find(1L).orElseThrow().indexable()).isTrue();
    }

    @Test
    void 전부_LOW면_색인_대상이_아니다() {
        complex(1, "헬리오시티");
        stat(1, 84, 900_000_000L, 2, "L3", "LOW");
        stat(1, 59, 700_000_000L, 1, "L3", "LOW");

        assertThat(query.find(1L).orElseThrow().indexable()).isFalse();
    }

    @Test
    void 시세가_아예_없으면_색인_대상이_아니다() {
        complex(1, "헬리오시티");

        ComplexPage page = query.find(1L).orElseThrow();

        assertThat(page.areaStats()).isEmpty();
        assertThat(page.indexable()).isFalse();
    }

    @Test
    void 이름이_지번뿐이면_색인_대상이_아니다() {
        complex(1, "(828-76)");
        stat(1, 84, 900_000_000L, 30, "L1", "HIGH");

        assertThat(query.find(1L).orElseThrow().indexable()).isFalse();
    }
}
```

마지막 두 테스트가 R-4를 고정한다. 신뢰도만 보는 구현, 이름만 보는 구현 모두 배제된다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*ComplexPageQueryTest'`
Expected: FAIL — `ComplexPage`·`ComplexPageQuery` 없음

- [ ] **Step 3: 읽기 모델 작성**

`src/main/java/com/jeonseradar/web/complex/ComplexPage.java`:

```java
package com.jeonseradar.web.complex;

import java.time.LocalDate;
import java.util.List;

/**
 * 단지 페이지가 렌더링하는 전부. 엔티티를 뷰까지 끌고 가지 않는다 (원장 R-7) —
 * 템플릿이 볼 수 있는 것을 조회 시점에 고정하고, 지연 로딩이 뷰에서 터질 여지를 없앤다.
 */
public record ComplexPage(
        long id,
        String slug,
        String displayName,
        String legalDong,
        String regionName,
        Integer buildYear,
        LocalDate snapshotDate,
        List<AreaStat> areaStats,
        List<MaturityRow> maturities,
        boolean indexable) {

    public record AreaStat(
            int areaBucket, long medianDeposit, int sampleCount,
            String sourceLevel, String confidence) {}

    public record MaturityRow(
            String maturityYm, int areaBucket, int contractCount, int estimatedCount,
            long medianDepositThen, long currentMedianDeposit,
            long gapAmount, double gapRate, int renewalExhaustedCount, String confidence) {}

    /** `/단지/{id}-{slug}` */
    public String path() {
        return "/단지/" + id + "-" + slug;
    }

    /** 스펙 §4.2: 단지명 단독으로는 대형 서비스를 이길 수 없으므로 수식어를 결합한다. */
    public String title() {
        return displayName + " 전세 만기·역전세 현황 (" + snapshotDate.getYear() + ")";
    }
}
```

`src/main/java/com/jeonseradar/web/complex/ComplexPageQuery.java`:

```java
package com.jeonseradar.web.complex;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.PublishedSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ComplexPageQuery {

    /** 국토부 원자료에 단지명이 없어 지번만 남은 경우: `(828-76)`. 실측 300건. */
    private static final Pattern JIBUN_ONLY = Pattern.compile("^\\(.*\\)$");

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;

    public ComplexPageQuery(JdbcTemplate jdbc, PublishedSnapshot published, SigunguCatalog catalog) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
    }

    public Optional<ComplexPage> find(long id) {
        LocalDate snapshotDate = published.date();

        List<Map<String, Object>> head = jdbc.queryForList("""
                SELECT id, slug, display_name, legal_dong, region_code, build_year
                FROM complex WHERE id = ?
                """, id);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> c = head.get(0);

        List<ComplexPage.AreaStat> stats = jdbc.query("""
                SELECT area_bucket, median_deposit, sample_count, source_level, confidence
                FROM complex_rent_stat
                WHERE snapshot_date = ? AND complex_id = ?
                ORDER BY area_bucket DESC
                """,
                (rs, n) -> new ComplexPage.AreaStat(
                        rs.getInt("area_bucket"), rs.getLong("median_deposit"),
                        rs.getInt("sample_count"), rs.getString("source_level"),
                        rs.getString("confidence")),
                snapshotDate, id);

        List<ComplexPage.MaturityRow> maturities = jdbc.query("""
                SELECT maturity_ym, area_bucket, contract_count, estimated_count,
                       median_deposit_then, current_median_deposit,
                       gap_amount, gap_rate, renewal_exhausted_count, confidence
                FROM maturity_forecast
                WHERE snapshot_date = ? AND complex_id = ?
                ORDER BY maturity_ym, area_bucket
                """,
                (rs, n) -> new ComplexPage.MaturityRow(
                        rs.getString("maturity_ym"), rs.getInt("area_bucket"),
                        rs.getInt("contract_count"), rs.getInt("estimated_count"),
                        rs.getLong("median_deposit_then"), rs.getLong("current_median_deposit"),
                        rs.getLong("gap_amount"), rs.getDouble("gap_rate"),
                        rs.getInt("renewal_exhausted_count"), rs.getString("confidence")),
                snapshotDate, id);

        String displayName = (String) c.get("display_name");
        String regionCode = (String) c.get("region_code");
        RegionName region = RegionName.of(regionCode, catalog.nameOf(regionCode));

        return Optional.of(new ComplexPage(
                ((Number) c.get("id")).longValue(),
                (String) c.get("slug"),
                displayName,
                (String) c.get("legal_dong"),
                region.displayName(),
                c.get("build_year") == null ? null : ((Number) c.get("build_year")).intValue(),
                snapshotDate,
                stats,
                maturities,
                indexable(displayName, stats)));
    }

    /**
     * 스펙 §4.1: confidence가 LOW이거나 산출 불가면 noindex. 자동 생성된 얇은 페이지를
     * 대량 발행하면 검색엔진이 doorway page로 보고 사이트 전체 평가를 낮춘다.
     *
     * 지번뿐인 이름을 더 배제하는 것은 스펙에 없는 추가 조건이다 (원장 R-4).
     * "(828-76) 전세 만기·역전세 현황"이라는 제목의 페이지는 스펙이 막으려던 바로 그것이다.
     */
    private boolean indexable(String displayName, List<ComplexPage.AreaStat> stats) {
        if (JIBUN_ONLY.matcher(displayName).matches()) {
            return false;
        }
        return stats.stream().anyMatch(s -> !"LOW".equals(s.confidence()));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*ComplexPageQueryTest'`
Expected: PASS (8개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/jeonseradar/web/complex src/test/java/com/jeonseradar/web/complex
git commit -m "feat: 단지 페이지 읽기 모델과 색인 판정 추가"
```

---

### Task 4: 단지 페이지 컨트롤러와 템플릿

**Files:**
- Create: `src/main/java/com/jeonseradar/web/complex/ComplexPageController.java`
- Create: `src/main/resources/templates/complex.html`
- Test: `src/test/java/com/jeonseradar/web/complex/ComplexPageControllerTest.java`

**Interfaces:**
- Consumes: `ComplexPageQuery.find(long)` (Task 3)
- Produces: `GET /단지/{idAndSlug}`

**정규 URL 처리.** slug가 바뀌거나 사용자가 id만 알고 들어와도 페이지는 떠야 하지만, 색인은 한 주소로 모여야 한다. slug가 다르면 301로 정규 주소에 보낸다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/complex/ComplexPageControllerTest.java`:

```java
package com.jeonseradar.web.complex;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ComplexPageControllerTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();
    }

    private void publishSnapshot() {
        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 0, 0);
        snapshots.save(s);
    }

    private void complexWith(String name, String confidence) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (1, '11710-1', '11710', '잠실동', '40', ?, 'helio-11710-1', 2018)
                """, name);
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, 1, 84, 950000000, 30, 'L1', ?)
                """, SNAPSHOT, confidence);
    }

    @Test
    void 발행된_스냅샷이_없으면_503을_준다() throws Exception {
        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 없는_단지는_404를_준다() throws Exception {
        publishSnapshot();
        mvc.perform(get("/단지/999-없음")).andExpect(status().isNotFound());
    }

    @Test
    void 단지_페이지가_렌더링된다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("complex"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("헬리오시티")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("전세 만기·역전세 현황")));
    }

    @Test
    void slug가_다르면_정규_주소로_301한다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        mvc.perform(get("/단지/1-옛날slug"))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/단지/1-helio-11710-1"));
    }

    @Test
    void 신뢰도가_충분하면_noindex를_넣지_않는다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "HIGH");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("noindex"))));
    }

    @Test
    void 신뢰도가_낮으면_noindex를_넣는다() throws Exception {
        publishSnapshot();
        complexWith("헬리오시티", "LOW");

        mvc.perform(get("/단지/1-helio-11710-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("noindex")));
    }
}
```

마지막 두 테스트가 짝이다. noindex를 늘 넣는 구현과 한 번도 안 넣는 구현이 모두 배제된다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*ComplexPageControllerTest'`
Expected: FAIL — 매핑 없음 (404)

- [ ] **Step 3: 컨트롤러 작성**

`src/main/java/com/jeonseradar/web/complex/ComplexPageController.java`:

```java
package com.jeonseradar.web.complex;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class ComplexPageController {

    private final ComplexPageQuery query;

    public ComplexPageController(ComplexPageQuery query) {
        this.query = query;
    }

    /**
     * 반환형이 Object인 이유: 정규 주소가 아닐 때는 뷰 이름 대신 RedirectView를 돌려준다.
     * `return "redirect:..."`는 302를 내는데, 영구 이전이므로 301이어야 색인이 정규 주소로
     * 옮겨간다.
     */
    @GetMapping("/단지/{idAndSlug}")
    public Object page(@PathVariable String idAndSlug, Model model) {
        long id = parseId(idAndSlug);
        ComplexPage page = query.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // 같은 단지가 여러 주소로 색인되지 않도록 정규 주소 하나로 모은다.
        if (!idAndSlug.equals(id + "-" + page.slug())) {
            RedirectView redirect = new RedirectView(page.path());
            redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return redirect;
        }

        model.addAttribute("page", page);
        model.addAttribute("robots", page.indexable() ? null : "noindex, follow");
        return "complex";
    }

    /** `123-헬리오시티-11710-1` 에서 앞의 123만 뽑는다. 숫자가 아니면 404. */
    private long parseId(String idAndSlug) {
        int dash = idAndSlug.indexOf('-');
        String raw = dash < 0 ? idAndSlug : idAndSlug.substring(0, dash);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
```

`noindex`일 때 `follow`를 함께 주는 이유: 이 페이지 자체는 색인하지 않되, 여기서 나가는
링크는 따라가게 둔다. 데이터가 쌓여 MEDIUM으로 올라간 이웃 단지가 발견될 통로가 된다.

- [ ] **Step 4: 템플릿 작성**

`src/main/resources/templates/complex.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title th:text="${page.title()}">단지</title>
  <meta name="robots" th:if="${robots != null}" th:attr="content=${robots}">
  <link rel="canonical" th:href="${page.path()}">
  <link rel="stylesheet" href="/style.css">
</head>
<body>
<div class="wrap">
  <h1 th:text="${page.displayName()} + ' 전세 만기·역전세 현황'">단지명</h1>
  <p class="muted">
    <span th:text="${page.regionName()}">지역</span>
    <span th:text="${page.legalDong()}">법정동</span>
    <span th:if="${page.buildYear() != null}" th:text="'· ' + ${page.buildYear()} + '년 준공'"></span>
    · <span th:text="${page.snapshotDate()}">기준일</span> 기준
  </p>

  <h2>현재 전세 시세</h2>
  <p class="muted" th:if="${page.areaStats().isEmpty()}">
    최근 6개월 전세 실거래가 없어 시세를 산출하지 못했습니다.
  </p>
  <table th:unless="${page.areaStats().isEmpty()}">
    <thead>
    <tr><th>평형(㎡)</th><th>전세 중위값</th><th>표본</th><th>산출</th><th>신뢰도</th></tr>
    </thead>
    <tbody>
    <tr th:each="s : ${page.areaStats()}">
      <td th:text="${s.areaBucket()}">84</td>
      <td th:text="${#numbers.formatInteger(s.medianDeposit() / 10000, 0, 'COMMA')} + '만원'">0</td>
      <td th:text="${s.sampleCount()} + '건'">0</td>
      <td th:text="${s.sourceLevel()}">L1</td>
      <td><span class="conf" th:classappend="'conf-' + ${s.confidence()}"
                th:text="${s.confidence()}">HIGH</span></td>
    </tr>
    </tbody>
  </table>
  <p class="muted">
    산출 기준 — L1: 이 단지 같은 평형의 최근 6개월 실거래.
    L2: 이 단지 다른 평형의 ㎡당 단가로 환산. L3: 같은 법정동 같은 평형.
    표본이 적으면 신뢰도를 낮춰 표시합니다.
  </p>

  <h2>전세 만기 일정과 역전세 갭</h2>
  <p class="muted" th:if="${page.maturities().isEmpty()}">만기가 확인된 계약이 없습니다.</p>
  <table th:unless="${page.maturities().isEmpty()}">
    <thead>
    <tr><th>만기</th><th>평형</th><th>계약</th><th>당시 보증금</th><th>현재 시세</th>
      <th>갭</th><th>갱신권 소진</th><th>신뢰도</th></tr>
    </thead>
    <tbody>
    <tr th:each="m : ${page.maturities()}">
      <td th:text="${#strings.substring(m.maturityYm(),0,4)} + '.' + ${#strings.substring(m.maturityYm(),4,6)}">2026.12</td>
      <td th:text="${m.areaBucket()}">84</td>
      <td th:text="${m.contractCount()} + '건'">0</td>
      <td th:text="${#numbers.formatInteger(m.medianDepositThen() / 10000, 0, 'COMMA')}">0</td>
      <td th:text="${#numbers.formatInteger(m.currentMedianDeposit() / 10000, 0, 'COMMA')}">0</td>
      <td th:classappend="${m.gapAmount() > 0} ? 'gap-pos' : 'gap-neg'"
          th:text="(${m.gapAmount() > 0} ? '+' : '') + ${#numbers.formatInteger(m.gapAmount() / 10000, 0, 'COMMA')}">0</td>
      <td th:text="${m.renewalExhaustedCount()} + '건'">0</td>
      <td><span class="conf" th:classappend="'conf-' + ${m.confidence()}"
                th:text="${m.confidence()}">LOW</span></td>
    </tr>
    </tbody>
  </table>
  <p class="muted">
    갭은 <b>당시 보증금 − 현재 시세</b>입니다. 양수(빨강)는 만기에 집주인이 돌려줘야 할
    차액이 있다는 뜻입니다. 계약 건수가 적으면 당시 보증금이 중위값이 아니라 그 한 건의
    가격이므로, 신뢰도를 그만큼 낮춰 표시합니다.
  </p>
</div>
</body>
</html>
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*ComplexPageControllerTest'`
Expected: PASS (6개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/web/complex src/main/resources/templates/complex.html src/test/java/com/jeonseradar/web/complex
git commit -m "feat: 단지 페이지 컨트롤러와 템플릿 추가"
```

---

### Task 5: 지역 페이지 조회와 랭킹 문턱

**Files:**
- Create: `src/main/java/com/jeonseradar/web/region/RegionPage.java`
- Create: `src/main/java/com/jeonseradar/web/region/RegionPageQuery.java`
- Create: `src/main/java/com/jeonseradar/web/region/RankingProperties.java`
- Modify: `src/main/resources/application.yml` (jeonse.ranking 블록 추가)
- Test: `src/test/java/com/jeonseradar/web/region/RegionPageQueryTest.java`

**Interfaces:**
- Consumes: `PublishedSnapshot.date()`, `SigunguCatalog`, `RegionName`
- Produces: `RegionPageQuery.find(String regionCode)` → `Optional<RegionPage>`,
  `RegionPageQuery.sigunguCodesOf(String sido)` → `List<String>`

**문턱이 설정값인 이유 (R-5).** 실측에서 갭 상위 50위 중 44개가 계약 1건짜리였다. 문턱 없이 갭 내림차순으로 늘어놓으면 지역 페이지 상단이 전부 표본 빈약 이상치가 된다. 최종 값은 전국 데이터로 2단계 Task 7에서 정하므로 코드에 박지 않는다.

**빈약한 행을 계산에서 빼지는 않는다.** 단지 자기 페이지에서는 "계약 1건 기준"이라고 그대로 보여준다. 거르는 것은 랭킹뿐이다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/region/RegionPageQueryTest.java`:

```java
package com.jeonseradar.web.region;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RegionPageQueryTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired RegionPageQuery query;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 1, 0);
        snapshots.save(s);
    }

    private void complex(long id, String code, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, ?, '잠실동', '40', ?, ?, 2018)
                """, id, code + "-" + id, code, name, id + "-단지-" + code + "-1");
    }

    private void forecast(long complexId, String ym, long gap, int contracts, String conf) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, ?, 84, ?, ?, 0, 1000000000, ?, ?, 0.1, 1, ?)
                """, SNAPSHOT, complexId, ym, contracts, 1_000_000_000L - gap, gap, conf);
    }

    @Test
    void 단지가_없는_지역은_비어있다() {
        assertThat(query.find("11710")).isEmpty();
    }

    @Test
    void 지역_이름과_기준일을_준다() {
        complex(1, "11710", "헬리오시티");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.regionName()).isEqualTo("송파구");
        assertThat(page.sido()).isEqualTo("서울특별시");
        assertThat(page.snapshotDate()).isEqualTo(SNAPSHOT);
    }

    @Test
    void 랭킹은_갭_내림차순이다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 300_000_000L, 5, "MEDIUM");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("나단지", "가단지");
    }

    @Test
    void 계약_건수가_문턱_미만이면_랭킹에서_빠진다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 1, "MEDIUM"); // 갭은 크지만 계약 1건

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("가단지");
    }

    @Test
    void 신뢰도가_문턱_미만이면_랭킹에서_빠진다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 9, "LOW");

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).extracting(RegionPage.RankRow::displayName)
                .containsExactly("가단지");
    }

    @Test
    void 갭이_음수인_행은_역전세가_아니므로_랭킹에_없다() {
        complex(1, "11710", "가단지");
        forecast(1, "202612", -100_000_000L, 5, "MEDIUM");

        assertThat(query.find("11710").orElseThrow().ranking()).isEmpty();
    }

    @Test
    void 요약은_거른_행이_아니라_전체를_센다() {
        complex(1, "11710", "가단지");
        complex(2, "11710", "나단지");
        forecast(1, "202612", 100_000_000L, 5, "MEDIUM");
        forecast(2, "202612", 900_000_000L, 1, "LOW"); // 랭킹에선 빠지지만 집계엔 든다

        RegionPage page = query.find("11710").orElseThrow();

        assertThat(page.ranking()).hasSize(1);
        assertThat(page.totalMaturityContracts()).isEqualTo(6);
        assertThat(page.negativeGapContracts()).isEqualTo(6);
    }

    @Test
    void 시도의_시군구_코드를_준다() {
        complex(1, "11710", "가단지");
        complex(2, "11680", "나단지");
        complex(3, "41111", "다단지");

        assertThat(query.sigunguCodesOf("서울특별시")).containsExactlyInAnyOrder("11710", "11680");
    }
}
```

`요약은_거른_행이_아니라_전체를_센다`가 중요하다. 랭킹 필터가 통계까지 오염시키면 "이 지역 만기 물량"이 실제보다 작게 나온다. 거르는 것은 목록이지 사실이 아니다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*RegionPageQueryTest'`
Expected: FAIL — `RegionPage`·`RegionPageQuery` 없음

- [ ] **Step 3: 설정 클래스와 application.yml**

`src/main/java/com/jeonseradar/web/region/RankingProperties.java`:

```java
package com.jeonseradar.web.region;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지역 페이지 랭킹에 올릴 최소 기준 (원장 R-5, F-40).
 *
 * 2026-09-22 실측: 갭 상위 50위 중 44개가 계약 1건짜리였고, 1건 그룹의 최대 갭은 35억,
 * 5건 이상 그룹은 9억이었다. 극단값이 시장이 아니라 표본 빈약의 산물이므로 문턱이 필요하다.
 * 최종 값은 전국 데이터로 2단계 Task 7에서 정하므로 설정으로 뺀다.
 */
@ConfigurationProperties(prefix = "jeonse.ranking")
public record RankingProperties(int minContractCount, String minConfidence, int limit) {

    public RankingProperties {
        if (minContractCount < 1) {
            throw new IllegalArgumentException("minContractCount는 1 이상이어야 한다: " + minContractCount);
        }
        if (!java.util.List.of("HIGH", "MEDIUM", "LOW").contains(minConfidence)) {
            throw new IllegalArgumentException("minConfidence는 HIGH·MEDIUM·LOW 중 하나여야 한다: " + minConfidence);
        }
    }

    /** SQL IN 절에 넣을, 문턱 이상인 신뢰도 목록. */
    public java.util.List<String> acceptedConfidences() {
        return switch (minConfidence) {
            case "HIGH" -> java.util.List.of("HIGH");
            case "MEDIUM" -> java.util.List.of("HIGH", "MEDIUM");
            default -> java.util.List.of("HIGH", "MEDIUM", "LOW");
        };
    }
}
```

`JeonseRadarApplication`에 `@ConfigurationPropertiesScan`을 더한다 (이미 있으면 그대로 둔다).

`src/main/resources/application.yml`에 추가:

```yaml
jeonse:
  ranking:
    min-contract-count: 3
    min-confidence: MEDIUM
    limit: 50
```

- [ ] **Step 4: 읽기 모델과 조회 작성**

`src/main/java/com/jeonseradar/web/region/RegionPage.java`:

```java
package com.jeonseradar.web.region;

import java.time.LocalDate;
import java.util.List;

public record RegionPage(
        String regionCode,
        String sido,
        String regionName,
        String sidoSegment,
        String sigunguSegment,
        LocalDate snapshotDate,
        int complexCount,
        long totalMaturityContracts,
        long negativeGapContracts,
        List<RankRow> ranking) {

    public record RankRow(
            long complexId, String slug, String displayName, String legalDong,
            int areaBucket, String maturityYm, int contractCount,
            long gapAmount, double gapRate, int renewalExhaustedCount, String confidence) {

        public String path() {
            return "/단지/" + complexId + "-" + slug;
        }
    }

    public String path() {
        return sigunguSegment == null
                ? "/역전세/" + sidoSegment
                : "/역전세/" + sidoSegment + "/" + sigunguSegment;
    }

    public String title() {
        return regionName + " 전세 만기·역전세 현황 (" + snapshotDate.getYear() + ")";
    }

    /** 랭킹에 올릴 게 하나도 없는 지역은 색인하지 않는다. */
    public boolean indexable() {
        return !ranking.isEmpty();
    }
}
```

`src/main/java/com/jeonseradar/web/region/RegionPageQuery.java`:

```java
package com.jeonseradar.web.region;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import com.jeonseradar.web.PublishedSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RegionPageQuery {

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;
    private final RankingProperties ranking;

    public RegionPageQuery(JdbcTemplate jdbc, PublishedSnapshot published,
                           SigunguCatalog catalog, RankingProperties ranking) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
        this.ranking = ranking;
    }

    public Optional<RegionPage> find(String regionCode) {
        LocalDate snapshotDate = published.date();

        Integer complexCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complex WHERE region_code = ?", Integer.class, regionCode);
        if (complexCount == null || complexCount == 0) {
            return Optional.empty();
        }

        // 요약은 랭킹 필터를 적용하지 않는다. 거르는 것은 목록이지 사실이 아니다.
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT COALESCE(SUM(f.contract_count), 0) total,
                       COALESCE(SUM(CASE WHEN f.gap_amount > 0 THEN f.contract_count ELSE 0 END), 0) negative
                FROM maturity_forecast f JOIN complex c ON c.id = f.complex_id
                WHERE f.snapshot_date = ? AND c.region_code = ?
                """, snapshotDate, regionCode);

        String placeholders = String.join(",",
                java.util.Collections.nCopies(ranking.acceptedConfidences().size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(snapshotDate);
        args.add(regionCode);
        args.add(ranking.minContractCount());
        args.addAll(ranking.acceptedConfidences());
        args.add(ranking.limit());

        List<RegionPage.RankRow> rows = jdbc.query("""
                SELECT c.id, c.slug, c.display_name, c.legal_dong,
                       f.area_bucket, f.maturity_ym, f.contract_count,
                       f.gap_amount, f.gap_rate, f.renewal_exhausted_count, f.confidence
                FROM maturity_forecast f JOIN complex c ON c.id = f.complex_id
                WHERE f.snapshot_date = ? AND c.region_code = ?
                  AND f.gap_amount > 0
                  AND f.contract_count >= ?
                  AND f.confidence IN (%s)
                ORDER BY f.gap_amount DESC
                LIMIT ?
                """.formatted(placeholders),
                (rs, n) -> new RegionPage.RankRow(
                        rs.getLong("id"), rs.getString("slug"), rs.getString("display_name"),
                        rs.getString("legal_dong"), rs.getInt("area_bucket"),
                        rs.getString("maturity_ym"), rs.getInt("contract_count"),
                        rs.getLong("gap_amount"), rs.getDouble("gap_rate"),
                        rs.getInt("renewal_exhausted_count"), rs.getString("confidence")),
                args.toArray());

        RegionName name = RegionName.of(regionCode, catalog.nameOf(regionCode));
        return Optional.of(new RegionPage(
                regionCode, name.sido(), name.displayName(),
                name.sidoSegment(), name.sigunguSegment(), snapshotDate,
                complexCount,
                ((Number) summary.get("total")).longValue(),
                ((Number) summary.get("negative")).longValue(),
                rows));
    }

    /** 시도 인덱스 페이지용. 단지가 실제로 있는 시군구만 준다. */
    public List<String> sigunguCodesOf(String sido) {
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT region_code FROM complex ORDER BY region_code", String.class);
        return codes.stream()
                .filter(code -> sido.equals(RegionName.of(code, catalog.nameOf(code)).sido()))
                .toList();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*RegionPageQueryTest'`
Expected: PASS (8개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/web/region src/main/resources/application.yml src/test/java/com/jeonseradar/web/region
git commit -m "feat: 지역 페이지 읽기 모델과 랭킹 문턱 설정 추가"
```

---

### Task 6: 지역 페이지와 시도 인덱스 컨트롤러

**Files:**
- Create: `src/main/java/com/jeonseradar/web/region/RegionPageController.java`
- Create: `src/main/resources/templates/region.html`
- Create: `src/main/resources/templates/sido.html`
- Test: `src/test/java/com/jeonseradar/web/region/RegionPageControllerTest.java`

**Interfaces:**
- Consumes: `RegionPageQuery` (Task 5), `SigunguCatalog`, `RegionName`
- Produces: `GET /역전세/{sido}`, `GET /역전세/{sido}/{sigungu}`

**URL에서 코드로 되돌리는 방법.** URL에는 이름이 들어 있고 조회는 코드로 한다. 카탈로그 269행을 순회해 `sido`와 `sigunguSegment`가 일치하는 코드를 찾는다. 269행은 메모리에 있고 요청당 한 번이라 인덱스를 따로 만들 필요가 없다 — 필요해지면 그때 맵을 만든다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/region/RegionPageControllerTest.java`:

```java
package com.jeonseradar.web.region;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RegionPageControllerTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 1, 0);
        snapshots.save(s);
    }

    private void complexIn(String code, long id, String name) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, ?, '잠실동', '40', ?, ?, 2018)
                """, id, code + "-" + id, code, name, id + "-단지-" + code + "-1");
    }

    private void forecast(long complexId, long gap, int contracts, String conf) {
        jdbc.update("""
                INSERT INTO maturity_forecast (snapshot_date, complex_id, area_bucket, maturity_ym,
                        contract_count, estimated_count, median_deposit_then, current_median_deposit,
                        gap_amount, gap_rate, renewal_exhausted_count, confidence)
                VALUES (?, ?, 84, '202612', ?, 0, 1000000000, ?, ?, 0.1, 1, ?)
                """, SNAPSHOT, complexId, contracts, 1_000_000_000L - gap, gap, conf);
    }

    @Test
    void 시군구_페이지가_렌더링된다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(status().isOk())
                .andExpect(view().name("region"))
                .andExpect(content().string(containsString("송파구")))
                .andExpect(content().string(containsString("헬리오시티")));
    }

    @Test
    void 세_토큰_시군구는_하이픈_주소로_열린다() throws Exception {
        complexIn("41111", 1, "장안단지");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/경기도/수원시-장안구"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("수원시 장안구")));
    }

    @Test
    void 없는_지역은_404를_준다() throws Exception {
        mvc.perform(get("/역전세/서울특별시/없는구")).andExpect(status().isNotFound());
    }

    @Test
    void 랭킹이_비면_noindex를_넣는다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 1, "LOW"); // 문턱 미달

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("noindex")));
    }

    @Test
    void 랭킹이_있으면_noindex를_넣지_않는다() throws Exception {
        complexIn("11710", 1, "헬리오시티");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/서울특별시/송파구"))
                .andExpect(content().string(not(containsString("noindex"))));
    }

    @Test
    void 시도_인덱스가_그_시도의_시군구를_나열한다() throws Exception {
        complexIn("11710", 1, "가단지");
        complexIn("11680", 2, "나단지");
        complexIn("41111", 3, "다단지");

        mvc.perform(get("/역전세/서울특별시"))
                .andExpect(status().isOk())
                .andExpect(view().name("sido"))
                .andExpect(content().string(containsString("송파구")))
                .andExpect(content().string(containsString("강남구")))
                .andExpect(content().string(not(containsString("장안구"))));
    }

    @Test
    void 시군구가_없는_시도는_인덱스가_곧_지역_페이지다() throws Exception {
        complexIn("36110", 1, "세종단지");
        forecast(1, 100_000_000L, 5, "MEDIUM");

        mvc.perform(get("/역전세/세종특별자치시"))
                .andExpect(status().isOk())
                .andExpect(view().name("region"))
                .andExpect(content().string(containsString("세종단지")));
    }
}
```

마지막 테스트가 R-3의 근거다. 세종은 시군구가 없어 2세그먼트 주소가 성립하지 않는데, 인덱스 자리에서 지역 페이지를 바로 내주면 특수 주소를 만들지 않아도 된다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*RegionPageControllerTest'`
Expected: FAIL — 매핑 없음 (404)

- [ ] **Step 3: 컨트롤러 작성**

`src/main/java/com/jeonseradar/web/region/RegionPageController.java`:

```java
package com.jeonseradar.web.region;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Controller
public class RegionPageController {

    private final RegionPageQuery query;
    private final SigunguCatalog catalog;

    public RegionPageController(RegionPageQuery query, SigunguCatalog catalog) {
        this.query = query;
        this.catalog = catalog;
    }

    @GetMapping("/역전세/{sido}")
    public String sidoIndex(@PathVariable String sido, Model model) {
        List<String> codes = query.sigunguCodesOf(sido);
        if (codes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 세종특별자치시처럼 시군구가 없는 시도는 인덱스 자리가 곧 지역 페이지다 (원장 R-3).
        if (codes.size() == 1 && !RegionName.of(codes.get(0), catalog.nameOf(codes.get(0))).hasSigungu()) {
            return renderRegion(codes.get(0), model);
        }

        List<RegionName> names = codes.stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .toList();
        model.addAttribute("sido", sido);
        model.addAttribute("regions", names);
        model.addAttribute("robots", null);
        return "sido";
    }

    @GetMapping("/역전세/{sido}/{sigungu}")
    public String region(@PathVariable String sido, @PathVariable String sigungu, Model model) {
        String code = codeOf(sido, sigungu)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return renderRegion(code, model);
    }

    private String renderRegion(String code, Model model) {
        RegionPage page = query.find(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("page", page);
        model.addAttribute("robots", page.indexable() ? null : "noindex, follow");
        return "region";
    }

    /**
     * URL에는 이름이, 조회에는 코드가 필요하다. 카탈로그 269행을 순회해 되돌린다.
     * 요청당 한 번이고 전부 메모리에 있으므로 인덱스를 미리 만들지 않는다.
     */
    private Optional<String> codeOf(String sido, String sigunguSegment) {
        return catalog.all().stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .filter(n -> n.sido().equals(sido) && sigunguSegment.equals(n.sigunguSegment()))
                .map(RegionName::code)
                .findFirst();
    }
}
```

`SigunguCatalog.all()`은 이름이 아니라 **코드 목록**을 돌려준다 (`codeToName.keySet()`, 269개). 위 코드는 그 전제로 쓰여 있으므로 카탈로그를 고칠 일이 없다.

- [ ] **Step 4: 템플릿 작성**

`src/main/resources/templates/region.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title th:text="${page.title()}">지역</title>
  <meta name="robots" th:if="${robots != null}" th:attr="content=${robots}">
  <link rel="canonical" th:href="${page.path()}">
  <link rel="stylesheet" href="/style.css">
</head>
<body>
<div class="wrap">
  <p class="muted"><a th:href="'/역전세/' + ${page.sidoSegment()}" th:text="${page.sido()}">시도</a></p>
  <h1 th:text="${page.regionName()} + ' 전세 만기·역전세 현황'">지역</h1>
  <p class="muted"><span th:text="${page.snapshotDate()}">기준일</span> 기준</p>

  <div class="cards">
    <div class="card"><span class="muted">단지</span>
      <b th:text="${#numbers.formatInteger(page.complexCount(),0,'COMMA')}">0</b></div>
    <div class="card"><span class="muted">만기 예정 계약</span>
      <b th:text="${#numbers.formatInteger(page.totalMaturityContracts(),0,'COMMA')}">0</b></div>
    <div class="card"><span class="muted">역전세 해당 계약</span>
      <b th:text="${#numbers.formatInteger(page.negativeGapContracts(),0,'COMMA')}">0</b></div>
  </div>

  <h2>역전세 갭이 큰 단지</h2>
  <p class="muted" th:if="${page.ranking().isEmpty()}">
    표본이 충분한 역전세 건이 아직 없습니다. 거래가 쌓이면 이곳에 표시됩니다.
  </p>
  <table th:unless="${page.ranking().isEmpty()}">
    <thead>
    <tr><th>단지</th><th>평형</th><th>만기</th><th>계약</th><th>갭</th>
      <th>갱신권 소진</th><th>신뢰도</th></tr>
    </thead>
    <tbody>
    <tr th:each="r : ${page.ranking()}">
      <td><a th:href="${r.path()}" th:text="${r.displayName()}">단지</a>
        <span class="muted" th:text="${r.legalDong()}">동</span></td>
      <td th:text="${r.areaBucket()}">84</td>
      <td th:text="${#strings.substring(r.maturityYm(),0,4)} + '.' + ${#strings.substring(r.maturityYm(),4,6)}">2026.12</td>
      <td th:text="${r.contractCount()} + '건'">0</td>
      <td class="gap-pos"
          th:text="'+' + ${#numbers.formatInteger(r.gapAmount() / 10000, 0, 'COMMA')} + '만원'">0</td>
      <td th:text="${r.renewalExhaustedCount()} + '건'">0</td>
      <td><span class="conf" th:classappend="'conf-' + ${r.confidence()}"
                th:text="${r.confidence()}">MEDIUM</span></td>
    </tr>
    </tbody>
  </table>
  <p class="muted">
    위 목록은 표본이 일정 수준 이상인 건만 보여줍니다. 표본이 적은 건도 각 단지 페이지에서는
    "계약 N건 기준"으로 그대로 확인할 수 있습니다. 위 요약 숫자는 거르지 않은 전체입니다.
  </p>
</div>
</body>
</html>
```

`src/main/resources/templates/sido.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title th:text="${sido} + ' 역전세 현황'">시도</title>
  <meta name="robots" th:if="${robots != null}" th:attr="content=${robots}">
  <link rel="stylesheet" href="/style.css">
</head>
<body>
<div class="wrap">
  <h1 th:text="${sido} + ' 전세 만기·역전세 현황'">시도</h1>
  <ul class="sigungu">
    <li th:each="r : ${regions}">
      <a th:href="'/역전세/' + ${r.sidoSegment()} + '/' + ${r.sigunguSegment()}"
         th:text="${r.displayName()}">시군구</a>
    </li>
  </ul>
</div>
</body>
</html>
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*RegionPageControllerTest'`
Expected: PASS (7개)

- [ ] **Step 6: 전체 스위트와 커밋**

```bash
./gradlew test --rerun-tasks
git add src/main/java/com/jeonseradar/web/region src/main/java/com/jeonseradar/region src/main/resources/templates src/test/java/com/jeonseradar/web/region
git commit -m "feat: 지역 페이지와 시도 인덱스 컨트롤러 추가"
```

---

### Task 7: sitemap.xml과 robots.txt

**Files:**
- Create: `src/main/java/com/jeonseradar/web/SitemapController.java`
- Test: `src/test/java/com/jeonseradar/web/SitemapControllerTest.java`

**Interfaces:**
- Consumes: `PublishedSnapshot`, `JdbcTemplate`, `SigunguCatalog`, `RegionName`
- Produces: `GET /sitemap.xml`, `GET /robots.txt`

**sitemap이 색인 게이트를 다시 구현하면 안 된다.** 판정은 Task 3에서 SQL로 이미 정의됐다. 같은 조건을 두 곳에 쓰면 언젠가 갈라지고, 갈라지면 noindex인 페이지를 sitemap이 제출하는 모순이 생긴다. 동일한 SQL 조건을 한 번만 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/web/SitemapControllerTest.java`:

```java
package com.jeonseradar.web;

import com.jeonseradar.metric.entity.MetricSnapshot;
import com.jeonseradar.metric.repository.MetricSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SitemapControllerTest {

    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 9, 22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricSnapshotRepository snapshots;
    @Autowired com.jeonseradar.web.complex.ComplexPageQuery complexPageQuery;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM maturity_forecast");
        jdbc.update("DELETE FROM complex_rent_stat");
        jdbc.update("DELETE FROM complex");
        snapshots.deleteAll();

        MetricSnapshot s = MetricSnapshot.start(SNAPSHOT);
        s.complete(1, 0, 0);
        snapshots.save(s);
    }

    private void complexWith(long id, String name, String confidence) {
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (?, ?, '11710', '잠실동', '40', ?, ?, 2018)
                """, id, "11710-" + id, name, "c" + id + "-11710-1");
        jdbc.update("""
                INSERT INTO complex_rent_stat (snapshot_date, complex_id, area_bucket,
                        median_deposit, sample_count, source_level, confidence)
                VALUES (?, ?, 84, 950000000, 30, 'L1', ?)
                """, SNAPSHOT, id, confidence);
    }

    @Test
    void robots는_sitemap을_가리킨다() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sitemap:")))
                .andExpect(content().string(containsString("/sitemap.xml")));
    }

    @Test
    void 색인_가능한_단지만_sitemap에_넣는다() throws Exception {
        complexWith(1, "헬리오시티", "HIGH");
        complexWith(2, "리센츠", "LOW");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/c1-11710-1")))
                .andExpect(content().string(not(containsString("/c2-11710-1"))));
    }

    @Test
    void 지번뿐인_이름은_sitemap에_넣지_않는다() throws Exception {
        complexWith(1, "(828-76)", "HIGH");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(not(containsString("/c1-11710-1"))));
    }

    @Test
    void 단지가_있는_지역은_sitemap에_넣는다() throws Exception {
        complexWith(1, "헬리오시티", "HIGH");

        mvc.perform(get("/sitemap.xml"))
                .andExpect(content().string(containsString("/%EC%97%AD%EC%A0%84%EC%84%B8/")
                        .or(containsString("/역전세/"))));
    }

    @Test
    void sitemap_수록_여부가_단지_페이지의_색인_판정과_일치한다() throws Exception {
        // 색인 규칙이 Java(ComplexPageQuery.indexable)와 SQL(여기) 두 곳에 있다 (원장 C-1).
        // 집합 질의가 필요해 SQL을 없앨 수는 없으므로, 둘이 갈라지지 않는지를 대조로 고정한다.
        // 갈라지면 noindex인 페이지를 sitemap이 제출하는 모순이 생긴다.
        complexWith(1, "헬리오시티", "HIGH");     // 색인 대상
        complexWith(2, "리센츠", "MEDIUM");       // 색인 대상
        complexWith(3, "파크리오", "LOW");        // 신뢰도 미달
        complexWith(4, "(828-76)", "HIGH");       // 지번뿐인 이름
        jdbc.update("""
                INSERT INTO complex (id, apt_seq, region_code, legal_dong, jibun,
                                     display_name, slug, build_year)
                VALUES (5, '11710-5', '11710', '잠실동', '40', '시세없음', 'c5-11710-1', 2018)
                """);                            // 시세 자체가 없음

        String xml = mvc.perform(get("/sitemap.xml")).andReturn()
                .getResponse().getContentAsString();

        for (long id = 1; id <= 5; id++) {
            boolean inSitemap = xml.contains("/c" + id + "-11710-1");
            boolean indexable = complexPageQuery.find(id).orElseThrow().indexable();
            org.assertj.core.api.Assertions.assertThat(inSitemap)
                    .as("단지 %d — sitemap 수록과 indexable()이 달라졌다", id)
                    .isEqualTo(indexable);
        }
    }

    @Test
    void 스냅샷이_없으면_503이다() throws Exception {
        snapshots.deleteAll();
        mvc.perform(get("/sitemap.xml")).andExpect(status().isServiceUnavailable());
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*SitemapControllerTest'`
Expected: FAIL — 매핑 없음 (404)

- [ ] **Step 3: 구현**

`src/main/java/com/jeonseradar/web/SitemapController.java`:

```java
package com.jeonseradar.web;

import com.jeonseradar.region.RegionName;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;

@Controller
public class SitemapController {

    private final JdbcTemplate jdbc;
    private final PublishedSnapshot published;
    private final SigunguCatalog catalog;
    private final String baseUrl;

    public SitemapController(JdbcTemplate jdbc, PublishedSnapshot published,
                             SigunguCatalog catalog,
                             @Value("${jeonse.base-url:https://example.invalid}") String baseUrl) {
        this.jdbc = jdbc;
        this.published = published;
        this.catalog = catalog;
        this.baseUrl = baseUrl;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *
                Allow: /

                Sitemap: %s/sitemap.xml
                """.formatted(baseUrl);
    }

    /**
     * 색인 조건은 ComplexPageQuery.indexable()과 **같은 규칙**이어야 한다.
     * 조건이 갈라지면 noindex인 페이지를 sitemap이 제출하는 모순이 생긴다.
     * 여기서는 그 규칙을 SQL로 한 번만 쓴다.
     */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        LocalDate snapshotDate = published.date();

        List<String> complexPaths = jdbc.query("""
                SELECT c.id, c.slug
                FROM complex c
                WHERE c.display_name NOT REGEXP '^\\\\(.*\\\\)$'
                  AND EXISTS (
                    SELECT 1 FROM complex_rent_stat s
                    WHERE s.snapshot_date = ? AND s.complex_id = c.id AND s.confidence <> 'LOW')
                ORDER BY c.id
                """,
                (rs, n) -> "/단지/" + rs.getLong("id") + "-" + rs.getString("slug"),
                snapshotDate);

        List<String> regionPaths = jdbc.queryForList(
                        "SELECT DISTINCT region_code FROM complex ORDER BY region_code", String.class)
                .stream()
                .map(code -> RegionName.of(code, catalog.nameOf(code)))
                .map(n -> n.hasSigungu()
                        ? "/역전세/" + n.sidoSegment() + "/" + n.sigunguSegment()
                        : "/역전세/" + n.sidoSegment())
                .distinct()
                .toList();

        StringBuilder sb = new StringBuilder(
                "<?xml version=\\"1.0\\" encoding=\\"UTF-8\\"?>\\n"
                        + "<urlset xmlns=\\"http://www.sitemaps.org/schemas/sitemap/0.9\\">\\n");
        for (String path : regionPaths) {
            append(sb, path, snapshotDate);
        }
        for (String path : complexPaths) {
            append(sb, path, snapshotDate);
        }
        sb.append("</urlset>\\n");
        return sb.toString();
    }

    private void append(StringBuilder sb, String path, LocalDate lastmod) {
        sb.append("  <url><loc>").append(baseUrl).append(path)
          .append("</loc><lastmod>").append(lastmod).append("</lastmod></url>\\n");
    }
}
```

`application.yml`에 기본 URL을 더한다:

```yaml
jeonse:
  base-url: http://localhost:8080
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*SitemapControllerTest'`
Expected: PASS (6개)

- [ ] **Step 5: 전체 스위트와 커밋**

```bash
./gradlew test --rerun-tasks
git add src/main/java/com/jeonseradar/web src/main/resources/application.yml src/test/java/com/jeonseradar/web
git commit -m "feat: sitemap.xml과 robots.txt 추가"
```

---

## 이 계획이 다루지 않는 것

- **이메일 구독과 주간 다이제스트** (스펙 §4.6) — 4단계.
- **결제·계정** — 스펙 §4.5가 1차 출시에서 명시적으로 제외한다.
- **최근 거래 목록과 매매 검증 플래그** (스펙 §4.4의 무료 항목) — 매매 백필이 끝나야 의미 있는 데이터가 나온다(현재 `deal_flag` 17건). 백필 2일차 완료 후 별도 태스크로 붙인다.
- **F-31 공공·장기전세 오염 필터** — 2단계 원장에 판정만 돼 있고 미구현. 지역 랭킹에 영향을 주므로 2단계 Task 7과 함께 정리한다.
