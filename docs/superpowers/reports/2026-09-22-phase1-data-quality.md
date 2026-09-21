# 1단계 데이터 품질 리포트 (Task 11)

**판정 (부분 결과):** 현재 적재분(강남구·양구군 2개 시군구 × 2개월, 전월세 1,620건) 기준
계약기간 `term_estimated=true` 비율은 **1.7%**로 사전 표본 실측 기준선(4~5%, 26,173건)과
정합하고 스펙 §6의 50% 게이트를 크게 하회한다. 그러나 이 측정은 **전국 백필이 아니라
2개 시군구·2개월분의 비무작위·강남구 편중 부분 표본**에 대한 것이므로, 스펙이 정의한
**전수(全數) 검증 게이트는 여전히 열려 있다 (미확정)**. 이 실행은 사전 표본 실측
(26,173건, 6개 시군구 × 6개월, 채움률 95.6%)을 뒤엎는 것이 아니라 방향을 보강하는
정황 증거일 뿐이다.

## 1. 배경

만기 예측은 `계약기간(contractTerm)` 필드에 의존하며, 이 필드가 없으면 "계약일 + 24개월"로
추정하고 해당 행을 `term_estimated = true`로 표시한다. 스펙 §6은 **추정 비율이 50%를
넘으면 지표 설계 자체를 재검토**하도록 요구한다. 이 태스크는 `DataQualityReporter`를
구현하고, 현재 로컬 MySQL에 적재된 데이터로 이 게이트를 측정한다.

Task 10에서 전국 백필은 실행하지 않았다 — 라이브 API 호출이 하루 20건으로 제한되어,
강남구(11680)·양구군(51800) 2개 시군구 × 2개월(202608, 202609)만 실배치했다. 전국
데이터셋은 궁극적으로 수백만 행 규모가 될 것이므로, 이번 측정은 **그 규모의 극히 일부,
그것도 무작위 표본이 아닌 지역 2곳**에 대한 것이다.

## 2. 구현

- `src/main/java/com/jeonseradar/ingest/service/DataQualityReporter.java` (신규) —
  브리프 코드 그대로 구현. `rent_deal`/`complex` 테이블을 집계해
  `QualityReport(totalRent, estimatedTerm, estimatedRatio, pureJeonse, renewalRightUsed, complexCount)`를
  반환하는 `@Service`.
- `src/test/java/com/jeonseradar/ingest/service/DataQualityReporterTest.java` (신규) —
  브리프 코드 그대로 구현하되, 브리프가 지시한 테스트 격리 조치 중 `@BeforeEach`에서
  `DELETE FROM rent_deal`을 수행하는 방식을 선택했다 (이전 태스크에서 이 테스트 클래스
  계열이 `@Transactional` 없이 행이 누수되는 문제가 실제로 발생한 바 있어, 브리프도 이를
  명시적으로 경고했다).

### TDD 증거

**RED** — `DataQualityReporter`가 존재하지 않는 상태에서 새 테스트 클래스만 추가하고 실행:
```
$ ./gradlew test --tests '*DataQualityReporterTest'
> Task :compileTestJava FAILED
error: cannot find symbol
    DataQualityReporter reporter;
    ^
  symbol:   class DataQualityReporter
```
(그 이전, 테스트 파일 작성 전에는 `No tests found for given includes` — 테스트 자체가
없었음을 먼저 확인했다.)

**GREEN** — `DataQualityReporter` 구현 후:
```
$ ./gradlew test --tests '*DataQualityReporterTest'
BUILD SUCCESSFUL in 51s
```
`build/test-results/test/TEST-com.jeonseradar.ingest.service.DataQualityReporterTest.xml`:
```
tests="4" skipped="0" failures="0" errors="0"
  추정_비율을_계산한다()
  순수전세만_센다()
  갱신권_사용건을_센다()
  데이터가_없으면_비율은_0이다()
```

### 전체 스위트

```
$ ./gradlew test --rerun-tasks
BUILD SUCCESSFUL in 2m 57s
```
`build/test-results/test/TEST-*.xml` 15개 클래스 합산: **tests=115, skipped=0, failures=0, errors=0**
(기존 111 + 신규 4). 종료 시 로그에 보이는 `HikariPool-* Connection is not available` /
`Communications link failure` 경고는 Testcontainers MySQL 컨테이너가 각 테스트 클래스
종료 후 회수되면서 나는 셧다운 훅 잡음이며, `BUILD SUCCESSFUL`과 XML 결과(실패 0/에러 0)로
테스트 자체의 실패가 아님을 확인했다.

## 3. 측정 (부분 표본, 2026-09-22 실행)

