# 1단계: 수집 파이프라인 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 국토교통부 실거래가 API(매매·전월세)에서 전국 실거래 원본을 멱등하게 수집해 MySQL에 적재하고, 계약기간 필드의 실제 결측률을 실측한다.

**Architecture:** `@Scheduled` 배치가 `(시군구코드 × 계약년월 × API종류)` 조합을 순회하며 XML을 받아 파싱하고, 불변 필드로 만든 `deal_hash`를 UNIQUE 키로 upsert한다. 수집 상태는 `ingest_log`에 기록되어 실패분이 익일 재시도 대상으로 남는다. 지표 집계는 2단계 범위이며 이 계획에는 없다.

**Tech Stack:** Spring Boot 3.4.x / JDK 21 / Gradle (Groovy DSL) / MySQL 8.0 / Spring Data JPA / RestClient / JUnit 5 / Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-21-jeonse-maturity-radar-design.md`

## Global Constraints

모든 태스크의 요구사항에 암묵적으로 포함된다.

- **공공데이터만 사용한다.** 네이버 등 크롤링 데이터를 일절 포함하지 않는다 (스펙 §배경-법적 전제).
- 금액은 **`BIGINT`, 원 단위**로 저장한다. 문자열 금액 금지 (스펙 §배경-funapp과 다른 점).
- 전용면적은 **`DECIMAL(10,2)`**로 저장한다.
- `deal_hash`는 **불변 필드로만** 구성한다. `해제여부`·`등기일자`를 포함하지 않는다 (스펙 §1.3).
- 단지 식별 키는 **`(region_code, legal_dong, jibun)`**이다. 아파트명으로 조인하지 않는다 (스펙 §1.4).
- `area_bucket`은 **1㎡ 단위 반올림** INT다 (스펙 §1.5).
- resultCode **`03`(NODATA)은 성공(0건)**으로 기록하고 재시도하지 않는다 (스펙 §1.6).
- resultCode **`22`(쿼터 초과)는 당일 배치 전체를 즉시 중단**한다. 재시도 금지 (스펙 §1.6).
- **네트워크를 타는 테스트를 만들지 않는다.** 실제 응답을 픽스처로 저장해 사용한다 (스펙 §5).
- 커밋 메시지는 한국어 한 줄 요약 + 필요 시 본문. Conventional Commits 접두사(`feat:`, `test:`, `chore:`, `docs:`) 사용.

## API 실측 결과 (2026-09-21, 서비스키로 실제 호출하여 확인)

**이 계획의 필드명과 형식은 추측이 아니라 실측값이다.** 아래는 반드시 그대로 따른다.

- **엔드포인트 유효 확인:** `RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev`,
  `RTMSDataSvcAptRent/getRTMSDataSvcAptRent` 둘 다 정상 응답.
- **정상 응답의 `resultCode`는 `000`** (2자리가 아니라 3자리). 앞자리 0 제거 정규화가 필수다.
- **인증/인프라 오류는 HTTP 403 + `<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>`** 구조로 온다.
  HTTP 상태가 200이 아니라는 점이 핵심이다 — Task 8 참조.
- **`aptSeq`(단지 일련번호, 예 `11110-2519`)가 두 API 모두에 채움률 100%로 존재한다.**
  단지 식별 키는 지번이 아니라 `(sggCd, aptSeq)`다 (스펙 §1.4 개정).
- **`contractTerm` 형식은 `YY.MM~YY.MM`** (예: `25.05~27.05`). `YYYYMM~YYYYMM`은 **0%**, 존재하지 않는다.
- **`useRRRight`는 `"사용"` 또는 공백 두 값뿐이다.** `"미사용"`은 오지 않는다.
- **`rgstDate` 형식은 `yy.MM.dd`** (예: `26.06.30`).
- **`cdealType`/`cdealDay`는 값이 없을 때 빈 문자열이 아니라 공백 한 칸(`" "`)** 으로 온다. 반드시 trim한다.
- **`excluUseAr`는 소수점 4자리까지 온다** (예: `59.8547`). 저장 시 2자리로 반올림한다.
- 실제 응답 픽스처 3종이 이미 캡처되어 있다 (Task 2에서 사용):
  `.superpowers/sdd/2026-09-21-phase1-ingest-pipeline/fixtures/{trade-ok,rent-ok,invalid-key}.xml`

서비스키는 `.env.local`(gitignore됨)의 `MOLIT_SERVICE_KEY`에 있다. **절대 커밋하지 않는다.**

---

### Task 1: 프로젝트 스캐폴딩

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `src/main/java/com/jeonseradar/JeonseRadarApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/jeonseradar/JeonseRadarApplicationTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: Spring Boot 애플리케이션 컨텍스트, Testcontainers MySQL 기반 통합 테스트 환경

- [ ] **Step 1: Gradle 빌드 파일 작성**

`settings.gradle`:

```groovy
rootProject.name = 'jeonse-radar'
```

`build.gradle`:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.jeonseradar'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    runtimeOnly 'com.mysql:mysql-connector-j'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:mysql'
    testImplementation 'org.testcontainers:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
```

`.gitignore`:

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.DS_Store
```

- [ ] **Step 2: Gradle wrapper 생성**

Run: `gradle wrapper --gradle-version 8.11.1`

로컬에 gradle이 없으면 `brew install gradle` 후 실행한다. 생성 후 `gradle/wrapper/` 디렉터리와 `gradlew`가 있어야 한다.

- [ ] **Step 3: 애플리케이션 클래스와 설정 작성**

`src/main/java/com/jeonseradar/JeonseRadarApplication.java`:

```java
package com.jeonseradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JeonseRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(JeonseRadarApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: jeonse-radar
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:jeonse}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true
    username: ${DB_USERNAME:jeonse}
    password: ${DB_PASSWORD:jeonse}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        jdbc:
          batch_size: 500
        order_inserts: true
    open-in-view: false

molit:
  base-url: https://apis.data.go.kr/1613000
  service-key: ${MOLIT_SERVICE_KEY:}
  rows-per-page: 1000
  delay-ms: 120
  max-retries: 3
```

`src/test/resources/application-test.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
molit:
  base-url: http://localhost:1
  service-key: test-key
  rows-per-page: 1000
  delay-ms: 0
  max-retries: 3
```

테스트 프로필의 `base-url`을 도달 불가 주소로 두어, 실수로 네트워크를 타는 테스트가 생기면 즉시 실패하게 한다.

- [ ] **Step 4: 컨텍스트 로딩 테스트 작성**

`src/test/java/com/jeonseradar/JeonseRadarApplicationTest.java`:

```java
package com.jeonseradar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class JeonseRadarApplicationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Test
    void 컨텍스트가_로딩된다() {
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew test`
Expected: PASS. Docker가 떠 있어야 Testcontainers가 MySQL을 띄운다.

- [ ] **Step 6: 커밋**

```bash
git add build.gradle settings.gradle gradle.properties .gitignore gradlew gradlew.bat gradle src
git commit -m "chore: Spring Boot 프로젝트 스캐폴딩 및 Testcontainers 환경 구성"
```

---

### Task 2: 응답 파서 — resultCode 판정

**Files:**
- Create: `src/main/java/com/jeonseradar/ingest/parser/ResultCode.java`
- Create: `src/main/java/com/jeonseradar/ingest/parser/ParsedResponse.java`
- Create: `src/main/java/com/jeonseradar/ingest/parser/MolitResponseParser.java`
- Create: `src/test/resources/fixtures/molit/trade-ok.xml`
- Create: `src/test/resources/fixtures/molit/nodata.xml`
- Create: `src/test/resources/fixtures/molit/quota-exceeded.xml`
- Create: `src/test/resources/fixtures/molit/malformed.xml`
- Test: `src/test/java/com/jeonseradar/ingest/parser/MolitResponseParserTest.java`

**Interfaces:**
- Consumes: Task 1의 프로젝트 구조
- Produces:
  - `enum ResultCode { OK, NODATA, QUOTA_EXCEEDED, INVALID_KEY, ERROR }`
  - `record ParsedResponse(ResultCode code, String rawCode, int totalCount, List<Map<String,String>> items)`
  - `MolitResponseParser.parse(String xml) -> ParsedResponse` (파싱 불가 시 `ParseException` 던짐)

- [ ] **Step 1: 캡처된 실제 응답 픽스처를 테스트 리소스로 복사**

실제 API 호출로 이미 캡처해 둔 응답 3종이 있다. 다시 호출하지 말고 복사한다.

```bash
mkdir -p src/test/resources/fixtures/molit
cp .superpowers/sdd/2026-09-21-phase1-ingest-pipeline/fixtures/trade-ok.xml \
   .superpowers/sdd/2026-09-21-phase1-ingest-pipeline/fixtures/rent-ok.xml \
   .superpowers/sdd/2026-09-21-phase1-ingest-pipeline/fixtures/invalid-key.xml \
   src/test/resources/fixtures/molit/
ls -la src/test/resources/fixtures/molit/
```

세 파일에 서비스키는 들어있지 않다(확인 완료). 커밋해도 안전하다.

- [ ] **Step 2: 픽스처의 실제 구조 확인**

```bash
grep -oE '<(resultCode|returnReasonCode|errMsg|totalCount)>[^<]*<' src/test/resources/fixtures/molit/*.xml
```

확인되는 사실:

- `trade-ok.xml` / `rent-ok.xml` → `<header><resultCode>000</resultCode>`, `<totalCount>` 양수, `<item>` 5개
- `invalid-key.xml` → `<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode>`

**`resultCode`가 `000`(3자리)이고 인증 오류는 `returnReasonCode`(다른 태그, 다른 루트 엘리먼트)에 온다.**
파서는 두 위치를 모두 읽고, 앞자리 0을 제거해 정규화해야 한다.

- [ ] **Step 3: NODATA·쿼터초과 픽스처를 손으로 작성**

이 두 상황은 의도적으로 재현하기 어려우므로 실제 형식에 맞춰 직접 만든다. Step 2에서 확인한 실제 구조와 태그 이름이 다르면 실제 쪽에 맞춘다.

`src/test/resources/fixtures/molit/nodata.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<response>
  <header>
    <resultCode>03</resultCode>
    <resultMsg>NODATA_ERROR</resultMsg>
  </header>
  <body>
    <items/>
    <numOfRows>10</numOfRows>
    <pageNo>1</pageNo>
    <totalCount>0</totalCount>
  </body>
</response>
```

`src/test/resources/fixtures/molit/quota-exceeded.xml` — 실제 캡처한 `invalid-key.xml`과
동일한 구조에 코드만 22로 바꾼 것이다 (구조는 실측 확인됨):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<OpenAPI_ServiceResponse>
<cmmMsgHeader>
  <errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</errMsg>
  <returnAuthMsg>서비스 요청제한횟수 초과에러</returnAuthMsg>
  <returnReasonCode>22</returnReasonCode>
</cmmMsgHeader>
</OpenAPI_ServiceResponse>
```

`src/test/resources/fixtures/molit/malformed.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<response><header><resultCode>00</resultCode>
```

- [ ] **Step 4: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/ingest/parser/MolitResponseParserTest.java`:

```java
package com.jeonseradar.ingest.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MolitResponseParserTest {

    private final MolitResponseParser parser = new MolitResponseParser();

    private String fixture(String name) throws IOException {
        try (var in = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/molit/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 정상응답은_OK와_항목들을_반환한다() throws IOException {
        ParsedResponse result = parser.parse(fixture("trade-ok.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.OK);
        assertThat(result.items()).isNotEmpty();
        assertThat(result.totalCount()).isPositive();
        assertThat(result.items().getFirst()).containsKeys("aptNm", "aptSeq", "sggCd");
    }

    @Test
    void NODATA는_OK가_아니라_NODATA로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("nodata.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.NODATA);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
    }

    @Test
    void 쿼터초과는_cmmMsgHeader에서_읽어_QUOTA_EXCEEDED로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("quota-exceeded.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.QUOTA_EXCEEDED);
    }

    @Test
    void 인증키오류는_INVALID_KEY로_판정된다() throws IOException {
        ParsedResponse result = parser.parse(fixture("invalid-key.xml"));

        assertThat(result.code()).isEqualTo(ResultCode.INVALID_KEY);
    }

    @Test
    void 코드는_앞자리_0을_무시하고_정규화된다() {
        assertThat(ResultCode.from("000")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("00")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("0")).isEqualTo(ResultCode.OK);
        assertThat(ResultCode.from("003")).isEqualTo(ResultCode.NODATA);
        assertThat(ResultCode.from("03")).isEqualTo(ResultCode.NODATA);
    }

    @Test
    void 깨진XML은_ParseException을_던진다() throws IOException {
        String xml = fixture("malformed.xml");

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(MolitResponseParser.ParseException.class);
    }
}
```

- [ ] **Step 5: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*MolitResponseParserTest'`
Expected: FAIL — `ResultCode`, `ParsedResponse`, `MolitResponseParser` 클래스가 없어 컴파일 에러.

- [ ] **Step 6: ResultCode 구현**

`src/main/java/com/jeonseradar/ingest/parser/ResultCode.java`:

```java
package com.jeonseradar.ingest.parser;

public enum ResultCode {
    OK,
    NODATA,
    QUOTA_EXCEEDED,
    INVALID_KEY,
    ERROR;

    /**
     * 공공데이터 API는 같은 의미의 코드를 "00" / "000" / "0"처럼 다른 자리수로 반환한다.
     * 앞자리 0을 제거해 정규화한 뒤 판정한다.
     */
    public static ResultCode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ERROR;
        }
        String normalized = raw.trim().replaceFirst("^0+(?=\\d)", "");
        return switch (normalized) {
            case "0" -> OK;
            case "3" -> NODATA;
            case "22" -> QUOTA_EXCEEDED;
            case "30", "31", "32" -> INVALID_KEY;
            default -> ERROR;
        };
    }
}
```

- [ ] **Step 7: ParsedResponse 구현**

`src/main/java/com/jeonseradar/ingest/parser/ParsedResponse.java`:

```java
package com.jeonseradar.ingest.parser;

import java.util.List;
import java.util.Map;

public record ParsedResponse(
        ResultCode code,
        String rawCode,
        int totalCount,
        List<Map<String, String>> items
) {
    public boolean hasMorePages(int pageNo, int rowsPerPage) {
        return (long) pageNo * rowsPerPage < totalCount;
    }
}
```

