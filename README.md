# 전세 만기·역전세 레이더

국토교통부 실거래가 공공데이터로 **아파트 단지별 전세 만기 도래 물량과 역전세 규모를 예측**하는 서비스.

만기가 언제 돌아오는지, 그때 집주인이 얼마를 메워야 하는지를 단지 단위로 계산한다.
부수적으로 매매 신고가의 등기·해제 여부를 사실 그대로 공개한다.

- 설계 문서: [docs/superpowers/specs/2026-09-21-jeonse-maturity-radar-design.md](docs/superpowers/specs/2026-09-21-jeonse-maturity-radar-design.md)
- 1단계 구현 계획: [docs/superpowers/plans/2026-09-21-phase1-ingest-pipeline.md](docs/superpowers/plans/2026-09-21-phase1-ingest-pipeline.md)

---

## 지금 어디까지 되어 있나

전체 4단계 중 **1~3단계가 완료**됐다. 전국 실거래가가 적재돼 있고, 집계가 돌고,
단지·지역 공개 페이지가 실제 데이터로 렌더링된다.

| 단계 | 내용 | 상태 |
|------|------|------|
| 1 | 수집 파이프라인 — 실거래가 원본 적재 | **완료** (전국 16,140/16,140 대상, 실패 0) |
| 2 | 지표 집계 — 만기 예측, 역전세 갭 | **완료** (7개 태스크) |
| 3 | 공개 페이지 — 단지·지역 (Thymeleaf SSR) | **완료** (7개 태스크) |
| 4 | 이메일 구독과 주간 다이제스트 | 미착수 |

### 1단계 안에서 동작하는 것 / 아직 없는 것

**동작한다** — 단위·통합 테스트로 검증됨 (100개 테스트)

- 국토교통부 API 호출과 XML 응답 파싱, 페이지 순회
- `resultCode` 판정 (정상 / NODATA / 쿼터 초과 / 인증키 오류 / 일시 장애)
- 금액·면적·지번·날짜 변환, 계약기간 파싱
- `aptSeq` 기준 단지 정규화
- `deal_hash` 기반 멱등 upsert
- 단건 수집 오케스트레이션과 `ingest_log` 기록

**적재 규모 (2026-09-23 기준)**

| | |
|---|---|
| 전월세 실거래 | 3,089,809 |
| 매매 실거래 | 991,166 |
| 단지 | 44,320 |
| 시군구 | 255 (아파트 실거래가 있는 지역 전부) |

---

## 준비물

| 항목 | 버전 | 비고 |
|------|------|------|
| JDK | 21 | |
| Docker | 실행 중이어야 함 | 테스트가 Testcontainers로 MySQL을 띄운다 |
| MySQL | 8.0 | 애플리케이션 실행 시에만 필요. 테스트는 Testcontainers가 대신함. `docker-compose.yml` 제공 |
| 공공데이터포털 서비스키 | — | 아래 참조 |

Gradle은 별도 설치가 필요 없다 — 저장소에 wrapper(`./gradlew`)가 포함되어 있다.

### 서비스키 발급

공공데이터포털에서 아래 두 API를 각각 신청한다. 둘 다 **자동승인**이며 보통 1~2시간 내 활성화된다.