로컬 MySQL(`jeonse-mysql` 컨테이너, `--default-character-set=utf8mb4`)에 브리프 Step 5의
SQL을 그대로 실행했다. 이 SQL은 `DataQualityReporter.report()`가 실행하는 쿼리와
필드 단위로 동일하다 (`COUNT(*) FROM rent_deal`, `SUM(term_estimated)`,
`SUM(monthly_rent = 0)`, `SUM(renewal_right_used)`, `COUNT(*) FROM complex`).

```sql
SELECT
  COUNT(*)                                                   AS 전월세_전체,
  SUM(term_estimated)                                        AS 계약기간_추정건,
  ROUND(SUM(term_estimated) / COUNT(*) * 100, 1)             AS 추정비율_퍼센트,
  SUM(monthly_rent = 0)                                      AS 순수전세,
  SUM(renewal_right_used)                                    AS 갱신권_사용,
  ROUND(SUM(renewal_right_used) / SUM(monthly_rent = 0) * 100, 1) AS 전세중_갱신권사용_퍼센트
FROM rent_deal;
```

| 지표 | 값 |
|---|---|
| 전월세 전체 | **1,620** |
| 계약기간 추정건 (`term_estimated = true`) | **28** |
| 추정비율 | **1.7%** |
| 순수전세 (`monthly_rent = 0`) | **779** |
| 갱신권 사용 (`renewal_right_used = true`, 전체 대비) | **396** (396/1,620 = 24.4%) |
| 전세 중 갱신권 사용 비율 (779건 대비) | **50.8%** (브리프 SQL의 분모 기준) |

```sql
SELECT COUNT(*) AS 단지수 FROM complex;
```
| 단지수 |
|---|
| **331** |

```sql
SELECT status, COUNT(*) FROM ingest_log GROUP BY status;
```
| status | 건수 |
|---|---|
| SUCCESS | 8 |

FAILED/PENDING 등 다른 상태는 없음 — Task 10에서 실행한 8건(강남구·양구군 × 2개월 ×
RENT/TRADE)이 전부 SUCCESS다.

### 기대값(사전 조회) 대조

이 태스크를 디스패치하기 전에 컨트롤러가 직접 조회해둔 값과 비교한 결과, **전부 일치**했다.

| 지표 | 기대값 | 실측값 | 일치 |
|---|---|---|---|
| 전월세 전체 | 1,620 | 1,620 | ✅ |
| `term_estimated = true` | 28 (1.7%) | 28 (1.7%) | ✅ |
| 순수 전세 | 779 | 779 | ✅ |
| `renewal_right_used = true` | 396 (24.4%) | 396 (24.4%, 396/1620) | ✅ |
| 단지 수 | 331 | 331 | ✅ |

### 추가로 확인한 정황 (표본 편중과 이미 알려진 갭)

- **지역 분포:** `region_code`별로 보면 11680(강남구) 1,614건, 51800(양구군) 6건.
  즉 이번 부분 표본의 **99.6%가 강남구 한 곳**이다. 추정비율 1.7%는 "전국"이 아니라
  사실상 "강남구 2개월"의 결측률에 가깝다 — 서로 다른 시군구·연식·계약 관행에서
  결측률이 다를 가능성을 이 표본은 배제하지 못한다.
- **NULL 없음:** `term_estimated`, `renewal_right_used` 모두 NULL 0건 — 매퍼가 두 필드를
  항상 채우고 있음을 확인했다 (COUNT 집계가 NULL 누락으로 왜곡될 위험 없음).
- **이미 알려진 `ingest_log` 대 `rent_deal` 갭:** `ingest_log`의 RENT `row_count` 합계는
  **1,657**인데 `rent_deal` 실제 행 수는 **1,620** (차이 2.2%). 이는 이 태스크의 범위가
  아니며 이미 별도로 규명된 사항이다 — `deal_hash`가 동/호수 정보 없이 계산되어(동/호가
  원본 API에 없음) 같은 층·같은 면적·같은 날짜·같은 가격의 서로 다른 두 세대가 하나의
  해시로 합쳐지기 때문이다(스펙 정확도 이슈로 기록됨, 독립 실측 1.7~2.2%와 일치).
  이 갭이 품질 리포터의 세 지표(추정비율/순수전세/갱신권사용) 계산 자체에 영향을 주지는
  않는다 — 리포터는 `rent_deal`에 실제로 적재된 행만 집계하며, 사라진 것으로 추정되는
  나머지 37건이 결측/추정 여부에서 적재분과 크게 다른 분포를 가질 이유는 없다.

## 4. 게이트 판정