- [ ] **Step 8: MolitResponseParser 구현**

`src/main/java/com/jeonseradar/ingest/parser/MolitResponseParser.java`:

```java
package com.jeonseradar.ingest.parser;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MolitResponseParser {

    public static class ParseException extends RuntimeException {
        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ParsedResponse parse(String xml) {
        Document doc = toDocument(xml);

        String rawCode = firstText(doc, "resultCode");
        if (rawCode == null) {
            rawCode = firstText(doc, "returnReasonCode");
        }
        ResultCode code = ResultCode.from(rawCode);

        int totalCount = parseIntOrZero(firstText(doc, "totalCount"));
        List<Map<String, String>> items = extractItems(doc);

        return new ParsedResponse(code, rawCode, totalCount, items);
    }

    private Document toDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ParseException("공공데이터 응답 XML 파싱 실패", e);
        }
    }

    private List<Map<String, String>> extractItems(Document doc) {
        List<Map<String, String>> items = new ArrayList<>();
        NodeList itemNodes = doc.getElementsByTagName("item");

        for (int i = 0; i < itemNodes.getLength(); i++) {
            Node itemNode = itemNodes.item(i);
            if (itemNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            NodeList children = itemNode.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    String value = child.getTextContent();
                    fields.put(child.getNodeName(), value == null ? "" : value.trim());
                }
            }
            items.add(fields);
        }
        return items;
    }

    private String firstText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Element element = (Element) nodes.item(0);
        String text = element.getTextContent();
        return text == null ? null : text.trim();
    }

    private int parseIntOrZero(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `./gradlew test --tests '*MolitResponseParserTest'`
Expected: PASS (6개 테스트)

실패하면 Step 2에서 확인한 실제 태그 이름과 대조한다. **픽스처는 실제 API 응답이므로 진실이고,
구현이 픽스처에 맞춰야 한다.** 픽스처를 고쳐서 테스트를 통과시키는 것은 금지한다.

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/jeonseradar/ingest/parser src/test/java/com/jeonseradar/ingest/parser src/test/resources/fixtures
git commit -m "feat: 공공데이터 응답 파서 및 resultCode 판정 추가"
```

---

### Task 3: 값 변환기 — 금액·면적·지번·날짜

**Files:**
- Create: `src/main/java/com/jeonseradar/ingest/parser/MolitFields.java`
- Create: `src/main/java/com/jeonseradar/ingest/parser/FieldConverter.java`
- Test: `src/test/java/com/jeonseradar/ingest/parser/FieldConverterTest.java`

**Interfaces:**
- Consumes: Task 2의 `ParsedResponse.items()` (`Map<String,String>`)
- Produces:
  - `MolitFields` — API 필드명 상수 (**한 곳에서만 관리**)
  - `FieldConverter.toWon(String) -> long` (만원 단위 문자열 → 원)
  - `FieldConverter.toArea(String) -> BigDecimal`
  - `FieldConverter.toAreaBucket(BigDecimal) -> int`
  - `FieldConverter.toJibun(Map<String,String>) -> String`
  - `FieldConverter.toDealDate(Map<String,String>) -> LocalDate`
  - `FieldConverter.toRegisteredDate(String) -> LocalDate` (없으면 null)

- [ ] **Step 1: 실측 필드명 확인 (대조만, 수정 불필요)**

```bash
python3 -c "
import re
for f in ['trade-ok.xml','rent-ok.xml']:
    xml=open('src/test/resources/fixtures/molit/'+f,encoding='utf-8').read()
    item=re.search(r'<item>(.*?)</item>',xml,re.S)
    print('==',f); print(sorted(set(re.findall(r'<(\w+)>',item.group(1)))))
"
```

**아래 Step 2의 상수는 이미 실제 응답과 대조를 마친 값이다.** 출력과 다른 것이 있으면
그때만 상수를 고친다. 매매/전월세 간 대소문자가 다른 필드가 있으나(`roadNm` vs `roadnm`)
이 서비스가 쓰는 필드는 양쪽 표기가 동일함을 확인했다.

- [ ] **Step 2: 필드명 상수 작성**

`src/main/java/com/jeonseradar/ingest/parser/MolitFields.java`:

```java
package com.jeonseradar.ingest.parser;

/**
 * 국토교통부 실거래가 API 응답 필드명.
 * API 개편으로 바뀔 수 있으므로 필드명 문자열은 이 클래스에만 존재한다.
 * 실제 응답과 다르면 여기만 고친다.
 */
public final class MolitFields {

    private MolitFields() {
    }

    // 공통
    /** 단지 일련번호 (예: 11110-2519). 채움률 100%. 단지 식별의 기준 키다. */
    public static final String APT_SEQ = "aptSeq";
    public static final String SGG_CD = "sggCd";
    public static final String UMD_NM = "umdNm";
    public static final String JIBUN = "jibun";
    public static final String BONBUN = "bonbun";
    public static final String BUBUN = "bubun";
    public static final String APT_NM = "aptNm";
    public static final String EXCLU_USE_AR = "excluUseAr";
    public static final String FLOOR = "floor";
    public static final String BUILD_YEAR = "buildYear";
    public static final String DEAL_YEAR = "dealYear";
    public static final String DEAL_MONTH = "dealMonth";
    public static final String DEAL_DAY = "dealDay";

    // 매매
    public static final String DEAL_AMOUNT = "dealAmount";
    public static final String DEALING_GBN = "dealingGbn";
    public static final String ESTATE_AGENT_SGG_NM = "estateAgentSggNm";
    public static final String RGST_DATE = "rgstDate";
    public static final String CDEAL_TYPE = "cdealType";
    public static final String CDEAL_DAY = "cdealDay";

    // 전월세
    public static final String DEPOSIT = "deposit";
    public static final String MONTHLY_RENT = "monthlyRent";
    public static final String CONTRACT_TERM = "contractTerm";
    public static final String CONTRACT_TYPE = "contractType";
    public static final String USE_RR_RIGHT = "useRRRight";
    public static final String PRE_DEPOSIT = "preDeposit";
    public static final String PRE_MONTHLY_RENT = "preMonthlyRent";
}
```

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/ingest/parser/FieldConverterTest.java`:

```java
package com.jeonseradar.ingest.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldConverterTest {

    @Test
    void 만원단위_콤마_공백_금액을_원단위로_변환한다() {
        assertThat(FieldConverter.toWon("  125,000")).isEqualTo(1_250_000_000L);
        assertThat(FieldConverter.toWon("52,000")).isEqualTo(520_000_000L);
        assertThat(FieldConverter.toWon("0")).isZero();
    }

    @Test
    void 빈금액은_0으로_변환한다() {
        assertThat(FieldConverter.toWon("")).isZero();
        assertThat(FieldConverter.toWon(null)).isZero();
        assertThat(FieldConverter.toWon("   ")).isZero();
    }

    @Test
    void 전용면적은_소수점_둘째자리까지_보존한다() {
        assertThat(FieldConverter.toArea("84.97")).isEqualByComparingTo(new BigDecimal("84.97"));
        assertThat(FieldConverter.toArea(" 59.9 ")).isEqualByComparingTo(new BigDecimal("59.90"));
    }

    @Test
    void 실제응답의_소수점_넷째자리_면적을_둘째자리로_반올림한다() {
        // 실측: excluUseAr = "59.8547" 처럼 4자리로 온다
        assertThat(FieldConverter.toArea("59.8547")).isEqualByComparingTo(new BigDecimal("59.85"));
        assertThat(FieldConverter.toArea("79.87")).isEqualByComparingTo(new BigDecimal("79.87"));
    }

    @Test
    void 공백_한칸으로_오는_값은_비어있는_것으로_다룬다() {
        // 실측: cdealType, cdealDay, preDeposit 등이 "" 가 아니라 " " 로 온다
        assertThat(FieldConverter.toWon(" ")).isZero();
        assertThat(FieldConverter.toRegisteredDate(" ")).isNull();
    }

    @Test
    void 면적버킷은_1제곱미터_단위_반올림이다() {
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.97"))).isEqualTo(85);
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.49"))).isEqualTo(84);
        assertThat(FieldConverter.toAreaBucket(new BigDecimal("84.50"))).isEqualTo(85);
    }

    @Test
    void 같은_평형의_미세한_면적차이는_같은_버킷으로_묶인다() {
        int a = FieldConverter.toAreaBucket(new BigDecimal("84.97"));
        int b = FieldConverter.toAreaBucket(new BigDecimal("84.90"));
        int c = FieldConverter.toAreaBucket(new BigDecimal("85.00"));

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void 지번은_jibun필드를_우선_사용한다() {
        Map<String, String> item = Map.of(MolitFields.JIBUN, "123-4");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123-4");
    }

    @Test
    void jibun이_없으면_본번과_부번을_조합한다() {
        Map<String, String> item = Map.of(
                MolitFields.BONBUN, "0123",
                MolitFields.BUBUN, "0004");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123-4");
    }

    @Test
    void 부번이_0이면_본번만_사용한다() {
        Map<String, String> item = Map.of(
                MolitFields.BONBUN, "0123",
                MolitFields.BUBUN, "0000");

        assertThat(FieldConverter.toJibun(item)).isEqualTo("123");
    }

    @Test
    void 계약일은_연월일_필드를_조립한다() {
        Map<String, String> item = Map.of(
                MolitFields.DEAL_YEAR, "2026",
                MolitFields.DEAL_MONTH, "3",
                MolitFields.DEAL_DAY, "14");

        assertThat(FieldConverter.toDealDate(item)).isEqualTo(LocalDate.of(2026, 3, 14));
    }

    @Test
    void 등기일자가_비어있으면_null이다() {
        assertThat(FieldConverter.toRegisteredDate("")).isNull();
        assertThat(FieldConverter.toRegisteredDate(null)).isNull();
        assertThat(FieldConverter.toRegisteredDate("  ")).isNull();
    }

    @Test
    void 등기일자는_yy_MM_dd_형식을_파싱한다() {
        assertThat(FieldConverter.toRegisteredDate("26.05.20"))
                .isEqualTo(LocalDate.of(2026, 5, 20));
    }
}
```

- [ ] **Step 4: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*FieldConverterTest'`
Expected: FAIL — `FieldConverter` 클래스 없음

- [ ] **Step 5: FieldConverter 구현**

`src/main/java/com/jeonseradar/ingest/parser/FieldConverter.java`:

```java
package com.jeonseradar.ingest.parser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class FieldConverter {

    private static final long MAN_WON = 10_000L;
    private static final DateTimeFormatter RGST_FORMAT = DateTimeFormatter.ofPattern("yy.MM.dd");

    private FieldConverter() {
    }

    /** API 금액은 만원 단위 + 콤마 + 좌측 공백으로 온다. 원 단위 long으로 변환한다. */
    public static long toWon(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String digits = raw.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) {
            return 0L;
        }
        return Long.parseLong(digits) * MAN_WON;
    }

    public static BigDecimal toArea(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
    }

    public static int toAreaBucket(BigDecimal area) {
        return area.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /**
     * 단지 식별 키의 일부. jibun 필드가 있으면 그대로, 없으면 본번-부번을 조합한다.
     * 본번/부번은 4자리 zero-padding 되어 오므로 앞자리 0을 제거한다.
     */
    public static String toJibun(Map<String, String> item) {
        String jibun = item.get(MolitFields.JIBUN);
        if (jibun != null && !jibun.isBlank()) {
            return jibun.trim();
        }
        String bonbun = stripLeadingZeros(item.get(MolitFields.BONBUN));
        String bubun = stripLeadingZeros(item.get(MolitFields.BUBUN));
        if (bonbun.isEmpty()) {
            return "";
        }
        return bubun.isEmpty() || bubun.equals("0") ? bonbun : bonbun + "-" + bubun;
    }

    public static LocalDate toDealDate(Map<String, String> item) {
        int year = parseIntOrZero(item.get(MolitFields.DEAL_YEAR));
        int month = parseIntOrZero(item.get(MolitFields.DEAL_MONTH));
        int day = parseIntOrZero(item.get(MolitFields.DEAL_DAY));
        return LocalDate.of(year, month, day);
    }

    /** 등기일자는 미등기 시 공백으로 온다. 공백은 null(미등기)이며 0건 오류가 아니다. */
    public static LocalDate toRegisteredDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), RGST_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripLeadingZeros(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String stripped = raw.trim().replaceFirst("^0+(?=\\d)", "");
        return stripped.equals("0") ? "0" : stripped;
    }

    private static int parseIntOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Integer.parseInt(raw.trim());
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*FieldConverterTest'`
Expected: PASS (13개 테스트)

`toRegisteredDate`의 `yy.MM.dd` 형식은 실측 확인됐다 (`rgstDate = "26.06.30"`). 그대로 간다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jeonseradar/ingest/parser src/test/java/com/jeonseradar/ingest/parser
git commit -m "feat: 금액/면적/지번/날짜 변환기 및 API 필드명 상수 추가"
```

---

### Task 4: 엔티티와 deal_hash

**Files:**
- Create: `src/main/java/com/jeonseradar/deal/entity/Complex.java`
- Create: `src/main/java/com/jeonseradar/deal/entity/TradeDeal.java`
- Create: `src/main/java/com/jeonseradar/deal/entity/RentDeal.java`
- Create: `src/main/java/com/jeonseradar/deal/service/DealHasher.java`
- Test: `src/test/java/com/jeonseradar/deal/service/DealHasherTest.java`

**Interfaces:**
- Consumes: Task 3의 `FieldConverter`
- Produces:
  - `DealHasher.hashTrade(String regionCode, String legalDong, String jibun, BigDecimal area, int floor, LocalDate dealDate, long dealAmount) -> String`
  - `DealHasher.hashRent(String regionCode, String legalDong, String jibun, BigDecimal area, int floor, LocalDate dealDate, long deposit, long monthlyRent) -> String`
  - 엔티티 `Complex`, `TradeDeal`, `RentDeal` (필드는 스펙 §2와 동일)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/deal/service/DealHasherTest.java`:

```java
package com.jeonseradar.deal.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DealHasherTest {

    private static final String REGION = "11110";
    private static final String DONG = "청운동";
    private static final String JIBUN = "123-4";
    private static final BigDecimal AREA = new BigDecimal("84.97");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 14);

    @Test
    void 같은_입력은_같은_해시를_만든다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 해시는_64자_16진수다() {
        String hash = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 금액이_다르면_다른_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_260_000_000L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 층이_다르면_다른_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 5, DATE, 1_250_000_000L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, AREA, 6, DATE, 1_250_000_000L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 면적_표기가_달라도_수치가_같으면_같은_해시다() {
        String a = DealHasher.hashTrade(REGION, DONG, JIBUN, new BigDecimal("84.97"), 5, DATE, 1L);
        String b = DealHasher.hashTrade(REGION, DONG, JIBUN, new BigDecimal("84.970"), 5, DATE, 1L);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 전월세는_보증금과_월세가_모두_해시에_반영된다() {
        String jeonse = DealHasher.hashRent(REGION, DONG, JIBUN, AREA, 5, DATE, 500_000_000L, 0L);
        String banjeonse = DealHasher.hashRent(REGION, DONG, JIBUN, AREA, 5, DATE, 500_000_000L, 500_000L);

        assertThat(jeonse).isNotEqualTo(banjeonse);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*DealHasherTest'`
Expected: FAIL — `DealHasher` 없음

- [ ] **Step 3: DealHasher 구현**

`src/main/java/com/jeonseradar/deal/service/DealHasher.java`:

```java
package com.jeonseradar.deal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 국토교통부 API는 거래 고유 ID를 제공하지 않으므로 자연키 해시로 거래를 식별한다.
 *
 * 해시에는 절대 변하지 않는 필드만 넣는다.
 * 등기일자(rgstDate)와 해제여부(cdealType)는 나중에 값이 바뀌므로 포함하면 안 된다.
 * 포함하면 값이 바뀔 때마다 새 행이 생겨 같은 거래가 중복된다.
 */
public final class DealHasher {

    private static final String SEP = "|";

    private DealHasher() {
    }

    public static String hashTrade(String regionCode, String legalDong, String jibun,
                                   BigDecimal area, int floor, LocalDate dealDate,
                                   long dealAmount) {
        return sha256(String.join(SEP,
                "TRADE", nz(regionCode), nz(legalDong), nz(jibun),
                normalizeArea(area), String.valueOf(floor),
                dealDate.toString(), String.valueOf(dealAmount)));
    }

    public static String hashRent(String regionCode, String legalDong, String jibun,
                                  BigDecimal area, int floor, LocalDate dealDate,
                                  long deposit, long monthlyRent) {
        return sha256(String.join(SEP,
                "RENT", nz(regionCode), nz(legalDong), nz(jibun),
                normalizeArea(area), String.valueOf(floor),
                dealDate.toString(), String.valueOf(deposit), String.valueOf(monthlyRent)));
    }

    /** 84.97과 84.970이 같은 해시를 내도록 소수점 둘째자리로 고정한다. */
    private static String normalizeArea(BigDecimal area) {
        return area.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*DealHasherTest'`
Expected: PASS (6개 테스트)

- [ ] **Step 5: Complex 엔티티 작성**

`src/main/java/com/jeonseradar/deal/entity/Complex.java`:

```java
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
```

- [ ] **Step 6: TradeDeal 엔티티 작성**

`src/main/java/com/jeonseradar/deal/entity/TradeDeal.java`:

```java
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
```

- [ ] **Step 6b: RentDeal 엔티티 작성**

`src/main/java/com/jeonseradar/deal/entity/RentDeal.java`:

```java
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
```

- [ ] **Step 7: 스키마 생성 확인**

Run: `./gradlew test --tests '*JeonseRadarApplicationTest'`
Expected: PASS. Hibernate가 `complex`, `trade_deal`, `rent_deal` 테이블을 만들고 컨텍스트가 뜬다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jeonseradar/deal src/test/java/com/jeonseradar/deal
git commit -m "feat: 거래/단지 엔티티 및 불변필드 기반 deal_hash 추가"
```

---

### Task 5: 계약기간 파서

**Files:**
- Create: `src/main/java/com/jeonseradar/deal/service/ContractTermParser.java`
- Test: `src/test/java/com/jeonseradar/deal/service/ContractTermParserTest.java`

**Interfaces:**
- Consumes: 없음 (순수 함수)
- Produces: `ContractTermParser.parse(String raw, LocalDate dealDate) -> ContractTerm`
  - `record ContractTerm(String startYm, String endYm, boolean estimated)` — `startYm`/`endYm`은 `yyyyMM` 6자리

**실측 형식 (2026-09-21, 18,287건).** 이 표가 이 태스크의 요구사항이다.

| 입력 형식 | 실측 비율 | 예 | 처리 |
|---|---|---|---|
| `YY.MM~YY.MM` | 73.6% | `25.05~27.05` | 종료월을 만기월로 |
| 공백 / 공백 한 칸 | 26.0% | `" "` | 계약일 + 24개월 추정 |
| `~YY.MM` (시작 없음) | 0.4% | `~26.03` | **종료월은 유효 — 그대로 사용** |
| `YYYYMM~YYYYMM` | **0%** | — | 존재하지 않는다 |

2자리 연도는 `2000 + yy`로 해석한다 (실측 분포 21~27).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/deal/service/ContractTermParserTest.java`:

```java
package com.jeonseradar.deal.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTermParserTest {

    private static final LocalDate DEAL_DATE = LocalDate.of(2026, 3, 14);

    @Test
    void 실측형식_YY점MM을_파싱한다() {
        var term = ContractTermParser.parse("25.05~27.05", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202505");
        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 두자리연도는_2000년대로_해석한다() {
        var term = ContractTermParser.parse("21.11~23.11", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202111");
        assertThat(term.endYm()).isEqualTo("202311");
    }

    @Test
    void 공백이_섞여도_파싱한다() {
        var term = ContractTermParser.parse("  25.05 ~ 27.05  ", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 시작월이_없고_종료월만_있으면_종료월을_그대로_쓴다() {
        // 실측 0.4%: "~26.03"
        var term = ContractTermParser.parse("~26.03", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202603");
        assertThat(term.startYm()).isNull();
        assertThat(term.estimated()).isFalse();
    }

    @Test
    void 공백_한칸이면_추정한다() {
        // 실측: 결측은 "" 이 아니라 " " 로 온다
        var term = ContractTermParser.parse(" ", DEAL_DATE);

        assertThat(term.startYm()).isEqualTo("202603");
        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void null이면_추정한다() {
        var term = ContractTermParser.parse(null, DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 형식이_깨졌으면_추정한다() {
        var term = ContractTermParser.parse("계약기간없음", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202803");
        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 존재하지_않는_월이면_추정으로_떨어진다() {
        var term = ContractTermParser.parse("25.05~27.13", DEAL_DATE);

        assertThat(term.estimated()).isTrue();
        assertThat(term.endYm()).isEqualTo("202803");
    }

    @Test
    void 종료가_시작보다_이르면_추정으로_떨어진다() {
        var term = ContractTermParser.parse("27.05~25.05", DEAL_DATE);

        assertThat(term.estimated()).isTrue();
    }

    @Test
    void 구형식_YYYYMM은_실제로_오지_않지만_와도_추정으로_떨어지지_않는다() {
        // 방어적: 과거 문서에 나오는 형식. 실측 0%지만 오면 읽어준다.
        var term = ContractTermParser.parse("202505~202705", DEAL_DATE);

        assertThat(term.endYm()).isEqualTo("202705");
        assertThat(term.estimated()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*ContractTermParserTest'`
Expected: FAIL — `ContractTermParser` 없음

- [ ] **Step 3: 구현**

`src/main/java/com/jeonseradar/deal/service/ContractTermParser.java`:

```java
package com.jeonseradar.deal.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전월세 API의 계약기간 필드를 만기월로 변환한다.
 *
 * 실측(2026-09-21, 18,287건) 형식 분포:
 *   YY.MM~YY.MM   73.6%  (예: "25.05~27.05")
 *   공백           26.0%  (공백 한 칸으로 온다)
 *   ~YY.MM          0.4%  (시작월 없이 종료월만 — 종료월은 유효하다)
 *   YYYYMM~YYYYMM   0.0%  (존재하지 않지만 방어적으로 읽는다)
 *
 * 결측·형식오류 시 주택임대차보호법 기본 2년으로 추정하되 반드시 estimated=true를 남긴다.
 * 추정치를 확정치처럼 노출하면 한 번 틀렸을 때 서비스 신뢰가 사라진다 (스펙 §3.2).
 */
public final class ContractTermParser {

    /** "25.05~27.05" — 시작월은 선택적이다 ("~26.03" 형태가 실측 0.4% 존재) */
    private static final Pattern SHORT_TERM =
            Pattern.compile("^\\s*(?:(\\d{2})\\.(\\d{2}))?\\s*~\\s*(\\d{2})\\.(\\d{2})\\s*$");

    /** "202505~202705" — 실측 0%지만 방어적으로 지원 */
    private static final Pattern LONG_TERM =
            Pattern.compile("^\\s*(\\d{6})\\s*~\\s*(\\d{6})\\s*$");

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int DEFAULT_MONTHS = 24;
    private static final int CENTURY = 2000;

    public record ContractTerm(String startYm, String endYm, boolean estimated) {
    }

    private ContractTermParser() {
    }

    public static ContractTerm parse(String raw, LocalDate dealDate) {
        if (raw == null || raw.isBlank()) {
            return estimate(dealDate);
        }

        Matcher shortMatch = SHORT_TERM.matcher(raw);
        if (shortMatch.matches()) {
            YearMonth start = shortMatch.group(1) == null
                    ? null
                    : toYearMonth(CENTURY + Integer.parseInt(shortMatch.group(1)),
                                  Integer.parseInt(shortMatch.group(2)));
            YearMonth end = toYearMonth(CENTURY + Integer.parseInt(shortMatch.group(3)),
                                        Integer.parseInt(shortMatch.group(4)));
            return build(start, end, shortMatch.group(1) != null, dealDate);
        }

        Matcher longMatch = LONG_TERM.matcher(raw);
        if (longMatch.matches()) {
            YearMonth start = toYearMonth(longMatch.group(1));
            YearMonth end = toYearMonth(longMatch.group(2));
            return build(start, end, true, dealDate);
        }

        return estimate(dealDate);
    }

    /**
     * 종료월을 얻었으면 확정으로 본다. 시작월은 없어도 무방하다 —
     * 만기 예측에 필요한 것은 종료월뿐이다.
     */
    private static ContractTerm build(YearMonth start, YearMonth end,
                                      boolean startRequired, LocalDate dealDate) {
        if (end == null || (startRequired && start == null)) {
            return estimate(dealDate);
        }
        if (start != null && end.isBefore(start)) {
            return estimate(dealDate);
        }
        return new ContractTerm(start == null ? null : start.format(YM), end.format(YM), false);
    }

    private static ContractTerm estimate(LocalDate dealDate) {
        YearMonth start = YearMonth.from(dealDate);
        YearMonth end = start.plusMonths(DEFAULT_MONTHS);
        return new ContractTerm(start.format(YM), end.format(YM), true);
    }

    private static YearMonth toYearMonth(int year, int month) {
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static YearMonth toYearMonth(String ym) {
        try {
            return YearMonth.parse(ym, YM);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*ContractTermParserTest'`
Expected: PASS (10개 테스트)

- [ ] **Step 5: 실제 픽스처로 교차 확인**

실제 응답의 `contractTerm` 값이 파서를 통과하는지 눈으로 확인한다.

```bash
grep -oE '<contractTerm>[^<]*</contractTerm>' src/test/resources/fixtures/molit/rent-ok.xml
```

픽스처 5건은 모두 공백일 수 있다(실측 표본에서 그랬다). 그 경우 이 스텝은 "공백 → 추정"
경로만 확인하는 것으로 충분하다. Step 1의 단위 테스트가 나머지 형식을 덮는다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/jeonseradar/deal/service/ContractTermParser.java src/test/java/com/jeonseradar/deal/service/ContractTermParserTest.java
git commit -m "feat: 계약기간 파서 추가 (실측 YY.MM~YY.MM 형식, 결측 시 24개월 추정)"
```

---

### Task 6: 단지 정규화 — aptSeq 기준 병합

**Files:**
- Create: `src/main/java/com/jeonseradar/deal/repository/ComplexRepository.java`
- Create: `src/main/java/com/jeonseradar/deal/service/SlugGenerator.java`
- Create: `src/main/java/com/jeonseradar/deal/service/ComplexResolver.java`
- Test: `src/test/java/com/jeonseradar/deal/service/ComplexResolverTest.java`

**Interfaces:**
- Consumes: Task 4의 `Complex` 엔티티
- Produces: `ComplexResolver.resolve(String regionCode, String aptSeq, String legalDong, String jibun, String aptName, Integer buildYear) -> Long` (complexId 반환, 없으면 생성)

**왜 지번이 아니라 aptSeq인가 (2026-09-21 실측, 전월세 17,532건):**

| 항목 | 결과 |
|---|---|
| `aptSeq` 채움률 | 100% (매매·전월세 양쪽) |
| 한 지번에 서로 다른 aptSeq 2개 이상 | **43건** — 지번으로 묶으면 다른 단지가 합쳐진다 |
| 한 aptSeq가 여러 지번에 걸침 | 0건 |
| 한 aptSeq에 서로 다른 아파트명 | **0건** |

마지막 줄이 중요하다. aptSeq 안에서는 이름이 흔들리지 않으므로 **최빈값 수렴 로직이 필요 없다.**
처음 본 이름을 그대로 쓰면 된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/deal/service/ComplexResolverTest.java`:

```java
package com.jeonseradar.deal.service;

import com.jeonseradar.deal.repository.ComplexRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class ComplexResolverTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    ComplexResolver resolver;

    @Autowired
    ComplexRepository repository;

    @Test
    void 같은_aptSeq는_같은_단지다() {
        Long first = resolver.resolve("11110", "11110-2519", "무악동", "89", "경희궁롯데캐슬", 2019);
        Long second = resolver.resolve("11110", "11110-2519", "무악동", "89", "경희궁롯데캐슬", 2019);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 아파트명_표기가_달라도_aptSeq가_같으면_같은_단지다() {
        Long a = resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이(3단지)", 2017);
        Long b = resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이 3단지", 2017);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 같은_지번이라도_aptSeq가_다르면_다른_단지로_분리된다() {
        // 실측 43건의 사례. 지번 기준이었다면 두 단지가 하나로 합쳐졌을 것이다.
        // 예: 강남구 개포동 12 -> 11680-279, 11680-280
        Long a = resolver.resolve("11680", "11680-279", "개포동", "12", "개포주공1단지", 1982);
        Long b = resolver.resolve("11680", "11680-280", "개포동", "12", "개포주공2단지", 1982);

        assertThat(a).isNotEqualTo(b);
        assertThat(repository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 시군구가_다르면_다른_단지다() {
        Long a = resolver.resolve("11110", "11110-1", "중앙동", "1", "한화아파트", 2010);
        Long b = resolver.resolve("11170", "11170-1", "중앙동", "1", "한화아파트", 2010);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 표시명은_처음_본_이름을_유지한다() {
        Long id = resolver.resolve("11110", "11110-91", "창신동", "703", "창신쌍용2", 1993);
        resolver.resolve("11110", "11110-91", "창신동", "703", "창신쌍용2", 1993);

        assertThat(repository.findById(id).orElseThrow().getDisplayName())
                .isEqualTo("창신쌍용2");
    }

    @Test
    void slug는_단지마다_고유하다() {
        Long a = resolver.resolve("11110", "11110-500", "청운동", "200", "자이", 2015);
        Long b = resolver.resolve("11110", "11110-501", "청운동", "201", "자이", 2015);

        String slugA = repository.findById(a).orElseThrow().getSlug();
        String slugB = repository.findById(b).orElseThrow().getSlug();

        assertThat(slugA).isNotEqualTo(slugB);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*ComplexResolverTest'`
Expected: FAIL — `ComplexResolver`, `ComplexRepository` 없음

- [ ] **Step 3: Repository 작성**

`src/main/java/com/jeonseradar/deal/repository/ComplexRepository.java`:

```java
package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.Complex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ComplexRepository extends JpaRepository<Complex, Long> {

    Optional<Complex> findByRegionCodeAndAptSeq(String regionCode, String aptSeq);

    boolean existsBySlug(String slug);
}
```

- [ ] **Step 4: SlugGenerator 작성**

`src/main/java/com/jeonseradar/deal/service/SlugGenerator.java`:

```java
package com.jeonseradar.deal.service;

import java.text.Normalizer;
import java.util.Locale;

public final class SlugGenerator {

    private SlugGenerator() {
    }

    /**
     * URL 경로용 slug. 한글은 유지하고(검색엔진이 처리 가능) 공백·특수문자만 정리한다.
     * aptSeq를 뒤에 붙여 같은 이름의 다른 단지를 구분한다.
     */
    public static String generate(String aptName, String aptSeq) {
        String base = Normalizer.normalize(aptName == null ? "" : aptName, Normalizer.Form.NFC)
                .toLowerCase(Locale.KOREAN)
                .replaceAll("[^0-9a-z가-힣]+", "-")
                .replaceAll("(^-|-$)", "");
        String suffix = (aptSeq == null ? "" : aptSeq)
                .replaceAll("[^0-9a-z가-힣-]+", "-")
                .replaceAll("(^-|-$)", "");
        String slug = base.isEmpty() ? suffix : base + "-" + suffix;
        return slug.length() > 150 ? slug.substring(0, 150) : slug;
    }
}
```

- [ ] **Step 5: ComplexResolver 작성**

`src/main/java/com/jeonseradar/deal/service/ComplexResolver.java`:

```java
package com.jeonseradar.deal.service;

import com.jeonseradar.deal.entity.Complex;
import com.jeonseradar.deal.repository.ComplexRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단지 식별은 아파트명도 지번도 아닌 aptSeq 기준이다.
 *
 * 아파트명은 두 API 간 표기가 흔들리고, 지번은 서로 다른 단지 여럿을 한 값으로 합쳐버린다
 * (실측 43건). aptSeq는 채움률 100%이고 한 값이 여러 지번에 걸치거나 이름이 흔들리는
 * 사례가 실측 0건이므로, 최빈값 수렴 같은 보정 로직 없이 그대로 키로 쓸 수 있다 (스펙 §1.4).
 */
@Service
public class ComplexResolver {

    private final ComplexRepository repository;

    public ComplexResolver(ComplexRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Long resolve(String regionCode, String aptSeq, String legalDong, String jibun,
                        String aptName, Integer buildYear) {
        return repository.findByRegionCodeAndAptSeq(regionCode, aptSeq)
                .orElseGet(() -> repository.save(new Complex(
                        regionCode, aptSeq, legalDong, jibun,
                        aptName, buildYear,
                        uniqueSlug(aptName, aptSeq))))
                .getId();
    }

    private String uniqueSlug(String aptName, String aptSeq) {
        String slug = SlugGenerator.generate(aptName, aptSeq);
        if (!repository.existsBySlug(slug)) {
            return slug;
        }
        int suffix = 2;
        while (repository.existsBySlug(slug + "-" + suffix)) {
            suffix++;
        }
        return slug + "-" + suffix;
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*ComplexResolverTest'`
Expected: PASS (6개 테스트)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jeonseradar/deal src/test/java/com/jeonseradar/deal
git commit -m "feat: aptSeq 기준 단지 정규화 추가"
```

---

### Task 7: 멱등 저장 — upsert

**Files:**
- Create: `src/main/java/com/jeonseradar/deal/repository/TradeDealRepository.java`
- Create: `src/main/java/com/jeonseradar/deal/repository/RentDealRepository.java`
- Create: `src/main/java/com/jeonseradar/deal/repository/DealUpsertDao.java`
- Test: `src/test/java/com/jeonseradar/deal/repository/DealUpsertDaoTest.java`

**Interfaces:**
- Consumes: Task 4의 엔티티, Task 6의 `ComplexResolver`
- Produces:
  - `DealUpsertDao.upsertTrades(List<TradeDeal>) -> int` (영향 행 수)
  - `DealUpsertDao.upsertRents(List<RentDeal>) -> int`

- [ ] **Step 1: 실패하는 테스트 작성**

이 테스트가 스펙 §1.3의 핵심 결정을 고정한다. **같은 거래 재수집 시 행이 늘지 않고, 가변 필드만 갱신되어야 한다.**

`src/test/java/com/jeonseradar/deal/repository/DealUpsertDaoTest.java`:

```java
package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.TradeDeal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class DealUpsertDaoTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    DealUpsertDao dao;

    @Autowired
    TradeDealRepository repository;

    /** 테스트 픽스처. TradeDeal.of 는 Task 4에서 이미 만들어져 있다. */
    private TradeDeal sample(String hash, String cancelYn, LocalDate registeredDate) {
        return TradeDeal.of(
                hash, 1L, "11110-2446", "11110", "평동", "233", "경희궁자이(3단지)",
                new java.math.BigDecimal("59.85"), 60, 2,
                LocalDate.of(2026, 3, 7), 2_300_000_000L, 2017,
                "중개거래", "서울 종로구", registeredDate, cancelYn, null);
    }

    @Test
    void 같은_거래를_두번_수집해도_행이_늘지_않는다() {
        dao.upsertTrades(List.of(sample("hash-a", "N", null)));
        dao.upsertTrades(List.of(sample("hash-a", "N", null)));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 해제여부가_바뀌면_기존_행이_갱신된다() {
        dao.upsertTrades(List.of(sample("hash-b", "N", null)));
        dao.upsertTrades(List.of(sample("hash-b", "O", null)));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByDealHash("hash-b").orElseThrow().getCancelYn())
                .isEqualTo("O");
    }

    @Test
    void 등기일자가_나중에_채워지면_갱신된다() {
        dao.upsertTrades(List.of(sample("hash-c", "N", null)));
        dao.upsertTrades(List.of(sample("hash-c", "N", LocalDate.of(2026, 5, 20))));

        assertThat(repository.findByDealHash("hash-c").orElseThrow().getRegisteredDate())
                .isEqualTo(LocalDate.of(2026, 5, 20));
    }

    @Test
    void 서로_다른_해시는_별개_행이다() {
        dao.upsertTrades(List.of(sample("hash-d", "N", null), sample("hash-e", "N", null)));

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void 배치_중간에_중복이_있어도_전체가_저장된다() {
        dao.upsertTrades(List.of(
                sample("hash-f", "N", null),
                sample("hash-f", "N", null),
                sample("hash-g", "N", null)));

        assertThat(repository.count()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*DealUpsertDaoTest'`
Expected: FAIL — `DealUpsertDao`, `findByDealHash` 없음 (`TradeDeal.of`는 Task 4에서 이미 존재)

- [ ] **Step 3: Task 4의 TradeDeal 정적 팩토리·getter 확인**

`TradeDeal.of(...)`와 getter들은 Task 4에서 이미 작성되어 있다. 새로 추가할 것은 없다.
아래만 확인하고 넘어간다.

```bash
grep -n 'public static TradeDeal of' src/main/java/com/jeonseradar/deal/entity/TradeDeal.java
grep -c 'public .* get' src/main/java/com/jeonseradar/deal/entity/TradeDeal.java
```

`of`가 없거나 인자 수가 18개(`dealHash, complexId, aptSeq, regionCode, legalDong, jibun,
aptName, area, areaBucket, floor, dealDate, dealAmount, buildYear, dealType, agentRegion,
registeredDate, cancelYn, cancelDate`)가 아니면 Task 4의 엔티티 정의와 대조해 맞춘다.

- [ ] **Step 4: Repository 작성**

`src/main/java/com/jeonseradar/deal/repository/TradeDealRepository.java`:

```java
package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.TradeDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeDealRepository extends JpaRepository<TradeDeal, Long> {
    Optional<TradeDeal> findByDealHash(String dealHash);
}
```

`src/main/java/com/jeonseradar/deal/repository/RentDealRepository.java`:

```java
package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.RentDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RentDealRepository extends JpaRepository<RentDeal, Long> {
    Optional<RentDeal> findByDealHash(String dealHash);
}
```

- [ ] **Step 5: DealUpsertDao 구현**

JPA `save()`는 매 건 SELECT를 발생시켜 수백만 행 백필에 부적합하다. JDBC 배치 upsert를 쓴다.

`src/main/java/com/jeonseradar/deal/repository/DealUpsertDao.java`:

```java
package com.jeonseradar.deal.repository;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.entity.TradeDeal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * deal_hash UNIQUE + ON DUPLICATE KEY UPDATE로 멱등 저장한다.
 *
 * 갱신 대상은 가변 필드(등기일자, 해제여부, 해제일)뿐이다.
 * 나머지는 deal_hash를 구성하는 불변 필드이므로 값이 같음이 보장된다 (스펙 §1.3).
 */
@Repository
public class DealUpsertDao {

    private static final String TRADE_SQL = """
            INSERT INTO trade_deal
              (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun, apt_name,
               area, area_bucket, floor, deal_date, deal_amount, build_year,
               deal_type, agent_region, registered_date, cancel_yn, cancel_date,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              registered_date = VALUES(registered_date),
              cancel_yn       = VALUES(cancel_yn),
              cancel_date     = VALUES(cancel_date),
              complex_id      = VALUES(complex_id),
              updated_at      = NOW()
            """;

    private final JdbcTemplate jdbcTemplate;

    public DealUpsertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int upsertTrades(List<TradeDeal> deals) {
        if (deals.isEmpty()) {
            return 0;
        }
        int[] affected = jdbcTemplate.batchUpdate(TRADE_SQL, deals, deals.size(),
                (PreparedStatement ps, TradeDeal d) -> {
                    ps.setString(1, d.getDealHash());
                    setLongOrNull(ps, 2, d.getComplexId());
                    ps.setString(3, d.getAptSeq());
                    ps.setString(4, d.getRegionCode());
                    ps.setString(5, d.getLegalDong());
                    ps.setString(6, d.getJibun());
                    ps.setString(7, d.getAptName());
                    ps.setBigDecimal(8, d.getArea());
                    ps.setInt(9, d.getAreaBucket());
                    ps.setInt(10, d.getFloor());
                    ps.setDate(11, Date.valueOf(d.getDealDate()));
                    ps.setLong(12, d.getDealAmount());
                    setIntOrNull(ps, 13, d.getBuildYear());
                    ps.setString(14, d.getDealType());
                    ps.setString(15, d.getAgentRegion());
                    setDateOrNull(ps, 16, d.getRegisteredDate());
                    ps.setString(17, d.getCancelYn());
                    setDateOrNull(ps, 18, d.getCancelDate());
                })[0].length;
        return affected;
    }

    @Transactional
    public int upsertRents(List<RentDeal> deals) {
        throw new UnsupportedOperationException("Step 6에서 구현");
    }

    private void setLongOrNull(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private void setIntOrNull(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setDateOrNull(PreparedStatement ps, int index, java.time.LocalDate value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
        }
    }
}
```

`batchUpdate`의 반환 타입 처리가 Spring 버전에 따라 다르면, 단순히 `jdbcTemplate.batchUpdate(sql, batchArgsSetter)`를 쓰고 `deals.size()`를 반환해도 이 계획의 테스트는 통과한다. 행 수 정확도는 1단계에서 중요하지 않다.

- [ ] **Step 6: upsertRents 구현**

`DealUpsertDao`에 상수와 메서드를 추가하고, Step 5의 `UnsupportedOperationException` 본문을 교체한다.

```java
    private static final String RENT_SQL = """
            INSERT INTO rent_deal
              (deal_hash, complex_id, apt_seq, region_code, legal_dong, jibun, apt_name,
               area, area_bucket, floor, deal_date, deposit, monthly_rent, build_year,
               contract_type, contract_term_raw, term_start_ym, term_end_ym,
               term_estimated, renewal_right_used, prev_deposit, prev_monthly_rent,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              complex_id = VALUES(complex_id),
              updated_at = NOW()
            """;

    @Transactional
    public int upsertRents(List<RentDeal> deals) {
        if (deals.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate(RENT_SQL, deals, deals.size(),
                (PreparedStatement ps, RentDeal d) -> {
                    ps.setString(1, d.getDealHash());
                    setLongOrNull(ps, 2, d.getComplexId());
                    ps.setString(3, d.getAptSeq());
                    ps.setString(4, d.getRegionCode());
                    ps.setString(5, d.getLegalDong());
                    ps.setString(6, d.getJibun());
                    ps.setString(7, d.getAptName());
                    ps.setBigDecimal(8, d.getArea());
                    ps.setInt(9, d.getAreaBucket());
                    ps.setInt(10, d.getFloor());
                    ps.setDate(11, Date.valueOf(d.getDealDate()));
                    ps.setLong(12, d.getDeposit());
                    ps.setLong(13, d.getMonthlyRent());
                    setIntOrNull(ps, 14, d.getBuildYear());
                    ps.setString(15, d.getContractType());
                    ps.setString(16, d.getContractTermRaw());
                    ps.setString(17, d.getTermStartYm());
                    ps.setString(18, d.getTermEndYm());
                    ps.setBoolean(19, Boolean.TRUE.equals(d.getTermEstimated()));
                    ps.setBoolean(20, Boolean.TRUE.equals(d.getRenewalRightUsed()));
                    setLongOrNull(ps, 21, d.getPrevDeposit());
                    setLongOrNull(ps, 22, d.getPrevMonthlyRent());
                });
        return deals.size();
    }
```

**`ON DUPLICATE KEY UPDATE` 절에 `complex_id`와 `updated_at`만 있는 것은 의도적이다.**
전월세에는 매매의 등기일자·해제여부처럼 나중에 바뀌는 필드가 없다. 나머지 컬럼은
모두 `deal_hash`를 구성하는 불변 필드이므로 값이 같음이 보장된다.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests '*DealUpsertDaoTest'`
Expected: PASS (5개 테스트)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jeonseradar/deal src/test/java/com/jeonseradar/deal
git commit -m "feat: deal_hash 기반 멱등 upsert 추가 (가변 필드만 갱신)"
```

---

### Task 8: API 클라이언트와 항목 매퍼

**Files:**
- Create: `src/main/java/com/jeonseradar/ingest/client/ApiType.java`
- Create: `src/main/java/com/jeonseradar/ingest/client/MolitProperties.java`
- Create: `src/main/java/com/jeonseradar/ingest/client/MolitApiClient.java`
- Create: `src/main/java/com/jeonseradar/ingest/mapper/TradeDealMapper.java`
- Create: `src/main/java/com/jeonseradar/ingest/mapper/RentDealMapper.java`
- Create: `src/main/java/com/jeonseradar/config/RestClientConfig.java`
- Test: `src/test/java/com/jeonseradar/ingest/mapper/TradeDealMapperTest.java`
- Test: `src/test/java/com/jeonseradar/ingest/mapper/RentDealMapperTest.java`

**Interfaces:**
- Consumes: Task 2 파서, Task 3 변환기, Task 4 해셔·엔티티, Task 5 계약기간 파서, Task 6 `ComplexResolver`
- Produces:
  - `enum ApiType { TRADE, RENT }` — `path()` 반환
  - `MolitApiClient.fetch(ApiType, String lawdCd, String dealYmd) -> ParsedResponse` (전 페이지 병합)
  - `TradeDealMapper.map(Map<String,String>) -> TradeDeal`
  - `RentDealMapper.map(Map<String,String>) -> RentDeal`

- [ ] **Step 1: 실패하는 매퍼 테스트 작성**

`src/test/java/com/jeonseradar/ingest/mapper/TradeDealMapperTest.java`:

```java
package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.TradeDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.ingest.parser.MolitFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeDealMapperTest {

    private final ComplexResolver resolver = mock(ComplexResolver.class);
    private final TradeDealMapper mapper = new TradeDealMapper(resolver);

    @BeforeEach
    void setUp() {
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(7L);
    }

    private Map<String, String> item() {
        // 실측 응답(trade-ok.xml)의 첫 item을 그대로 옮긴 값
        Map<String, String> item = new HashMap<>();
        item.put(MolitFields.APT_SEQ, "11110-2446");
        item.put(MolitFields.SGG_CD, "11110");
        item.put(MolitFields.UMD_NM, "평동");
        item.put(MolitFields.JIBUN, "233");
        item.put(MolitFields.APT_NM, "경희궁자이(3단지)");
        item.put(MolitFields.EXCLU_USE_AR, "59.8547");
        item.put(MolitFields.FLOOR, "2");
        item.put(MolitFields.BUILD_YEAR, "2017");
        item.put(MolitFields.DEAL_YEAR, "2026");
        item.put(MolitFields.DEAL_MONTH, "3");
        item.put(MolitFields.DEAL_DAY, "7");
        item.put(MolitFields.DEAL_AMOUNT, "230,000");
        item.put(MolitFields.DEALING_GBN, "중개거래");
        item.put(MolitFields.ESTATE_AGENT_SGG_NM, "서울 종로구");
        item.put(MolitFields.RGST_DATE, "26.06.30");
        item.put(MolitFields.CDEAL_TYPE, " ");
        item.put(MolitFields.CDEAL_DAY, " ");
        return item;
    }

    @Test
    void 금액을_원단위로_매핑한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: dealAmount "230,000" (만원) -> 23억
        assertThat(deal.getDealAmount()).isEqualTo(2_300_000_000L);
    }

    @Test
    void 소수점_넷째자리_면적을_버킷으로_반올림한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: excluUseAr "59.8547" -> 버킷 60
        assertThat(deal.getAreaBucket()).isEqualTo(60);
    }

    @Test
    void 계약일을_조립한다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getDealDate()).isEqualTo(LocalDate.of(2026, 3, 7));
    }

    @Test
    void 등기일자를_yy점MM점dd_형식으로_파싱한다() {
        TradeDeal deal = mapper.map(item());

        // 실측: rgstDate "26.06.30"
        assertThat(deal.getRegisteredDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void 등기일자가_공백_한칸이면_미등기로_남는다() {
        Map<String, String> item = item();
        item.put(MolitFields.RGST_DATE, " ");

        TradeDeal deal = mapper.map(item);

        assertThat(deal.getRegisteredDate()).isNull();
    }

    @Test
    void 해제여부가_공백_한칸이면_빈값으로_정규화된다() {
        // 실측: cdealType 은 값이 없을 때 "" 이 아니라 " " 로 온다
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getCancelYn()).isEmpty();
    }

    @Test
    void 해제여부가_O면_그대로_저장한다() {
        Map<String, String> item = item();
        item.put(MolitFields.CDEAL_TYPE, "O");
        item.put(MolitFields.CDEAL_DAY, "26.04.01");

        TradeDeal deal = mapper.map(item);

        assertThat(deal.getCancelYn()).isEqualTo("O");
        assertThat(deal.getCancelDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void aptSeq를_보존한다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getAptSeq()).isEqualTo("11110-2446");
    }

    @Test
    void 면적은_소수점_둘째자리로_저장된다() {
        TradeDeal deal = mapper.map(item());

        assertThat(deal.getArea()).isEqualByComparingTo(new java.math.BigDecimal("59.85"));
    }

    @Test
    void complexId는_aptSeq로_조회한_ComplexResolver_결과다() {
        when(resolver.resolve("11110", "11110-2446", "평동", "233", "경희궁자이(3단지)", 2017))
                .thenReturn(42L);

        TradeDeal deal = mapper.map(item());

        assertThat(deal.getComplexId()).isEqualTo(42L);
    }
}
```

`build.gradle`의 `spring-boot-starter-test`에 Mockito가 포함되어 있으므로 추가 의존성은 필요 없다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*TradeDealMapperTest'`
Expected: FAIL — `TradeDealMapper` 없음

- [ ] **Step 3: TradeDealMapper 구현**

`src/main/java/com/jeonseradar/ingest/mapper/TradeDealMapper.java`:

```java
package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.TradeDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.deal.service.DealHasher;
import com.jeonseradar.ingest.parser.FieldConverter;
import com.jeonseradar.ingest.parser.MolitFields;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Component
public class TradeDealMapper {

    private final ComplexResolver complexResolver;

    public TradeDealMapper(ComplexResolver complexResolver) {
        this.complexResolver = complexResolver;
    }

    public TradeDeal map(Map<String, String> item) {
        String aptSeq = text(item, MolitFields.APT_SEQ);
        String regionCode = text(item, MolitFields.SGG_CD);
        String legalDong = text(item, MolitFields.UMD_NM);
        String jibun = FieldConverter.toJibun(item);
        String aptName = text(item, MolitFields.APT_NM);
        Integer buildYear = intOrNull(item.get(MolitFields.BUILD_YEAR));

        BigDecimal area = FieldConverter.toArea(item.get(MolitFields.EXCLU_USE_AR));
        int floor = intOrZero(item.get(MolitFields.FLOOR));
        LocalDate dealDate = FieldConverter.toDealDate(item);
        long amount = FieldConverter.toWon(item.get(MolitFields.DEAL_AMOUNT));

        Long complexId = complexResolver.resolve(
                regionCode, aptSeq, legalDong, jibun, aptName, buildYear);
        String hash = DealHasher.hashTrade(regionCode, legalDong, jibun, area, floor, dealDate, amount);

        return TradeDeal.of(
                hash, complexId, aptSeq, regionCode, legalDong, jibun, aptName,
                area, FieldConverter.toAreaBucket(area), floor,
                dealDate, amount, buildYear,
                text(item, MolitFields.DEALING_GBN),
                text(item, MolitFields.ESTATE_AGENT_SGG_NM),
                FieldConverter.toRegisteredDate(item.get(MolitFields.RGST_DATE)),
                text(item, MolitFields.CDEAL_TYPE),
                FieldConverter.toRegisteredDate(item.get(MolitFields.CDEAL_DAY)));
    }

    private String text(Map<String, String> item, String key) {
        String value = item.get(key);
        return value == null ? "" : value.trim();
    }

    private Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intOrZero(String raw) {
        Integer value = intOrNull(raw);
        return value == null ? 0 : value;
    }
}
```

- [ ] **Step 4a: RentDealMapper 실패 테스트 작성**

`src/test/java/com/jeonseradar/ingest/mapper/RentDealMapperTest.java`:

```java
package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.ingest.parser.MolitFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RentDealMapperTest {

    private final ComplexResolver resolver = mock(ComplexResolver.class);
    private final RentDealMapper mapper = new RentDealMapper(resolver);

    @BeforeEach
    void setUp() {
        when(resolver.resolve(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(7L);
    }

    private Map<String, String> item() {
        // 실측 응답(rent-ok.xml)의 첫 item을 그대로 옮긴 값.
        // 실측에서 contractTerm/useRRRight/preDeposit 은 값이 없을 때 " " 로 온다.
        Map<String, String> item = new HashMap<>();
        item.put(MolitFields.APT_SEQ, "11110-2519");
        item.put(MolitFields.SGG_CD, "11110");
        item.put(MolitFields.UMD_NM, "무악동");
        item.put(MolitFields.JIBUN, "89");
        item.put(MolitFields.APT_NM, "경희궁롯데캐슬");
        item.put(MolitFields.EXCLU_USE_AR, "59.6737");
        item.put(MolitFields.FLOOR, "11");
        item.put(MolitFields.BUILD_YEAR, "2019");
        item.put(MolitFields.DEAL_YEAR, "2026");
        item.put(MolitFields.DEAL_MONTH, "3");
        item.put(MolitFields.DEAL_DAY, "19");
        item.put(MolitFields.DEPOSIT, "71,400");
        item.put(MolitFields.MONTHLY_RENT, "0");
        item.put(MolitFields.CONTRACT_TYPE, "신규");
        item.put(MolitFields.CONTRACT_TERM, "26.03~28.03");
        item.put(MolitFields.USE_RR_RIGHT, " ");
        item.put(MolitFields.PRE_DEPOSIT, " ");
        item.put(MolitFields.PRE_MONTHLY_RENT, " ");
        return item;
    }

    @Test
    void 보증금을_원단위로_매핑한다() {
        RentDeal deal = mapper.map(item());

        // 실측: deposit "71,400" (만원) -> 7억 1,400만원
        assertThat(deal.getDeposit()).isEqualTo(714_000_000L);
    }

    @Test
    void 월세가_0이면_순수전세로_저장된다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getMonthlyRent()).isZero();
    }

    @Test
    void 반전세는_월세가_원단위로_들어간다() {
        Map<String, String> item = item();
        item.put(MolitFields.MONTHLY_RENT, "80");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getMonthlyRent()).isEqualTo(800_000L);
    }

    @Test
    void 갱신요구권_사용이면_true다() {
        Map<String, String> item = item();
        item.put(MolitFields.USE_RR_RIGHT, "사용");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getRenewalRightUsed()).isTrue();
    }

    @Test
    void 갱신요구권_값이_비면_false다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getRenewalRightUsed()).isFalse();
    }

    @Test
    void 계약기간이_정상이면_만기월을_읽고_estimated가_false다() {
        RentDeal deal = mapper.map(item());

        // 실측 형식 "26.03~28.03" -> 만기 202803
        assertThat(deal.getTermEndYm()).isEqualTo("202803");
        assertThat(deal.getTermEstimated()).isFalse();
    }

    @Test
    void 계약기간이_공백_한칸이면_계약일_더하기_24개월로_추정하고_estimated가_true다() {
        // 실측: 결측은 "" 이 아니라 " " 로 온다
        Map<String, String> item = item();
        item.put(MolitFields.CONTRACT_TERM, " ");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getTermEndYm()).isEqualTo("202803");
        assertThat(deal.getTermEstimated()).isTrue();
    }

    @Test
    void 계약기간_원본문자열을_보존한다() {
        RentDeal deal = mapper.map(item());

        // 파싱 결과가 아니라 원본을 그대로 남긴다 — 결측률 실측(Task 11)의 근거가 된다
        assertThat(deal.getContractTermRaw()).isEqualTo("26.03~28.03");
    }

    @Test
    void 종전계약금액이_비면_null이다() {
        RentDeal deal = mapper.map(item());

        assertThat(deal.getPrevDeposit()).isNull();
        assertThat(deal.getPrevMonthlyRent()).isNull();
    }

    @Test
    void 종전계약보증금이_있으면_원단위로_매핑한다() {
        Map<String, String> item = item();
        item.put(MolitFields.PRE_DEPOSIT, "45,000");

        RentDeal deal = mapper.map(item);

        assertThat(deal.getPrevDeposit()).isEqualTo(450_000_000L);
    }
}
```

`계약기간이_정상이면...`과 `계약기간이_공백_한칸이면...`이 같은 `202803`을 기대하는 것은 우연이 아니다.
계약일이 2026-03이고 계약기간이 `26.03~28.03`이므로 추정값과 실제값이 일치한다.
**`estimated` 플래그가 두 경우를 구분하는 유일한 근거이며, 그래서 이 플래그를 반드시 저장해야 한다.**
`contractTermRaw` 원본 보존도 같은 이유다 — Task 11의 결측률 실측이 이 컬럼에 의존한다.

- [ ] **Step 4b: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*RentDealMapperTest'`
Expected: FAIL — `RentDealMapper` 없음

- [ ] **Step 4c: RentDealMapper 구현**

`src/main/java/com/jeonseradar/ingest/mapper/RentDealMapper.java`:

```java
package com.jeonseradar.ingest.mapper;

import com.jeonseradar.deal.entity.RentDeal;
import com.jeonseradar.deal.service.ComplexResolver;
import com.jeonseradar.deal.service.ContractTermParser;
import com.jeonseradar.deal.service.DealHasher;
import com.jeonseradar.ingest.parser.FieldConverter;
import com.jeonseradar.ingest.parser.MolitFields;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Component
public class RentDealMapper {

    private static final String RENEWAL_USED = "사용";

    private final ComplexResolver complexResolver;

    public RentDealMapper(ComplexResolver complexResolver) {
        this.complexResolver = complexResolver;
    }

    public RentDeal map(Map<String, String> item) {
        String aptSeq = text(item, MolitFields.APT_SEQ);
        String regionCode = text(item, MolitFields.SGG_CD);
        String legalDong = text(item, MolitFields.UMD_NM);
        String jibun = FieldConverter.toJibun(item);
        String aptName = text(item, MolitFields.APT_NM);
        Integer buildYear = intOrNull(item.get(MolitFields.BUILD_YEAR));

        BigDecimal area = FieldConverter.toArea(item.get(MolitFields.EXCLU_USE_AR));
        int floor = intOrZero(item.get(MolitFields.FLOOR));
        LocalDate dealDate = FieldConverter.toDealDate(item);

        long deposit = FieldConverter.toWon(item.get(MolitFields.DEPOSIT));
        long monthlyRent = FieldConverter.toWon(item.get(MolitFields.MONTHLY_RENT));

        String termRaw = text(item, MolitFields.CONTRACT_TERM);
        var term = ContractTermParser.parse(termRaw, dealDate);

        Long complexId = complexResolver.resolve(
                regionCode, aptSeq, legalDong, jibun, aptName, buildYear);
        String hash = DealHasher.hashRent(
                regionCode, legalDong, jibun, area, floor, dealDate, deposit, monthlyRent);

        return RentDeal.of(
                hash, complexId, aptSeq, regionCode, legalDong, jibun, aptName,
                area, FieldConverter.toAreaBucket(area), floor,
                dealDate, deposit, monthlyRent, buildYear,
                text(item, MolitFields.CONTRACT_TYPE),
                termRaw, term.startYm(), term.endYm(), term.estimated(),
                RENEWAL_USED.equals(text(item, MolitFields.USE_RR_RIGHT)),
                wonOrNull(item.get(MolitFields.PRE_DEPOSIT)),
                wonOrNull(item.get(MolitFields.PRE_MONTHLY_RENT)));
    }

    private String text(Map<String, String> item, String key) {
        String value = item.get(key);
        return value == null ? "" : value.trim();
    }

    /** 종전계약 금액은 신규 계약이면 비어 있다. 0원과 "없음"을 구분해야 하므로 null을 쓴다. */
    private Long wonOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return FieldConverter.toWon(raw);
    }

    private Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intOrZero(String raw) {
        Integer value = intOrNull(raw);
        return value == null ? 0 : value;
    }
}
```

- [ ] **Step 4d: 테스트 통과 확인**

Run: `./gradlew test --tests '*RentDealMapperTest'`
Expected: PASS (10개 테스트)

전월세 API에는 `aptSeq`가 있으나 매매와 달리 `bonbun`/`bubun`이 없고 `jibun`만 온다
(실측 확인). `FieldConverter.toJibun`이 `jibun` 우선이므로 그대로 동작한다.

- [ ] **Step 5: ApiType과 설정 프로퍼티 작성**

`src/main/java/com/jeonseradar/ingest/client/ApiType.java`:

```java
package com.jeonseradar.ingest.client;

public enum ApiType {

    TRADE("RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev"),
    RENT("RTMSDataSvcAptRent/getRTMSDataSvcAptRent");

    private final String path;

    ApiType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
```

Task 2 Step 1에서 확인한 실제 요청주소와 다르면 여기를 고친다.

`src/main/java/com/jeonseradar/ingest/client/MolitProperties.java`:

```java
package com.jeonseradar.ingest.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "molit")
public record MolitProperties(
        String baseUrl,
        String serviceKey,
        int rowsPerPage,
        long delayMs,
        int maxRetries
) {
}
```

`JeonseRadarApplication`에 `@ConfigurationPropertiesScan`을 추가한다.

- [ ] **Step 6: RestClient 설정과 클라이언트 작성**

`src/main/java/com/jeonseradar/config/RestClientConfig.java`:

```java
package com.jeonseradar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory).build();
    }
}
```

`src/main/java/com/jeonseradar/ingest/client/MolitApiClient.java`:

```java
package com.jeonseradar.ingest.client;

import com.jeonseradar.ingest.parser.MolitResponseParser;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MolitApiClient {

    private final RestClient restClient;
    private final MolitResponseParser parser;
    private final MolitProperties properties;

    public MolitApiClient(RestClient restClient, MolitResponseParser parser,
                          MolitProperties properties) {
        this.restClient = restClient;
        this.parser = parser;
        this.properties = properties;
    }

    /**
     * (시군구, 계약년월) 한 쌍의 전 페이지를 받아 하나의 ParsedResponse로 합친다.
     * OK가 아닌 코드를 만나면 즉시 그 응답을 그대로 반환한다 — 판정은 호출자 책임이다.
     */
    public ParsedResponse fetch(ApiType apiType, String lawdCd, String dealYmd) {
        List<Map<String, String>> all = new ArrayList<>();
        int pageNo = 1;
        ParsedResponse page;

        do {
            page = fetchPage(apiType, lawdCd, dealYmd, pageNo);
            if (page.code() != ResultCode.OK) {
                return page;
            }
            all.addAll(page.items());
            pageNo++;
            sleep();
        } while (page.hasMorePages(pageNo - 1, properties.rowsPerPage()));

        return new ParsedResponse(ResultCode.OK, page.rawCode(), page.totalCount(), all);
    }

    private ParsedResponse fetchPage(ApiType apiType, String lawdCd, String dealYmd, int pageNo) {
        String uri = UriComponentsBuilder
                .fromUriString(properties.baseUrl())
                .pathSegment(apiType.path().split("/"))
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("LAWD_CD", lawdCd)
                .queryParam("DEAL_YMD", dealYmd)
                .queryParam("numOfRows", properties.rowsPerPage())
                .queryParam("pageNo", pageNo)
                .build(true)
                .toUriString();

        // 공공 API는 인증 오류·쿼터 초과를 HTTP 403 등 4xx로 반환하면서 본문에
        // <cmmMsgHeader><returnReasonCode>로 진짜 원인을 담아 보낸다 (2026-09-21 실측).
        // retrieve()의 기본 동작은 4xx/5xx에서 예외를 던지는 것이라, 그대로 두면 본문이
        // 파서에 도달하지 못하고 INVALID_KEY/QUOTA_EXCEEDED가 일반 예외로 뭉개져
        // "즉시 중단" 대신 재시도로 빠진다 — 스펙 §1.6 위반.
        // 따라서 상태 코드로는 절대 실패시키지 않고, 언제나 본문의 resultCode로 판정한다.
        String body = restClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .body(String.class);
        return parser.parse(body == null ? "" : body);
    }

    private void sleep() {
        if (properties.delayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`serviceKey`는 이미 URL 인코딩된 값으로 발급되는 경우가 있다. `build(true)`는 재인코딩을 막는다.
`.env.local`의 `MOLIT_SERVICE_KEY`는 **디코딩된 형태**이며 실제 호출로 동작이 확인됐다.

**`onStatus(status -> true, ...)`를 빼면 안 된다.** 실측에서 인증 오류는 HTTP 403으로 오고,
쿼터 초과도 같은 경로를 탄다. 이 핸들러가 없으면 파서가 본문을 보지 못해
쿼터가 소진된 뒤에도 배치가 멈추지 않고 계속 API를 호출한다.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS (전체)

`MolitApiClient`에는 단위 테스트를 만들지 않는다 — 네트워크 의존이므로 Task 9의 오케스트레이션 테스트에서 mock으로 검증한다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/jeonseradar src/test/java/com/jeonseradar
git commit -m "feat: 국토교통부 API 클라이언트 및 거래 항목 매퍼 추가"
```

---

### Task 9: 수집 오케스트레이션과 ingest_log

**Files:**
- Create: `src/main/java/com/jeonseradar/ingest/entity/IngestLog.java`
- Create: `src/main/java/com/jeonseradar/ingest/entity/IngestStatus.java`
- Create: `src/main/java/com/jeonseradar/ingest/repository/IngestLogRepository.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/QuotaExceededException.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/InvalidKeyException.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/IngestService.java`
- Test: `src/test/java/com/jeonseradar/ingest/service/IngestServiceTest.java`

**Interfaces:**
- Consumes: Task 8 `MolitApiClient`·매퍼, Task 7 `DealUpsertDao`
- Produces: `IngestService.ingestOne(ApiType, String lawdCd, String dealYmd) -> IngestResult`
  - `record IngestResult(IngestStatus status, int rowCount, String resultCode)`

- [ ] **Step 1: 실패하는 테스트 작성**

이 테스트가 스펙 §1.6의 핵심 결정을 고정한다.

`src/test/java/com/jeonseradar/ingest/service/IngestServiceTest.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.MolitResponseParser;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    MolitApiClient client;
    TradeDealMapper tradeMapper;
    RentDealMapper rentMapper;
    DealUpsertDao upsertDao;
    IngestLogRepository logRepository;
    IngestService service;

    @BeforeEach
    void setUp() {
        client = mock(MolitApiClient.class);
        tradeMapper = mock(TradeDealMapper.class);
        rentMapper = mock(RentDealMapper.class);
        upsertDao = mock(DealUpsertDao.class);
        logRepository = mock(IngestLogRepository.class);
        when(logRepository.findByApiTypeAndRegionCodeAndDealYm(any(), anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        service = new IngestService(client, tradeMapper, rentMapper, upsertDao, logRepository, 3);
    }

    private ParsedResponse response(ResultCode code, int itemCount) {
        List<Map<String, String>> items = new java.util.ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(Map.of("aptNm", "단지" + i));
        }
        return new ParsedResponse(code, "x", itemCount, items);
    }

    @Test
    void NODATA는_실패가_아니라_0건_성공이다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.NODATA, 0));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void NODATA는_재시도하지_않는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.NODATA, 0));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        verify(client, times(1)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 쿼터초과는_예외를_던져_배치를_중단시킨다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.QUOTA_EXCEEDED, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void 쿼터초과는_재시도하지_않는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.QUOTA_EXCEEDED, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(QuotaExceededException.class);

        verify(client, times(1)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 인증키오류는_예외를_던진다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.INVALID_KEY, 0));

        assertThatThrownBy(() -> service.ingestOne(ApiType.TRADE, "11110", "202603"))
                .isInstanceOf(InvalidKeyException.class);
    }

    @Test
    void 일시오류는_최대_3회_재시도한_뒤_FAILED로_남는다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.ERROR, 0));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.FAILED);
        verify(client, times(3)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 파싱실패도_재시도_대상이다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenThrow(new MolitResponseParser.ParseException("깨짐", new RuntimeException()));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.FAILED);
        verify(client, times(3)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 재시도_도중_성공하면_SUCCESS다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.ERROR, 0))
                .thenReturn(response(ResultCode.OK, 2));

        var result = service.ingestOne(ApiType.TRADE, "11110", "202603");

        assertThat(result.status()).isEqualTo(IngestStatus.SUCCESS);
        assertThat(result.rowCount()).isEqualTo(2);
        verify(client, times(2)).fetch(any(), anyString(), anyString());
    }

    @Test
    void 정상응답은_매퍼를_거쳐_upsert된다() {
        when(client.fetch(any(), anyString(), anyString()))
                .thenReturn(response(ResultCode.OK, 3));

        service.ingestOne(ApiType.TRADE, "11110", "202603");

        verify(tradeMapper, times(3)).map(any());
        verify(upsertDao, times(1)).upsertTrades(any());
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*IngestServiceTest'`
Expected: FAIL — `IngestService` 등 없음

- [ ] **Step 3: 상태 enum과 예외 작성**

`src/main/java/com/jeonseradar/ingest/entity/IngestStatus.java`:

```java
package com.jeonseradar.ingest.entity;

public enum IngestStatus {
    PENDING,
    SUCCESS,
    FAILED
}
```

`src/main/java/com/jeonseradar/ingest/service/QuotaExceededException.java`:

```java
package com.jeonseradar.ingest.service;

/** 일일 트래픽 초과. 재시도하면 상황이 악화되므로 당일 배치 전체를 중단시킨다 (스펙 §1.6). */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
```

`src/main/java/com/jeonseradar/ingest/service/InvalidKeyException.java`:

```java
package com.jeonseradar.ingest.service;

/** 서비스키 오류. 재시도해도 무의미하므로 즉시 중단한다 (스펙 §1.6). */
public class InvalidKeyException extends RuntimeException {
    public InvalidKeyException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: IngestLog 엔티티와 Repository 작성**

`src/main/java/com/jeonseradar/ingest/entity/IngestLog.java`:

```java
package com.jeonseradar.ingest.entity;

import com.jeonseradar.ingest.client.ApiType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ingest_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ingest_target",
                columnNames = {"apiType", "regionCode", "dealYm"}))
public class IngestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private ApiType apiType;

    @Column(length = 5, nullable = false)
    private String regionCode;

    @Column(length = 6, nullable = false)
    private String dealYm;

    @Enumerated(EnumType.STRING)
    @Column(length = 8, nullable = false)
    private IngestStatus status;

    private Integer rowCount;

    @Column(length = 8)
    private String resultCode;

    private Integer attempts;

    private LocalDateTime lastAttemptAt;

    protected IngestLog() {
    }

    public IngestLog(ApiType apiType, String regionCode, String dealYm) {
        this.apiType = apiType;
        this.regionCode = regionCode;
        this.dealYm = dealYm;
        this.status = IngestStatus.PENDING;
        this.attempts = 0;
        this.rowCount = 0;
    }

    public void record(IngestStatus status, int rowCount, String resultCode, int attempts) {
        this.status = status;
        this.rowCount = rowCount;
        this.resultCode = resultCode;
        this.attempts = attempts;
        this.lastAttemptAt = LocalDateTime.now();
    }

    public IngestStatus getStatus() {
        return status;
    }

    public Integer getRowCount() {
        return rowCount;
    }
}
```

`src/main/java/com/jeonseradar/ingest/repository/IngestLogRepository.java`:

```java
package com.jeonseradar.ingest.repository;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngestLogRepository extends JpaRepository<IngestLog, Long> {

    Optional<IngestLog> findByApiTypeAndRegionCodeAndDealYm(
            ApiType apiType, String regionCode, String dealYm);

    List<IngestLog> findByStatus(IngestStatus status);

    long countByStatus(IngestStatus status);
}
```

- [ ] **Step 5: IngestService 구현**

`src/main/java/com/jeonseradar/ingest/service/IngestService.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.deal.repository.DealUpsertDao;
import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.client.MolitApiClient;
import com.jeonseradar.ingest.entity.IngestLog;
import com.jeonseradar.ingest.entity.IngestStatus;
import com.jeonseradar.ingest.mapper.RentDealMapper;
import com.jeonseradar.ingest.mapper.TradeDealMapper;
import com.jeonseradar.ingest.parser.ParsedResponse;
import com.jeonseradar.ingest.parser.ResultCode;
import com.jeonseradar.ingest.repository.IngestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record IngestResult(IngestStatus status, int rowCount, String resultCode) {
    }

    private final MolitApiClient client;
    private final TradeDealMapper tradeMapper;
    private final RentDealMapper rentMapper;
    private final DealUpsertDao upsertDao;
    private final IngestLogRepository logRepository;
    private final int maxRetries;

    public IngestService(MolitApiClient client,
                         TradeDealMapper tradeMapper,
                         RentDealMapper rentMapper,
                         DealUpsertDao upsertDao,
                         IngestLogRepository logRepository,
                         @Value("${molit.max-retries}") int maxRetries) {
        this.client = client;
        this.tradeMapper = tradeMapper;
        this.rentMapper = rentMapper;
        this.upsertDao = upsertDao;
        this.logRepository = logRepository;
        this.maxRetries = maxRetries;
    }

    public IngestResult ingestOne(ApiType apiType, String lawdCd, String dealYmd) {
        IngestLog logEntry = logRepository
                .findByApiTypeAndRegionCodeAndDealYm(apiType, lawdCd, dealYmd)
                .orElseGet(() -> new IngestLog(apiType, lawdCd, dealYmd));

        String lastCode = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ParsedResponse response = client.fetch(apiType, lawdCd, dealYmd);
                lastCode = response.rawCode();

                switch (response.code()) {
                    case OK -> {
                        int saved = save(apiType, response);
                        return finish(logEntry, IngestStatus.SUCCESS, saved, lastCode, attempt);
                    }
                    // 거래가 없는 지역은 0건이 정상이다. 실패로 남기면 재시도 큐가
                    // 영원히 비워지지 않는다 (스펙 §1.6).
                    case NODATA -> {
                        return finish(logEntry, IngestStatus.SUCCESS, 0, lastCode, attempt);
                    }
                    case QUOTA_EXCEEDED -> {
                        finish(logEntry, IngestStatus.PENDING, 0, lastCode, attempt);
                        throw new QuotaExceededException(
                                "일일 트래픽 초과: " + apiType + " " + lawdCd + " " + dealYmd);
                    }
                    case INVALID_KEY -> {
                        finish(logEntry, IngestStatus.FAILED, 0, lastCode, attempt);
                        throw new InvalidKeyException("서비스키 오류: " + lastCode);
                    }
                    case ERROR -> backoff(attempt);
                }
            } catch (QuotaExceededException | InvalidKeyException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("수집 실패 ({}회차) {} {} {}: {}",
                        attempt, apiType, lawdCd, dealYmd, e.toString());
                backoff(attempt);
            }
        }

        return finish(logEntry, IngestStatus.FAILED, 0, lastCode, maxRetries);
    }

    private int save(ApiType apiType, ParsedResponse response) {
        if (apiType == ApiType.TRADE) {
            var deals = response.items().stream().map(tradeMapper::map).toList();
            upsertDao.upsertTrades(deals);
            return deals.size();
        }
        var deals = response.items().stream().map(rentMapper::map).toList();
        upsertDao.upsertRents(deals);
        return deals.size();
    }

    private IngestResult finish(IngestLog logEntry, IngestStatus status,
                                int rowCount, String resultCode, int attempts) {
        logEntry.record(status, rowCount, resultCode, attempts);
        logRepository.save(logEntry);
        return new IngestResult(status, rowCount, resultCode);
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt) * 100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*IngestServiceTest'`
Expected: PASS (9개 테스트)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jeonseradar/ingest src/test/java/com/jeonseradar/ingest
git commit -m "feat: 수집 오케스트레이션 및 ingest_log 추가 (NODATA 성공 처리, 쿼터 초과 시 중단)"
```

---

### Task 10: 시군구 목록과 배치 실행

**Files:**
- Create: `src/main/resources/sigungu.csv`
- Create: `src/main/java/com/jeonseradar/region/SigunguCatalog.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/IngestTargetPlanner.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/IngestRunner.java`
- Create: `src/main/java/com/jeonseradar/ingest/service/IngestScheduler.java`
- Test: `src/test/java/com/jeonseradar/region/SigunguCatalogTest.java`
- Test: `src/test/java/com/jeonseradar/ingest/service/IngestTargetPlannerTest.java`
- Test: `src/test/java/com/jeonseradar/ingest/service/IngestRunnerTest.java`

**Interfaces:**
- Consumes: Task 9 `IngestService`
- Produces:
  - `SigunguCatalog.all() -> List<String>` (시군구 5자리 코드)
  - `IngestTargetPlanner.backfillTargets() -> List<IngestTarget>`
  - `IngestTargetPlanner.incrementalTargets(LocalDate today) -> List<IngestTarget>`
  - `record IngestTarget(ApiType apiType, String regionCode, String dealYm)`
  - `IngestRunner.run(List<IngestTarget>) -> RunSummary`
  - `record RunSummary(int success, int failed, int skipped, boolean abortedByQuota)`

- [ ] **Step 1: 시군구 코드 파일 생성**

법정동코드 전체자료에서 시군구(5자리)를 추출한다. 행정표준코드관리시스템(`https://www.code.go.kr`)에서 "법정동코드 전체자료"를 내려받아 변환한다.