- [국토교통부_아파트 매매 실거래가 자료](https://www.data.go.kr/data/15126469/openapi.do)
- [국토교통부_아파트 전월세 실거래가 자료](https://www.data.go.kr/data/15126474/openapi.do)

두 API는 **같은 서비스키를 공유**한다. 이용허락범위에 제한이 없어 상업적 이용이 가능하다.

발급받은 키(디코딩된 형태)를 저장소 루트의 `.env.local`에 둔다. 이 파일은 `.gitignore`에 등록되어 있다.

```
MOLIT_SERVICE_KEY=발급받은_키
```

> **키를 커밋하지 않는다.** `.env.local` 외의 파일에 키를 적지 말 것.

---

## 설정

`src/main/resources/application.yml`. 모든 값은 환경변수로 덮어쓸 수 있다.

### 데이터베이스

| 환경변수 | 기본값 |
|----------|--------|
| `DB_HOST` | `localhost` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `jeonse` |
| `DB_USERNAME` | `jeonse` |
| `DB_PASSWORD` | `jeonse` |

스키마는 `ddl-auto: update`로 자동 생성된다.

### API 호출

| 키 | 기본값 | 의미 |
|----|--------|------|
| `molit.base-url` | `https://apis.data.go.kr/1613000` | |
| `molit.service-key` | `${MOLIT_SERVICE_KEY:}` | 비어 있으면 호출이 인증 오류로 실패한다 |
| `molit.rows-per-page` | `1000` | 페이지당 건수 |
| `molit.delay-ms` | `120` | 페이지 간 대기 |
| `molit.max-retries` | `3` | 일시 장애 재시도 횟수 |

**일일 트래픽 한도는 개발계정 기준 10,000건**이다. 운영계정으로 전환하면 확대 가능하다.

---

## 실행

### 테스트

```bash
./gradlew test
```

Docker가 떠 있어야 한다. 첫 실행은 `mysql:8.0` 이미지를 받느라 몇 분 걸린다.

**테스트는 네트워크를 타지 않는다.** 실제 API 응답을 캡처한 픽스처(`src/test/resources/fixtures/molit/`)를 쓰고, 테스트 프로필의 `molit.base-url`은 일부러 도달 불가능한 주소로 두어 실수로 외부 호출을 하는 테스트가 있으면 즉시 실패하게 했다.

특정 테스트만 돌리려면:

```bash
./gradlew test --tests '*ContractTermParserTest'
```

### 로컬 데이터베이스

저장소 루트의 `docker-compose.yml`로 로컬 MySQL을 띄운다. 접속 정보가 `application.yml`의
기본값과 일치하므로 환경변수를 따로 줄 필요가 없다.

```bash
docker compose up -d --wait   # 헬스체크 통과까지 대기
docker compose logs -f db     # 로그
docker compose down           # 중지 (데이터는 볼륨에 남는다)
docker compose down -v        # 중지 + 데이터 삭제
```

한글 단지명을 위해 `utf8mb4`, `NOW()`가 KST를 쓰도록 `--default-time-zone=+09:00`,
백필의 대량 적재를 위해 버퍼 풀 512M와 `max_allowed_packet` 64M로 설정되어 있다.

> CLI로 직접 들여다볼 때는 클라이언트 charset을 지정해야 한다.
> `docker exec jeonse-mysql mysql --default-character-set=utf8mb4 -ujeonse -pjeonse jeonse`
> 빼먹으면 한글이 깨져 보이고 `CHAR_LENGTH()`가 바이트 수를 돌려준다. 저장은 정상이며
> 조회 화면만 그런 것이니 이걸 보고 데이터를 고치면 안 된다.

### 애플리케이션

```bash
./gradlew bootRun
```

MySQL이 떠 있어야 하고(위 `docker compose up -d`) `MOLIT_SERVICE_KEY`가 설정되어 있어야 한다.

> **주의 — 애플리케이션을 그냥 띄우면 스스로 데이터를 수집한다.**
> `bootRun`은 `IngestScheduler`를 함께 띄우고, 이 스케줄러는 **매일 02:00 KST에 최근
> 3개월치 증분 수집**을 실행한다(약 1,614회 호출, 일일 쿼터 10,000). 집계 스케줄러도
> 04:00 KST에 돈다.
>
> 의도한 게 아니라면 `.env.local`에 서비스키를 두지 않거나 스케줄을 끄고 띄워라.
>
> 전국 백필은 `--backfill`로 실행한다. 이미 성공한 대상은 건너뛰므로 재실행이 안전하고,
> 끝나면 JVM이 스스로 종료한다.

### Docker 문제가 생기면

이 머신에서는 Docker Engine 29.x와 Testcontainers 1.20.4의 API 협상 비호환이 있었다.
`src/test/resources/docker-java.properties.example`을 같은 디렉터리에 `docker-java.properties`로
복사하면 우회된다. 이 파일은 머신별 설정이라 `.gitignore`에 등록되어 있고 커밋하지 않는다.

---

## 데이터 모델

| 테이블 | 내용 |
|--------|------|
| `complex` | 단지 마스터. `(region_code, apt_seq)`가 유니크 키 |
| `trade_deal` | 매매 실거래 원본 |
| `rent_deal` | 전월세 실거래 원본 |
| `ingest_log` | 수집 상태. `(api_type, region_code, deal_ym)`별 성공/실패 |

집계 테이블은 2단계 범위이며 아직 없다.

### 알아둘 설계 결정 세 가지

**단지 식별은 지번이 아니라 `aptSeq`다.** 실측(전월세 17,532건) 결과 한 지번에 서로 다른
단지가 2개 이상 들어있는 경우가 43건 있었다(예: 강남구 개포동 12번지 → `11680-279`, `11680-280`).
지번으로 묶으면 서로 다른 단지가 합쳐진다. `aptSeq`는 채움률 100%이고 한 값이 여러 지번에
걸치거나 이름이 흔들리는 사례가 0건이다.

**`deal_hash`는 불변 필드로만 만든다.** API가 거래 고유 ID를 주지 않아 SHA-256 자연키 해시로
거래를 식별한다. `등기일자`와 `해제여부`는 신고 이후에 값이 바뀌므로 해시에 넣으면 같은 거래가
값이 바뀔 때마다 새 행으로 쌓인다. 해시 구성은 골든값 테스트로 고정되어 있으며,
**백필을 한 번 돌린 뒤에 바꾸면 적재된 데이터 전부가 고아가 된다.**

**금액은 원 단위 `BIGINT`로 저장한다.** API는 만원 단위 문자열(`230,000`)로 준다.
변환을 빠뜨리면 10,000배 오류인데 예외도 경고도 없이 저장되고 화면에 렌더링된다.

---

## API 동작에서 알아둘 점

실제 호출로 확인한 사항들이다. 코드가 이 전제 위에 서 있다.

**인증 오류와 쿼터 초과는 HTTP 403으로 온다.** 200이 아니다. 진짜 원인은 본문의
`<cmmMsgHeader><returnReasonCode>`에 있다. 그래서 클라이언트는 HTTP 상태로 아무것도 판단하지
않고 본문의 `resultCode`만 본다 — `MolitApiClient`의 `onStatus` 핸들러가 그 역할이며 지우면 안 된다.

**NODATA(`03`)는 실패가 아니다.** 거래가 없는 군 지역은 대부분의 달에 빈 응답이 정상이다.
실패로 기록하면 재시도 큐가 영원히 비워지지 않는다.

**쿼터 초과(`22`)는 재시도하지 않는다.** 이미 소진된 쿼터를 다시 때리면 다음 날 할당량까지
태운다. 예외로 전파해 당일 배치를 멈추고 남은 대상을 `PENDING`으로 남긴다.

**값이 없는 필드는 빈 문자열이 아니라 공백 한 칸(`" "`)으로 온다.** `계약기간`, `갱신요구권사용`,
`해제여부`, `종전계약보증금` 등. 파서가 trim하므로 이후 단계에서는 `""`로 보인다.

**`계약기간` 형식은 `YY.MM~YY.MM`이다** (예: `25.05~27.05`). 문서에서 흔히 보이는
`YYYYMM~YYYYMM`은 실측 18,287건 중 0건이었다. 시작월 없이 종료월만 오는 `~26.03` 형태도 0.4% 있다.

---

## 프로젝트 구조

```
src/main/java/com/jeonseradar/
├── ingest/
│   ├── client/      MolitApiClient, ApiType, MolitProperties
│   ├── parser/      MolitResponseParser, ResultCode, FieldConverter, MolitFields
│   ├── mapper/      TradeDealMapper, RentDealMapper
│   ├── entity/      IngestLog, IngestStatus
│   ├── repository/  IngestLogRepository
│   └── service/     IngestService, QuotaExceededException, InvalidKeyException
└── deal/
    ├── entity/      Complex, TradeDeal, RentDeal
    ├── repository/  DealUpsertDao, ComplexRepository, TradeDealRepository, RentDealRepository
    └── service/     DealHasher, ContractTermParser, ComplexResolver, SlugGenerator
```

---

## 개발 규칙

- **TDD** — 실패하는 테스트를 먼저 쓰고, 실패를 확인한 뒤 구현한다.
- **네트워크를 타는 테스트를 만들지 않는다.** 픽스처만 쓴다.
- **픽스처는 실제 API 응답이다.** 테스트가 실패하면 구현이 틀린 것이지 픽스처를 고칠 일이 아니다.
- **공공데이터만 쓴다.** 상업적 이용이 가능한 것은 공공데이터뿐이다. 크롤링 데이터는 넣지 않는다.
- 커밋 메시지는 한국어 한 줄 요약 + Conventional Commits 접두사.

---

## 운영 배포 (AWS Lightsail + apt.seeplus.net)

인스턴스 한 대(서울, 4GB)에서 MySQL·앱·Caddy를 Docker Compose로 돌린다. HTTPS는 Caddy가 자동 발급한다.
AWS 계정 `378040395734`(프로파일 `starter`)에 고정되어 있어, 다른 계정 자격증명으로는 plan/apply가 거부된다.

| 경로 | 역할 |
|------|------|
| `infra/terraform/` | 인스턴스, 고정 IP, 방화벽(22/80/443), 일일 자동 스냅샷, Route 53 A 레코드(apt.seeplus.net) |
| `deploy/docker-compose.prod.yml`, `Caddyfile`, `Dockerfile` | 서버에서 실행되는 스택 |
| `deploy/deploy.sh` | jar 빌드 → 업로드 → `docker compose up` (코드 바꿀 때마다) |
| `deploy/migrate-db.sh` | 로컬 DB를 서버로 덤프 이전 (최초 1회) |

```bash
# 1) 인프라
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # ssh_allowed_cidrs에 내 IP
terraform init && terraform apply

# 2) 비밀값
cp deploy/.env.prod.example deploy/.env.prod   # DB 비밀번호, 서비스키

# 3) 배포 + 데이터 이전 (로컬 docker compose의 MySQL이 떠 있어야 함)
deploy/deploy.sh
deploy/migrate-db.sh
```

SSH 키는 `~/.ssh/jeonse-radar`. 서버 접속은 `terraform -chdir=infra/terraform output -raw ssh`.

서비스 주소는 **https://apt.seeplus.net**. Route 53 존은 `seeplus.net`(`zone_name`)이고, 서비스 호스트는
그 안의 A 레코드(`domain`, 기본 `apt.seeplus.net`)다. 호스트를 바꾸려면 `variables.tf`의 `domain`과
`deploy/.env.prod`의 `DOMAIN`을 같이 바꾸고 `terraform apply` → `deploy/deploy.sh` 순으로 돌린다.

### 데이터 갱신

서버 앱이 떠 있으면 스케줄러가 알아서 돈다.

| 시각 (KST) | 작업 |
|------|------|
| 02:00 | `IngestScheduler` — 당월 포함 최근 3개월 전월세·매매 재수집 (SUCCESS여도 다시 긁는다) |
| 04:00 | `SnapshotScheduler` — 시세·만기·플래그 집계 |

새벽을 기다리지 않고 바로 갱신하려면 서버에서 일회성 컨테이너로 돌린다. 서비스 중인 `app` 컨테이너는 그대로 둔다.

```bash
ssh -i ~/.ssh/jeonse-radar ubuntu@$(terraform -chdir=infra/terraform output -raw static_ip)
cd /opt/jeonse-radar
docker compose run --rm -T app --backfill        # ingest_log에서 SUCCESS가 아닌 대상만 수집
docker compose run --rm -T app --build-metrics   # 집계
```

`--backfill`은 이미 SUCCESS인 (지역, 월)을 건너뛴다. 덤프 이전 뒤처럼 부분 수집된 월을 다시 받아야 하면
먼저 그 월을 PENDING으로 돌린다 (예: `update ingest_log set status='PENDING' where deal_ym='202609';`).
API 쿼터(일 10,000건)는 같은 서비스키를 쓰는 로컬과 나눠 쓴다.