브리프 Step 6의 판정 기준(추정비율 ≤10% / 10~50% / >50%)에 이 부분 표본의 값(1.7%)을
대입하면 **≤10% 구간**에 해당하여 "사전 실측과 일치. 2단계 진행, 만기 예측은 스펙 §3.2
그대로 간다"는 판정 규칙에 부합한다. 10~50% 구간에서 요구하는
`contract_term_raw`가 비어있지 않은데 `term_estimated = true`인 행 샘플링 쿼리는
이 구간에 해당하지 않아 실행하지 않았다.

**다만 이 판정은 스펙이 정의한 게이트 자체가 아니다.** 스펙 §6은 "전국 백필 완료 후
전수 재측정"을 요구하며, 이번 실행은:

1. 전국이 아니라 2개 시군구(그마저도 99.6%가 강남구 한 곳)
2. 6개월이 아니라 2개월
3. 궁극적 전국 데이터(수백만 행 추정) 대비 1,620건 (전월세)이라는 매우 작은 규모

라는 세 겹의 제약을 안고 있다. 따라서:

- **전수 게이트는 여전히 열려 있다.** "50% 미만이므로 게이트 통과"라고 말할 수 없다 —
  통과 여부를 판단할 전수 데이터 자체가 아직 없다.
- 현재까지 이 제품 전제("계약기간이 대부분 확정값으로 존재한다")를 뒷받침하는 **가장
  강한 근거는 여전히 사전 표본 실측**(26,173건, 6개 시군구 × 6개월, 채움률 95.6%,
  `ContractTermParser`가 실측 데이터 11,488건에서 미매칭 0건임을 별도 확인)이다.
- 이번 실측(1,620건, 강남구 편중)은 그 사전 표본과 같은 방향(낮은 추정비율)을 가리키는
  **정황 증거(corroboration)**일 뿐, 사전 표본을 대체하거나 전수 게이트를 닫는 근거가
  아니다.
- 2단계 계획을 작성할 때는 이 리포트의 수치를 "전국 확정"이 아니라 "강남구 편중 부분
  표본 + 별도 사전 표본, 둘 다 낮은 추정비율을 가리킴"으로 인용해야 한다. 실제 전국
  백필이 실행되면 이 태스크의 `DataQualityReporter`를 재실행해 전수 게이트를 최종
  판정해야 한다.

## 5. 변경 파일

- `src/main/java/com/jeonseradar/ingest/service/DataQualityReporter.java` (신규)
- `src/test/java/com/jeonseradar/ingest/service/DataQualityReporterTest.java` (신규)
- `docs/superpowers/reports/2026-09-22-phase1-data-quality.md` (신규, 본 문서)

## 6. 셀프 리뷰

- 테스트 격리: 브리프가 명시적으로 경고한 위험(이 테스트 클래스 계열이 `@Transactional`
  없이 행이 누수되어 "expected 1, got 7" 같은 실패가 났던 전례, Task 7에서 실제 발생)을
  반영해 `@BeforeEach`에서 `DELETE FROM rent_deal`을 추가했다. 브리프 본문도 이 방식을
  명시적으로 허용한다. 4개 테스트를 개별 실행과 전체 실행 양쪽에서 확인했고, 실행 순서에
  따라 값이 흔들리지 않음을 XML 결과로 확인했다.
- `DataQualityReporterTest`는 Testcontainers MySQL(일회성 컨테이너)을 쓰므로 로컬
  `jeonse-mysql`(포트 3306, 실데이터)과 완전히 분리되어 있다 — 테스트가 실데이터를
  건드리거나 실데이터가 테스트 결과에 영향을 줄 경로가 없음을 코드로 확인했다(다른
  `@DynamicPropertySource`를 쓰는 이 저장소의 기존 테스트들과 동일 패턴).
- 이 태스크는 라이브 MOLIT API를 호출하지 않았다 — 로컬 MySQL(Testcontainers 및
  `jeonse-mysql` 컨테이너)에 대해서만 쿼리했다.
- **불확실한 점:** "추정비율 1.7%"라는 숫자 자체는 SQL과 리포터 로직 양쪽에서 재현되어
  높은 확신이 있지만, 이 숫자가 **전국 대표값**이라는 주장은 전혀 하지 않는다 — 위
  "게이트 판정" 절에서 반복해 명시했듯, 표본이 지역·규모 양면에서 매우 편중되어 있어
  전국 결측률이 실제로는 이보다 높거나 낮을 가능성을 이 데이터만으로는 배제할 수 없다.
  이 판단은 임의 추측이 아니라 위에서 확인한 `region_code` 분포(강남구 99.6%)에 근거한다.
- `docs/superpowers/reports/` 디렉터리가 이전에는 존재하지 않아 이번에 새로 만들었다
  (기존 `.superpowers/sdd/.../reports/`와는 다른 경로다 — 전자는 브리프가 지정한 커밋
  대상 경로이고, 후자는 gitignore된 태스크 진행 기록 경로다).