```bash
# 내려받은 파일이 legal_dong.txt (탭 구분, EUC-KR)라고 가정
iconv -f EUC-KR -t UTF-8 legal_dong.txt \
  | awk -F'\t' 'NR>1 && $3=="존재" {print substr($1,1,5)"\t"$2}' \
  | sort -u -k1,1 \
  | awk -F'\t' '$1 !~ /00000$/ {print $1","$2}' \
  > src/main/resources/sigungu.csv

wc -l src/main/resources/sigungu.csv
```

**예상 행 수는 약 250개다.** 100개 미만이거나 400개를 넘으면 필터 조건이 잘못된 것이므로 원본 컬럼 구성을 다시 확인한다. 세종시(36110)처럼 시군구 계층이 특이한 곳이 빠지지 않았는지 확인한다.

파일 형식:

```csv
11110,서울특별시 종로구
11140,서울특별시 중구
```

- [ ] **Step 2: SigunguCatalog 테스트 작성**

`src/test/java/com/jeonseradar/region/SigunguCatalogTest.java`:

```java
package com.jeonseradar.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SigunguCatalogTest {

    private final SigunguCatalog catalog = new SigunguCatalog();

    @Test
    void 전국_시군구를_로드한다() {
        assertThat(catalog.all()).hasSizeBetween(200, 300);
    }

    @Test
    void 모든_코드는_5자리_숫자다() {
        assertThat(catalog.all()).allMatch(code -> code.matches("\\d{5}"));
    }

    @Test
    void 중복이_없다() {
        assertThat(catalog.all()).doesNotHaveDuplicates();
    }

    @Test
    void 서울_종로구가_포함된다() {
        assertThat(catalog.all()).contains("11110");
    }
}
```

- [ ] **Step 3: SigunguCatalog 구현**

`src/main/java/com/jeonseradar/region/SigunguCatalog.java`:

```java
package com.jeonseradar.region;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SigunguCatalog {

    private final Map<String, String> codeToName = new LinkedHashMap<>();

    public SigunguCatalog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("sigungu.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                if (parts.length == 2 && parts[0].trim().matches("\\d{5}")) {
                    codeToName.putIfAbsent(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("sigungu.csv를 읽을 수 없습니다", e);
        }
    }

    public List<String> all() {
        return new ArrayList<>(codeToName.keySet());
    }

    public String nameOf(String code) {
        return codeToName.get(code);
    }
}
```

- [ ] **Step 4: IngestTargetPlanner 테스트와 구현**

`src/test/java/com/jeonseradar/ingest/service/IngestTargetPlannerTest.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.region.SigunguCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTargetPlannerTest {

    private final SigunguCatalog catalog = new SigunguCatalog();
    private final IngestTargetPlanner planner = new IngestTargetPlanner(catalog, 36, 24, 3);

    @Test
    void 증분은_직전_3개월만_대상으로_한다() {
        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        List<String> months = targets.stream()
                .map(IngestTargetPlanner.IngestTarget::dealYm)
                .distinct()
                .sorted()
                .toList();

        assertThat(months).containsExactly("202607", "202608", "202609");
    }

    @Test
    void 증분은_두_API를_모두_포함한다() {
        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        assertThat(targets).extracting(IngestTargetPlanner.IngestTarget::apiType)
                .contains(ApiType.TRADE, ApiType.RENT);
    }

    @Test
    void 증분_호출수는_시군구수_곱하기_3개월_곱하기_2API다() {
        int regions = catalog.all().size();

        List<IngestTargetPlanner.IngestTarget> targets =
                planner.incrementalTargets(LocalDate.of(2026, 9, 21));

        assertThat(targets).hasSize(regions * 3 * 2);
    }

    @Test
    void 백필은_전월세36개월_매매24개월이다() {
        int regions = catalog.all().size();

        List<IngestTargetPlanner.IngestTarget> targets = planner.backfillTargets();

        long rentCount = targets.stream()
                .filter(t -> t.apiType() == ApiType.RENT).count();
        long tradeCount = targets.stream()
                .filter(t -> t.apiType() == ApiType.TRADE).count();

        assertThat(rentCount).isEqualTo((long) regions * 36);
        assertThat(tradeCount).isEqualTo((long) regions * 24);
    }
}
```

`src/main/java/com/jeonseradar/ingest/service/IngestTargetPlanner.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.region.SigunguCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class IngestTargetPlanner {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    public record IngestTarget(ApiType apiType, String regionCode, String dealYm) {
    }

    private final SigunguCatalog catalog;
    private final int rentBackfillMonths;
    private final int tradeBackfillMonths;
    private final int incrementalMonths;

    public IngestTargetPlanner(SigunguCatalog catalog,
                               @Value("${molit.backfill.rent-months:36}") int rentBackfillMonths,
                               @Value("${molit.backfill.trade-months:24}") int tradeBackfillMonths,
                               @Value("${molit.incremental-months:3}") int incrementalMonths) {
        this.catalog = catalog;
        this.rentBackfillMonths = rentBackfillMonths;
        this.tradeBackfillMonths = tradeBackfillMonths;
        this.incrementalMonths = incrementalMonths;
    }

    public List<IngestTarget> backfillTargets() {
        List<IngestTarget> targets = new ArrayList<>();
        YearMonth current = YearMonth.now();
        addTargets(targets, ApiType.RENT, current, rentBackfillMonths);
        addTargets(targets, ApiType.TRADE, current, tradeBackfillMonths);
        return targets;
    }

    /**
     * 신고 기한이 계약일로부터 30일이고 해제여부·등기일자가 나중에 바뀌므로,
     * 당월 포함 직전 3개월을 매일 다시 긁는다 (스펙 §1.2).
     */
    public List<IngestTarget> incrementalTargets(LocalDate today) {
        List<IngestTarget> targets = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        addTargets(targets, ApiType.RENT, current, incrementalMonths);
        addTargets(targets, ApiType.TRADE, current, incrementalMonths);
        return targets;
    }

    private void addTargets(List<IngestTarget> targets, ApiType apiType,
                            YearMonth from, int months) {
        for (String regionCode : catalog.all()) {
            for (int i = 0; i < months; i++) {
                targets.add(new IngestTarget(apiType, regionCode, from.minusMonths(i).format(YM)));
            }
        }
    }
}
```

`application.yml`의 `molit:` 아래에 다음을 추가한다.

```yaml
  incremental-months: 3
  backfill:
    rent-months: 36
    trade-months: 24
```

- [ ] **Step 5: IngestRunner 테스트 작성**

`src/test/java/com/jeonseradar/ingest/service/IngestRunnerTest.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.client.ApiType;
import com.jeonseradar.ingest.entity.IngestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IngestRunnerTest {

    private final IngestService service = mock(IngestService.class);
    private final IngestRunner runner = new IngestRunner(service);

    private List<IngestTargetPlanner.IngestTarget> targets(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new IngestTargetPlanner.IngestTarget(
                        ApiType.TRADE, String.format("%05d", 11110 + i), "202609"))
                .toList();
    }

    @Test
    void 쿼터초과를_만나면_남은_대상을_호출하지_않는다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 1, "00"))
                .thenThrow(new QuotaExceededException("초과"));

        var summary = runner.run(targets(10));

        assertThat(summary.abortedByQuota()).isTrue();
        assertThat(summary.success()).isEqualTo(1);
        verify(service, times(2)).ingestOne(any(), anyString(), anyString());
    }

    @Test
    void 개별_실패는_전체를_중단시키지_않는다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenReturn(new IngestService.IngestResult(IngestStatus.FAILED, 0, "99"))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 5, "00"))
                .thenReturn(new IngestService.IngestResult(IngestStatus.SUCCESS, 3, "00"));

        var summary = runner.run(targets(3));

        assertThat(summary.abortedByQuota()).isFalse();
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.success()).isEqualTo(2);
    }

    @Test
    void 인증키오류도_배치를_중단시킨다() {
        when(service.ingestOne(any(), anyString(), anyString()))
                .thenThrow(new InvalidKeyException("키 오류"));

        var summary = runner.run(targets(5));

        assertThat(summary.success()).isZero();
        verify(service, times(1)).ingestOne(any(), anyString(), anyString());
    }
}
```

- [ ] **Step 6: IngestRunner와 Scheduler 구현**

`src/main/java/com/jeonseradar/ingest/service/IngestRunner.java`:

```java
package com.jeonseradar.ingest.service;

import com.jeonseradar.ingest.entity.IngestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    public record RunSummary(int success, int failed, int skipped, boolean abortedByQuota) {
    }

    private final IngestService ingestService;

    public IngestRunner(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    public RunSummary run(List<IngestTargetPlanner.IngestTarget> targets) {
        int success = 0;
        int failed = 0;

        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            try {
                var result = ingestService.ingestOne(
                        target.apiType(), target.regionCode(), target.dealYm());
                if (result.status() == IngestStatus.SUCCESS) {
                    success++;
                } else {
                    failed++;
                }
            } catch (QuotaExceededException e) {
                log.warn("일일 쿼터 소진으로 배치 중단. 처리 {}/{}, 남은 대상은 내일 재개",
                        i, targets.size());
                return new RunSummary(success, failed, targets.size() - i, true);
            } catch (InvalidKeyException e) {
                log.error("서비스키 오류로 배치 중단: {}", e.getMessage());
                return new RunSummary(success, failed, targets.size() - i, false);
            }
        }

        log.info("수집 완료: 성공 {}, 실패 {}", success, failed);
        return new RunSummary(success, failed, 0, false);
    }
}
```

`src/main/java/com/jeonseradar/ingest/service/IngestScheduler.java`:

```java
package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestTargetPlanner planner;
    private final IngestRunner runner;

    public IngestScheduler(IngestTargetPlanner planner, IngestRunner runner) {
        this.planner = planner;
        this.runner = runner;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void dailyIncremental() {
        log.info("일일 증분 수집 시작");
        var summary = runner.run(planner.incrementalTargets(LocalDate.now()));
        log.info("일일 증분 수집 종료: {}", summary);
    }
}
```

- [ ] **Step 7: 백필 실행용 CLI 러너 추가**

`src/main/java/com/jeonseradar/ingest/service/BackfillCommand.java`:

```java
package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** `--backfill` 인자로 실행할 때만 전국 백필을 수행한다. 평소에는 아무 일도 하지 않는다. */
@Component
public class BackfillCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillCommand.class);

    private final IngestTargetPlanner planner;
    private final IngestRunner runner;

    public BackfillCommand(IngestTargetPlanner planner, IngestRunner runner) {
        this.planner = planner;
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("backfill")) {
            return;
        }
        var targets = planner.backfillTargets();
        log.info("백필 시작: 대상 {}건", targets.size());
        var summary = runner.run(targets);
        log.info("백필 종료: {}", summary);
    }
}
```

- [ ] **Step 8: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: PASS (전체)

- [ ] **Step 9: 실제 백필 1개 구로 검증**

```bash
export MOLIT_SERVICE_KEY='발급받은_키'
# sigungu.csv를 임시로 1줄(11110)만 남긴 사본으로 바꿔 소규모 검증
./gradlew bootRun --args='--backfill'
```

MySQL에서 확인한다.

```sql
SELECT COUNT(*) FROM trade_deal;
SELECT COUNT(*) FROM rent_deal;
SELECT status, COUNT(*) FROM ingest_log GROUP BY status;
SELECT * FROM trade_deal ORDER BY deal_date DESC LIMIT 5;
```

`deal_amount`가 원 단위(10자리 내외)인지, `area_bucket`이 채워졌는지, `complex_id`가 NULL이 아닌지 눈으로 확인한다.

- [ ] **Step 10: 같은 백필을 한 번 더 실행해 멱등성 확인**

```bash
./gradlew bootRun --args='--backfill'
```

```sql
SELECT COUNT(*) FROM trade_deal;
```

**행 수가 1회차와 같아야 한다.** 늘었다면 `deal_hash`에 가변 필드가 섞였거나 ON DUPLICATE KEY 절이 동작하지 않는 것이다.

- [ ] **Step 11: sigungu.csv 복원 후 전국 백필**

```bash
git checkout src/main/resources/sigungu.csv
./gradlew bootRun --args='--backfill'
```

쿼터 소진으로 중단되면 다음 날 다시 실행한다. `ingest_log`의 PENDING 항목이 남은 대상이다.

- [ ] **Step 12: 커밋**

```bash
git add src/main/java/com/jeonseradar src/main/resources src/test/java/com/jeonseradar
git commit -m "feat: 전국 시군구 목록 및 백필/증분 배치 추가"
```

---

### Task 11: 검증 게이트 — 계약기간 결측률 실측

**Files:**
- Create: `src/main/java/com/jeonseradar/ingest/service/DataQualityReporter.java`
- Test: `src/test/java/com/jeonseradar/ingest/service/DataQualityReporterTest.java`
- Create: `docs/superpowers/reports/2026-XX-XX-phase1-data-quality.md`

**Interfaces:**
- Consumes: Task 7의 `rent_deal` 테이블
- Produces: `DataQualityReporter.report() -> QualityReport`
  - `record QualityReport(long totalRent, long estimatedTerm, double estimatedRatio, long pureJeonse, long renewalRightUsed, long complexCount)`

**이 태스크가 1단계의 종료 조건이다.** 스펙 §6의 검증 게이트를 전수 데이터로 실행한다.

**사전 실측 기준선 (2026-09-21, 표본 26,173건 / 6개 시군구 × 6개월):**

| 지표 | 사전 실측값 |
|---|---|
| `contractTerm` 채움률 | **95.6%** (계약년월별 93.6~97.1%) |
| → `term_estimated = true` 예상 비율 | **약 4~5%** |
| `useRRRight = 사용` (전체 대비) | 18.2% |
| `contractType = 갱신` | 36.1% |
| `aptSeq` 채움률 | 100% |

전수 결과가 이 기준선에서 크게 벗어나면 파서나 매퍼에 결함이 있다는 신호다.
특히 **`term_estimated` 비율이 50%를 넘으면 `ContractTermParser`가 실제 형식
(`YY.MM~YY.MM`)을 못 읽고 있을 가능성부터 의심한다** — 지표 설계 문제가 아니라 버그다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/jeonseradar/ingest/service/DataQualityReporterTest.java`:

```java
package com.jeonseradar.ingest.service;

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
class DataQualityReporterTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    DataQualityReporter reporter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private void insertRent(String hash, boolean estimated, long monthlyRent, boolean renewalUsed) {
        jdbcTemplate.update("""
                INSERT INTO rent_deal
                  (deal_hash, complex_id, region_code, legal_dong, jibun, apt_name,
                   area, area_bucket, floor, deal_date, deposit, monthly_rent,
                   build_year, contract_type, contract_term_raw, term_start_ym, term_end_ym,
                   term_estimated, renewal_right_used, created_at, updated_at)
                VALUES (?,1,'11110','청운동','1','단지',84.97,85,3,'2026-03-14',
                        500000000,?,2005,'신규','',NULL,NULL,?,?, NOW(), NOW())
                """, hash, monthlyRent, estimated, renewalUsed);
    }

    @Test
    void 추정_비율을_계산한다() {
        insertRent("q1", true, 0, false);
        insertRent("q2", false, 0, false);
        insertRent("q3", false, 0, false);
        insertRent("q4", false, 0, false);

        var report = reporter.report();

        assertThat(report.totalRent()).isEqualTo(4);
        assertThat(report.estimatedTerm()).isEqualTo(1);
        assertThat(report.estimatedRatio()).isEqualTo(0.25);
    }

    @Test
    void 순수전세만_센다() {
        insertRent("q5", false, 0, false);
        insertRent("q6", false, 800000, false);

        var report = reporter.report();

        assertThat(report.pureJeonse()).isEqualTo(1);
    }

    @Test
    void 갱신권_사용건을_센다() {
        insertRent("q7", false, 0, true);
        insertRent("q8", false, 0, false);

        var report = reporter.report();

        assertThat(report.renewalRightUsed()).isEqualTo(1);
    }

    @Test
    void 데이터가_없으면_비율은_0이다() {
        var report = reporter.report();

        assertThat(report.estimatedRatio()).isZero();
    }
}
```

각 테스트는 독립 실행되어야 하므로 `@Transactional`을 붙이거나 `@BeforeEach`에서 `DELETE FROM rent_deal`을 수행한다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests '*DataQualityReporterTest'`
Expected: FAIL — `DataQualityReporter` 없음

- [ ] **Step 3: 구현**

`src/main/java/com/jeonseradar/ingest/service/DataQualityReporter.java`:

```java
package com.jeonseradar.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 1단계 종료 조건. 만기 예측 전체가 계약기간 필드에 의존하므로
 * 실제 결측·파싱실패 비율을 실측해야 2단계 설계를 확정할 수 있다 (스펙 §6 검증 게이트).
 */
@Service
public class DataQualityReporter {

    private static final Logger log = LoggerFactory.getLogger(DataQualityReporter.class);

    public record QualityReport(
            long totalRent,
            long estimatedTerm,
            double estimatedRatio,
            long pureJeonse,
            long renewalRightUsed,
            long complexCount
    ) {
    }

    private final JdbcTemplate jdbcTemplate;

    public DataQualityReporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public QualityReport report() {
        long total = count("SELECT COUNT(*) FROM rent_deal");
        long estimated = count("SELECT COUNT(*) FROM rent_deal WHERE term_estimated = TRUE");
        long pureJeonse = count("SELECT COUNT(*) FROM rent_deal WHERE monthly_rent = 0");
        long renewalUsed = count("SELECT COUNT(*) FROM rent_deal WHERE renewal_right_used = TRUE");
        long complexes = count("SELECT COUNT(*) FROM complex");

        double ratio = total == 0 ? 0.0 : (double) estimated / total;

        QualityReport report = new QualityReport(
                total, estimated, ratio, pureJeonse, renewalUsed, complexes);
        log.info("데이터 품질 리포트: {}", report);
        return report;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*DataQualityReporterTest'`
Expected: PASS (4개 테스트)

- [ ] **Step 5: 전국 데이터로 실측**

Task 10 Step 11의 전국 백필이 끝난 뒤 실행한다.

```sql
SELECT
  COUNT(*)                                                   AS 전월세_전체,
  SUM(term_estimated)                                        AS 계약기간_추정건,
  ROUND(SUM(term_estimated) / COUNT(*) * 100, 1)             AS 추정비율_퍼센트,
  SUM(monthly_rent = 0)                                      AS 순수전세,
  SUM(renewal_right_used)                                    AS 갱신권_사용,
  ROUND(SUM(renewal_right_used) / SUM(monthly_rent = 0) * 100, 1) AS 전세중_갱신권사용_퍼센트
FROM rent_deal;

SELECT COUNT(*) AS 단지수 FROM complex;

SELECT status, COUNT(*) FROM ingest_log GROUP BY status;
```

- [ ] **Step 6: 결과를 리포트 문서로 남기고 게이트를 판정**

`docs/superpowers/reports/<실행일>-phase1-data-quality.md`에 위 쿼리 결과를 표로 기록하고, 다음 판정을 명시한다.

- **추정비율 ≤ 10%** → 사전 실측과 일치. 2단계 진행, 만기 예측은 스펙 §3.2 그대로 간다.
- **추정비율 10~50%** → 기준선과 어긋남. **파서 결함을 먼저 의심하고 조사한다.**
  `contract_term_raw`가 비어있지 않은데 `term_estimated = true`인 행을 표본으로 뽑아
  어떤 형식이 파싱에 실패했는지 확인한다:
  ```sql
  SELECT contract_term_raw, COUNT(*) FROM rent_deal
  WHERE term_estimated = TRUE AND TRIM(contract_term_raw) <> ''
  GROUP BY contract_term_raw ORDER BY COUNT(*) DESC LIMIT 20;
  ```
  이 쿼리가 결과를 뱉으면 파서 버그이지 데이터 문제가 아니다.
- **추정비율 > 50%이고 위 쿼리가 비어 있음** → 진짜로 원본에 계약기간이 없다는 뜻.
  **2단계 계획 작성 전에 지표 재설계.** 계약기간이 확정된 건만 대상으로 하는 축소 지표나
  대체 접근을 검토한다. (사전 실측과 배치되는 결과이므로 개연성은 낮다.)

판정 결과와 근거 수치를 문서 첫 줄에 한 문장으로 적는다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/jeonseradar/ingest/service/DataQualityReporter.java src/test/java/com/jeonseradar/ingest/service/DataQualityReporterTest.java docs/superpowers/reports
git commit -m "feat: 데이터 품질 리포터 추가 및 1단계 검증 게이트 실측 결과 기록"
```

---

## 1단계 완료 조건

- [ ] `./gradlew test` 전체 통과
- [ ] 전국 백필 완료 (`ingest_log`에 FAILED가 남아 있지 않거나, 남은 건의 원인이 규명됨)
- [ ] 같은 백필을 두 번 실행해도 `trade_deal`·`rent_deal` 행 수가 늘지 않음
- [ ] `docs/superpowers/reports/<실행일>-phase1-data-quality.md`에 계약기간 추정비율이 기록되고 게이트 판정이 내려짐

2단계(지표 집계) 계획은 이 리포트의 수치를 근거로 작성한다.
