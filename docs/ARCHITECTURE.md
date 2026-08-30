# 아키텍처

## 저장소 경계

이 저장소는 Spring Boot 공개 API만 소유합니다. FastAPI MCP 구현은 별도 [Timing-Jeju/jeju_AI](https://github.com/Timing-Jeju/jeju_AI) 저장소가 소유하며, 두 서비스는 private network와 버전이 명시된 MCP 계약으로 연동합니다.

```text
.
├── services
│   └── spring-api
│       ├── src
│       ├── build.gradle
│       ├── gradle
│       └── Dockerfile
├── docs
├── supabase
│   ├── config.toml
│   └── migrations
├── db
├── fixtures
├── scripts
├── compose.yml
└── AGENTS.md
```

Spring은 외부 공개 API, 인증·인가, DB와 외부 API를 소유합니다. FastAPI 저장소는 Python 런타임, 패키지 구조, AI 계산 코드와 자체 CI를 독립적으로 결정합니다.

## Spring API 내부 구조

Spring API는 서비스 내부에서 하나의 배포 단위를 유지하는 모놀리식 애플리케이션입니다. 최상위 기술 계층별 분리 대신 `domain/{도메인}` 아래에 관련 코드를 함께 둡니다.

```text
com.timingjeju.api
├── TimingJejuApiApplication.java
├── application
│   └── security
│       ├── CurrentUser.java
│       └── CurrentUserAccessor.java
├── domain
│   └── {domain}
│       ├── controller
│       ├── service
│       ├── repository
│       ├── entity
│       ├── dto/request
│       ├── dto/response
│       ├── mapper
│       └── exception
└── global
    ├── config
    ├── error
    ├── response
    ├── security
    ├── logging
    └── util
```

실제 요구사항이 생기기 전에는 예시용 Member/Auth 도메인이나 가짜 Entity를 만들지 않습니다.

`application.security`는 인증된 현재 사용자처럼 여러 도메인 use case가 사용할 수 있는 프레임워크 비의존 계약만 둡니다. `CurrentUser`와 `CurrentUserAccessor`는 UUID와 순수 Java 타입만 노출하며, `global.security`의 adapter가 Spring SecurityContext와 검증 완료 JWT를 이 계약으로 변환합니다. 도메인은 `global.security`, Spring `Jwt`, `SecurityContext`에 의존하지 않습니다.

## API 문서화 경계

Spring 공개 API는 springdoc-openapi로 OpenAPI 3 계약과 Swagger UI를 제공합니다. 경로와 DTO Validation은 Spring MVC 코드에서 자동 추론하고, Controller 구현에는 문서 애노테이션을 반복하지 않습니다.

추가 설명이 필요한 API는 `domain/{domain}/controller/docs/{Domain}ApiDocs` 문서 계약 인터페이스에 `@Operation`과 특수 응답을 작성합니다. API 전체 정보와 향후 공통 인증·오류 응답은 `global.config.OpenApiConfig`와 `OpenApiCustomizer`가 담당합니다. 세부 기준은 [API 문서화 규칙](API_DOCUMENTATION.md)을 따릅니다.

## 공통 오류와 trace 경계

`global.error`는 공개 API의 단일 `application/problem+json` DTO, 확장 가능한 code registry, MVC exception 변환과 servlet response writer를 소유합니다. Security filter chain, CORS processor와 도메인별 Controller advice는 같은 writer를 사용하고 응답이 이미 commit되면 본문을 다시 쓰지 않습니다. 아직 commit되지 않은 partial body는 초기화한 뒤 공통 오류만 기록합니다. 도메인은 자체 오류 분류를 `ProblemDefinitionContributor`로 제공할 수 있지만 `global.error`가 도메인 구현에 역으로 의존하지 않습니다.

`global.logging`의 가장 이른 servlet filter가 사용자 입력을 신뢰하지 않고 요청마다 32자리 소문자 hex `traceId`를 생성합니다. 같은 값만 request attribute, `X-Trace-Id` 응답 헤더와 오류 body로 전파합니다. MDC에는 동기 dispatch, MVC `Callable` worker, async/error redispatch 동안 같은 값을 설정하고 각 처리 종료 시 이전 값을 복원합니다. 오류 `instance`는 같은 `traceId`를 포함한 occurrence URI `urn:timing-jeju:problem:<traceId>`로 고정하여 raw path segment와 query string을 반사하지 않습니다. 공통 advice가 처리한 raw exception의 Spring DEBUG 출력은 비활성화하고 token, provider payload와 PII를 응답이나 애플리케이션 로그에 공개하지 않습니다.

## 의존성 원칙

- 호출 흐름은 `controller → service → repository`입니다.
- Controller는 Repository를 직접 호출하거나 비즈니스 로직을 소유하지 않습니다.
- Entity를 API 응답으로 직접 반환하지 않고 Request/Response DTO를 분리합니다.
- 트랜잭션 경계는 Service에 두고 읽기 전용과 쓰기를 구분합니다.
- 특정 도메인 전용 코드는 `global`로 옮기지 않습니다.
- 다른 도메인의 Repository를 직접 호출하지 않고 공개 Service, Facade 또는 명시적 application 경계를 둡니다.

### 소셜 로그인 외부 API 경계

`domain/auth`는 Spring Security와 분리된 소셜 로그인 지원 카탈로그 및 Naver UserInfo 변환만 소유합니다. 카탈로그는 Supabase Dashboard의 실제 활성화 상태가 아닙니다. Google·Kakao의 OAuth authorization/code exchange는 프론트엔드 Supabase SDK와 Supabase Auth가 담당하고 Spring이 provider secret이나 token을 받지 않습니다. Naver adapter는 고정된 UserInfo URL, 엄격한 256자 Bearer 검증, 성공 envelope 검증, 인스턴스별 rate limit과 bulkhead를 적용하며 raw token·원본 profile을 저장·로그·응답에 남기지 않습니다. 외부 HTTP gateway, admission service, 표준 profile 변환, Bearer 형식 검증, 오류 응답 책임을 나누고 domain은 `global.security`에 의존하지 않습니다.

## 데이터베이스 마이그레이션 경계

- `supabase/migrations`를 public 애플리케이션 스키마의 단일 버전 관리 기준으로 사용합니다.
- 법정 문서는 `(document_type, locale, version)`으로 버전을 보존하고 Spring이 한 평가 시각의 최신 시행 문서만 조회합니다. 사용자 동의는 canonical JWT sub에만 귀속하며 다건 갱신과 필수 최신 문서 검증을 한 트랜잭션으로 처리합니다.
- 운영 또는 공유 환경에 적용된 migration은 수정하지 않고, 모든 후속 변경은 더 큰 timestamp의 새 migration으로만 추가합니다.
- 마이그레이션은 최초 public 스키마부터 timestamp순으로 누적 적용합니다. `20260819000000` TAGO 정류장 적재, `20260820000000` `#36` 노선-정류장 적재, `20260820000001` `#76` KMA 예보, `20260822000000` `#37` 관광지-정류장 후보 link, `20260823000000` `#65` 추천 체류시간 정책, `20260824000000` `#75` TourAPI discovery checkpoint, `20260825000000` `#33` 공개 장소 tombstone, `20260826000000` `#39` TAGO 도착정보, `20260827000000` `#39` 도착 요청 flight state, `20260830000000` `#170` schedule revision run foundation, `20260902000000` `#44` 여행 생성, `20260903000000` `#182` 관심 장소, `20260904000000`·`20260904000001` `#113` 푸시 기기·알림 설정 순으로 누적 적용합니다. 병합 전 선택적 선행 migration이 없어도 timestamp 중복을 거부하고 현재 존재하는 canonical migration 전체를 적용합니다.
- 로컬 Supabase와 운영 Supabase는 같은 마이그레이션을 사용하지만 Auth·DB 인스턴스와 사용자 데이터는 공유하지 않습니다.
- Supabase 소유 `auth` 스키마·`auth.users`·`auth.uid()`는 애플리케이션 마이그레이션이 생성·교체·삭제하지 않습니다.
- 일반 PostgreSQL Docker 검증용 호환 객체와 fixture는 `db/local-postgres`에 격리하며 운영에 적용하지 않습니다.
- 현재 기능 개발 로드맵 전체에서 Spring classpath와 `db/migration`에 Flyway를 도입하지 않습니다. `supabase/migrations`만 운영 DB 마이그레이션의 단일 기준으로 유지합니다. Flyway 검토는 모든 주요 기능 개발이 끝난 뒤 마지막 안정화 Issue에서만 수행하며, 그 전에는 의존성·application 설정·디렉터리·테스트를 추가하지 않습니다.

## 푸시 기기와 출발 알림 설정 경계

`domain/notification`은 현재 사용자의 푸시 기기 등록·해제와 출발 알림 설정 API만 소유하고, `application.notification`은 token 보호 전후 port, 설정 및 eligibility 계약을 소유합니다. `global.notification`은 PostgreSQL adapter와 AES-256-GCM crypto/config만 구현합니다. Firebase provider 전송은 별도 `application.push`/`global.push.firebase` 경계이며 이 도메인에 Firebase SDK 타입을 노출하지 않습니다.

registration token 원문은 controller 요청에서 application crypto port로 즉시 전달하고 repository에는 ciphertext와 SHA-256 fingerprint만 전달합니다. 원문·ciphertext·fingerprint 및 crypto 실패 원인은 API 응답, Problem Details, 로그, trace, metric과 fixture에 포함하지 않습니다. 암호화 키는 `PUSH_TOKEN_ENCRYPTION_KEY` 환경/secret 주입만 허용합니다.

두 푸시 테이블의 client 역할은 owner safe-column `SELECT`만 허용합니다. `authenticated`의 `INSERT`·`UPDATE`·`DELETE` grant/RLS policy는 두지 않고, 모든 변경은 Spring 서버의 `service_role` JDBC adapter만 수행합니다.

푸시 eligibility의 위치 문서는 profile locale 우선, `ko-KR` fallback과 semantic version/document ID 안정 정렬을 #19 법정 문서 정책과 공유합니다. profile, 후보 문서, 최종 동의·기기의 세 조회는 `REPEATABLE READ` transaction의 첫 DB read 시점 snapshot 하나를 공유하며 동시 commit은 다음 eligibility 호출부터 반영합니다. #61/#106 회원 탈퇴 intake는 `PushNotificationWithdrawalBoundary`만 호출하며, notification 경계는 탈퇴 command/status를 소유하지 않고 모든 기기의 즉시 eligibility 차단과 Auth 삭제 cascade만 책임집니다.

발송 eligibility는 활성 device, `GRANTED` OS 권한, 서버의 명시적 opt-in과 현재 유효한 최신 required 위치 동의를 모두 다시 검사합니다. 결과는 사용한 위치 동의 문서 ID/version을 함께 제공해 예약 계층이 audit snapshot으로 보존할 수 있게 하며, token 존재만으로 동의를 추론하지 않습니다.

## 변경 API 멱등성 경계

후속 생성·계산·적용 API는 [멱등성 application 계약](IDEMPOTENCY.md)의 `IdempotencyUseCase`를 통해서만 업무 변경을 실행합니다. application port는 Spring/JDBC에 의존하지 않고 `global.idempotency` adapter가 PostgreSQL registry와 트랜잭션을 연결합니다. 이 공통 기반 자체는 공개 endpoint를 추가하지 않습니다.

## 추천 체류시간 정책 경계

추천 체류시간은 TourAPI 원문이 아니라 앱 큐레이션입니다. `tour_places.recommended_stay_minutes`는 legacy read 호환으로만 남기며 신규 writer는 `place_stay_policy_versions`와 `place_stay_policies`만 변경합니다. 정책 import는 `tour_places`, snapshot/import run 계보와 외부 operation provenance를 수정하지 않습니다.

운영 writer는 versioned CSV import command 하나입니다. application 계층은 파일·Spring·JDBC를 모르며 전체 payload의 version, effective time, minutes, XOR scope와 정규화 중복을 먼저 검증합니다. dry-run은 live category/place를 조회하되 쓰지 않습니다. 실제 publish는 advisory transaction lock과 expected-active-version CAS, 정렬된 target row의 `FOR UPDATE` 잠금·live 재검증을 한 transaction에서 수행하고, 성공한 경우에만 새 draft와 모든 policy를 저장한 뒤 이전 active를 retire하고 새 version을 active로 바꿉니다. 같은 version/hash replay만 no-op이고 검증과 publish 사이 target 변경, version/hash collision과 stale CAS는 이전 active를 유지한 채 거부합니다.

목록과 상세 use case는 `StayPolicyResolver` 하나를 공유합니다. 단일 active snapshot에서 place override, category default 순으로 조회하며 둘 다 없으면 임의 숫자 대신 unavailable/null과 null provenance를 반환합니다.

## 공통 비동기 run 경계

`application.asyncrun`은 도메인 payload를 모르는 worker lifecycle 계약을 소유합니다. `AsyncRunWorker`는 claim과 terminal/retry 상태 조정만 담당하고, `RunExecutionPolicy`는 30초 lease·10초 heartbeat·50개 claim·최대 5회·1초 기반 60초 상한 full jitter·60초 deadline을 고정합니다. `ThreadedRunExecutionSupervisor`는 deadline, 주기 heartbeat와 graceful drain을 담당하며 lease/fencing 권한을 잃은 실행을 interrupt한 뒤 terminal 상태를 쓰지 않습니다.

`global.asyncrun`의 JDBC adapter는 `FOR UPDATE SKIP LOCKED`로 queued 또는 lease가 만료된 running run을 경쟁 없이 claim합니다. 모든 heartbeat, retry와 terminal 갱신은 증가하는 fencing token을 조건으로 수행합니다. durable command input과 위치 정리는 이 경계에 포함하지 않으며 각각 별도 Issue가 소유합니다.

`schedule_revision_runs`는 generation/compute run과 discriminator 없이 분리된 일정 보정 identity/lifecycle 부모입니다. canonical 사용자·여행·base 일정·target Day를 실제 복합 FK로 고정하고 같은 사용자/여행의 idempotency identity와 active base/Day scope를 DB unique로 직렬화합니다. 이 foundation은 queued 생성, lease/fencing 호환 상태와 terminal 불변성만 소유하며 HTTP 접수, structured command input, MCP call log와 결과 후보는 후속 Issue가 소유합니다.

`application.commandinput`은 HTTP나 JDBC를 모르는 immutable command snapshot과 canonical JSON/SHA-256 계약을 소유하고, `global.commandinput` JDBC adapter가 `compute_run_inputs`에 한 번 저장하고 parent별로 복원합니다. snapshot은 generic compute, itinerary generation, schedule revision parent 중 실제 FK 하나만 참조하며 owner·여행·base 일정·run type을 부모와 재검증합니다. structured input은 denylist가 아니라 run type/schema version별 exact field·type projection으로 닫아 unknown/alias/nested raw object를 Java와 DB에서 동일하게 거부합니다. `spare_time` window는 연도 0001~9999, 실제 Gregorian 날짜, 시·분·초 범위, optional 1~9자리 fraction, `Z` 또는 최대 `±18:00` offset만 허용하는 canonical RFC3339 부분집합입니다. 위치는 `GRID_100M`, `PLACE`, `STOP` closed union만 허용합니다. DB가 최초 `completed` 여행 전이에 `trip_plans.trip_ended_at`을 한 번 기록해 불변화하며, 실제 parent terminal과 이 canonical 여행 종료 anchor의 +24시간 중 earliest cutoff만 제한 DB 함수로 단조 단축합니다. `expires_at <= evaluated_at`은 due입니다. Issue #168 release gate 전에는 production 위치 접수를 default-off로 유지합니다. HTTP intake, MCP 호출 hash/log와 due payload redaction 실행은 각각 후속 Issue가 소유합니다.

## FCM 다음 목적지 출발 알림 경계

Issue #112의 canonical 계약은 `docs/contracts/domains/fcm-departure-notification/contract.json`이다. Spring이 FCM token registry, logical job, per-device delivery attempt, 취소, 제한 재시도와 provider 접수 상태의 단일 소유자이며 FastAPI는 token·credential·job·attempt·발송·알림 상태에 관여하지 않는다. Issue #115 `notification_jobs`는 기기와 무관한 일정 근거별 한 건이다. Issue #116 `push_delivery_targets`는 claim commit 후 첫 preparation transaction의 eligibility 재검사 뒤 고정하는 닫힌 snapshot/current state다. `push_delivery_attempts`는 `(jobId, pushDeviceId, attemptNo)`별 단일 행이고 같은 status를 `RESERVED → CALL_STARTED → terminal` CAS로 갱신해 terminal 뒤 immutable하게 유지한다. expired `LEASED`는 owner/lease와 fencing만 교체·증가시키는 `LEASED → LEASED` exact reclaim이며 generation·target·attempt identity를 보존하고 old fence completion을 거부한다. reclaim 뒤 `RESERVED`는 같은 row의 retryable failure, `CALL_STARTED`는 같은 row의 `ACCEPTANCE_UNKNOWN`으로 복구한다.

generation/expectedGeneration의 single generation naming만 사용한다. preparation eligible target이 0건이면 empty snapshot과 `CANCELLED/NO_ACTIVE_PUSH_TARGET`을 같은 transaction에 남기고 attempt/provider 호출은 0회다. target은 `UNATTEMPTED|RETRYABLE → RESERVED → IN_FLIGHT → terminal/retryable` 및 `UNATTEMPTED|RETRYABLE → SKIPPED` closed transition만 따른다. 호출 직전 inactive retry target은 attemptNo+1 exact terminal `SKIPPED` row·target `SKIPPED`·job aggregation을 same CAS transaction에 쓰고 provider 호출은 0회다. duplicate key/stale CAS는 무수정·provider 0회다. completion은 existing exact `CALL_STARTED` attempt와 `IN_FLIGHT` target에서만 attempt·target·job aggregation을 같은 CAS transaction으로 반영한다. absent/reserved/terminal/wrong-marker/second completion과 duplicate reservation은 무수정 거부한다.

앱 종료 상태의 사용자 표시 메시지는 `notification + data`다. `notifyAt = targetArrivalAt - expectedTravelDurationSeconds - safetyBufferMinutes`, `expiresAt = min(notifyAt + 15분, targetArrivalAt)`로 계산하고 `scheduledAt`은 `notifyAt`의 alias다. 세 시각은 UTC `timestamptz`로 저장한다. trusted `evaluatedAt`에 대해 notifyAt/expiresAt이 모두 미래일 때만 생성하며 equality/past는 생성·즉시 발송·provider 호출을 모두 금지한다. provider TTL은 `min(900, floor(expiresAt - sendAttemptAt))`만 사용한다. Android는 high priority와 `collapse_key`, APNs는 alert+sound와 `apns-expiration=sendAttemptAt+TTL` epoch seconds 및 `apns-collapse-id`에 같은 canonical collapse key를 사용한다. collapse key의 tripId는 canonical lowercase UUID를 regex와 UUID roundtrip으로 검증한다. safety buffer는 기본 10분, integer 0..120분 inclusive다. 여행 시간대로 표시하며 DST overlap의 두 offset과 DST gap은 모두 fail-closed다. data는 다섯 string field만 허용하고 canonical lowercase UUID·canonical deep link, key/value/전체 UTF-8 byte budget과 결정적 title/body fallback을 적용한다.

OS 알림 권한, 서버 출발 알림 설정과 최신 required 위치 동의를 예약 시점과 발송 직전에 확인하며 각 target 호출 직전 recheck를 포함한다. claim과 preparation 사이 기기 변화는 preparation snapshot에 반영하고 snapshot 뒤 신규 기기는 제외한다. 호출 직전 device 비활성은 `SKIPPED`, job-wide 철회는 남은 호출 없이 `CANCELLED`다. 동의 version은 canonical nonblank string이고 missing/null/blank/wrong type/unknown status는 fail-closed한다. 일정 버전 변경·항목 완료/건너뜀·여행 취소·알림 비활성화는 이전 미발송 작업을 취소하며 deduplication key와 generation fencing으로 stale worker를 거부한다. `safetyBufferMinutes` 변경도 preference CAS부터 old generation 무효화·미발송 job 취소·재계산·새 job까지 원자 수행한다. TTL은 최대 15분이면서 유효 출발 시각을 넘지 않고, 만료되면 보내지 않는다.

FCM 접수는 단말 전달 완료가 아니다. provider message id는 `ACCEPTED` 증거일 뿐 `DELIVERED`로 표현하지 않는다. explicit transient rejection과 request byte 미전송이 증명된 pre-connect failure만 재시도한다. post-write/read timeout, connection reset, unexpected EOF 같은 일반 post-write ambiguity는 terminal `ACCEPTANCE_UNKNOWN`으로 남겨 자동 재시도하지 않는다. 세 번째/만료 transient attempt도 유실하지 않고 job `DEAD`와 원자 보존한다. 앱 재진입 시에는 푸시 payload가 아니라 `live-state`를 다시 조회한다. #93과 #113~#116의 정정된 구현, ADC 또는 secret mount가 검증되기 전에는 production default-off와 fail-closed를 유지한다.

## 외부 데이터 적재 경계

`global.externalapi`는 provider 설정과 공통 HTTP 실행 경계를 소유합니다. `ExternalApiExecutor`는 allowlist Base URL에서만 target을 조립하고 timeout·제한적 GET retry·circuit breaker·압축 해제 후 body 상한·content type·redirect 정책을 한 곳에서 적용합니다. provider별 adapter는 고정된 `ExternalApiOperation`과 parser만 제공하며 자체 retry client를 만들지 않습니다. metric tag는 enum 기반 provider/service/operation/result로 제한하고 외부 query·header 비밀값과 raw payload를 기록하지 않습니다.

```text
TourAPI · TAGO · KMA 원천 응답
              │
              ▼
data_import_runs ── data_import_checkpoints
              │
              ▼
external_api_snapshots (버전이 명시된 raw snapshot)
              │ 검증·파싱
              ▼
detailInfo2 complete sweep (ordered page manifest·scope watermark)
              │
              ▼
장소 · 교통 · 날씨 정규화 read model
              │
              ▼
Spring 공개 API · 일정 계산용 facts
```

- import run은 parser/schema 버전을, raw snapshot은 parser version과 payload hash를 보존해 재처리와 감사가 가능하도록 합니다. 공개 API는 raw payload가 아니라 정규화 read model만 읽습니다.
- TourAPI importer는 공통 `tour_api_operations` registry와 `tour_api_operation_provenance`를 재사용합니다. writer는 active operation과 provider/service/operation/snapshot/run/request fingerprint 계보를 정규화 callback 전에 조회해 invalid command를 선거부하고, 정규화 쓰기와 provenance 쓰기는 하나의 transaction에서 실행합니다. callback은 현재 transaction에 참여하는 DB write만 허용하며 외부 호출·메시지·파일 같은 rollback 불가능한 부수효과를 금지합니다. provenance trigger는 최종 방어층으로 계보를 다시 검증하고 allowlist 7개 normalized table의 실제 UUID 행을 `FOR KEY SHARE`로 잠가 orphan provenance를 거부합니다. 각 대상 테이블의 식별자 `BEFORE UPDATE`와 `BEFORE DELETE` guard는 이 잠금과 직렬화된 뒤 커밋된 provenance가 있으면 `23503`으로 변경·삭제를 거부합니다. `place_details`의 식별자는 `place_id`이고 나머지 6개 타입은 `id`입니다. 새 normalized entity type은 allowlist, 정확한 PK branch, identifier mutation guard를 함께 확장해야 합니다. 같은 정규화 행은 여러 operation 계보를 가질 수 있지만 같은 operation/snapshot 계보는 한 번만 기록합니다. provenance는 fingerprint를 다시 계산하지 않고 `SnapshotSaveResult.requestFingerprint`를 `TourApiProvenanceCommand.fromSnapshot(...)`으로 그대로 상속합니다. 정밀 위치 container의 fingerprint 입력은 nested map key를 재귀 정렬하고 list 순서를 보존하며, 내부 secret·원문 URL·PII는 redaction marker로 치환하고 좌표 값만 요청 구분에 유지합니다.
- TourAPI 기준 코드 동기화는 `ldongCode2`·`lclsSystmCode2` 응답을 각각 `areaCode2`·`categoryCode2` provenance operation으로 연결합니다. operation별 import run과 redaction snapshot을 먼저 만들고, 제주 법정동 루트·시군구 및 관광 신분류 부모 계층을 전체 검증한 뒤 `external_reference_codes` batch와 provenance를 같은 JDBC transaction에서 멱등 upsert합니다. 같은 snapshot replay는 정규화 행과 감사 시각을 바꾸지 않으며 겹치는 유효기간이나 계보 불일치는 전체 batch를 rollback합니다.
- importer는 `application.snapshot.SnapshotStoreService`만 사용합니다. decompression이 끝난 원문 바이트는 2 MiB 이하만 받고 SHA-256은 해당 byte sequence에 적용합니다. request fingerprint의 단일 생성 소유자는 `CanonicalSnapshotRequestFingerprinter`이며 `snapshot-request-v1` schema의 provider, service, operation, scope, page, privacy-canonical metadata를 UTF-8 byte length로 구분해 SHA-256을 계산합니다. metadata key 순서는 정렬하고 service key·인증값·원문 URL·사용자 PII는 동일 redaction marker로 치환하므로 값이 달라도 fingerprint가 변하지 않습니다. 정밀 위치는 저장·로그하지 않되 요청 구분을 위해 fingerprint 입력에만 사용하고 즉시 폐기합니다. JSON/XML/UTF-8 text와 저장용 request metadata는 `snapshot-redaction-v2` registry로 secret·사용자 PII·정밀 위치를 제거하며 XML namespace는 localName 기준으로 같은 정책을 적용하되 namespace 선언 URI는 보존합니다. malformed·binary payload는 `raw_payload=NULL`인 rejected/ignored 감사 행만 남깁니다. 동일 byte라도 payload format·최초 상태·오류 의미가 다르면 replay하지 않고 collision으로 거부합니다. `received`는 terminal 상태로 한 번만 전환하고 성공 payload는 30일, 실패·미파싱 payload는 7일 보존합니다. 실제 삭제는 별도 retention 작업이 `purge_after` 이후 payload만 비우고 `purged_at`을 기록합니다.
- `detailInfo2`는 각 page를 먼저 `SnapshotStoreService`로 저장하고 gateway가 반환한 동일 byte만 parser에 전달합니다. replay 결과는 DB에 보존된 최초 `fetched_at`과 현재 parse status를 사용하며 `received`만 terminal 전이하고 이미 `parsed`인 true replay는 전이를 반복하지 않습니다. 모든 page 검증 뒤 ordered snapshot ID·request fingerprint·payload hash·raw count manifest를 complete sweep으로 원자 수용하며, 이 row 독립 scope watermark가 empty 응답도 기억합니다. item의 `(source_sweep_id, source_snapshot_id)`는 실제 sweep page membership으로 강제되고 누락 lifecycle은 complete sweep에서만 전이합니다.
- TAGO 도착정보 adapter는 공개 Controller 없이 application port 뒤에 둡니다. provider/service/city/stop/node 전체 fingerprint의 generation·owner fencing·lease·terminal outcome만 `tago_arrival_flights`에 보존해 여러 인스턴스 요청을 합칩니다. provider가 idempotency를 지원하지 않는 한 임의의 JVM pause·partition까지 외부 호출 exactly-once를 보장할 수는 없으며, 보장 범위는 중복 호출 억제와 current owner/generation만 DB 결과를 publish하는 것입니다. claim/poll SQL은 connection을 즉시 반환하고 `source.fetch` 동안 Spring transaction과 DB connection을 점유하지 않습니다. 응답 뒤 processor transaction은 어떤 write보다 먼저 current owner/generation/RUNNING/DB lease row를 `FOR UPDATE` 검증하고, snapshot/run/arrival 변경 뒤 DB clock lease를 다시 검사하는 terminal CAS 1행을 마지막에 수행합니다. CAS 0이면 nested REQUIRED 변경도 전부 rollback합니다. 성공 retain은 source `expiresAt`과 terminal replay window 중 이른 시각이며 이미 만료된 결과는 publish하지 않습니다. retained success를 읽는 사이 source가 만료되면 bounded re-observe 뒤 retain 경계에서 새 generation을 claim합니다. expired RUNNING은 즉시 steal하지 않고 `ABANDONED/DATA_UNAVAILABLE` quarantine으로 fail-closed하고, 만료 terminal은 partial cleanup index를 쓰는 `SKIP LOCKED` batch 32로 current fingerprint·retained·RUNNING을 제외해 정리합니다. leader는 claim 반환 직후 interrupt와 deadline을 다시 검사하고 DB history를 재조회하며, loser는 monotonic deadline 안에서만 poll합니다. `RATE_LIMITED`, `TIMEOUT`, `PROVIDER_UNAVAILABLE`일 때만 실패 완료 시각 기준 120초 이하인 마지막 관측을 stale로 반환하고, 공식 `EMPTY_RESULT`·DB `DATA_UNAVAILABLE`·계약 오류에는 stale을 반환하지 않습니다. flight state에는 raw provider body/message·credential·PII를 저장하지 않습니다.
- `application.importing`은 Spring 비의존 생명주기 service·port·불변 값만 소유하고 `global.importing`의 JDBC adapter가 `data_import_runs`에 접근합니다. 공개 Controller는 없습니다. 같은 idempotency key의 동일 요청만 기존 run과 쓰기 lease를 재사용하고, 요청 fingerprint나 버전이 다르면 거부합니다. replay 결과는 DB에 보존된 실제 `running`·`succeeded`·`failed`·`partial`·`cancelled` 상태와 count를 함께 반환하므로 importer가 terminal 실패나 실행 중 run을 성공으로 오인할 수 없습니다. 다른 idempotency key의 동일 provider/service/operation/scope 동시 `running`은 DB unique 경계로 차단합니다.
- count 누적과 terminal 전이는 하나의 조건부 UPDATE로 수행합니다. run ID, owner token, fencing token과 `running` 상태가 모두 일치해야 하며 정수 overflow 또는 stale writer이면 어떤 count와 상태도 부분 반영하지 않습니다. `partial`은 마지막 count와 고정 오류 분류를 같은 문장에서 기록하고 raw DB/provider 예외, URL query, key, token, PII는 저장·노출하지 않습니다.
- 외부 정규화 행은 `parsed`/`tombstoned` snapshot과 동일한 import run을 반드시 연결하고 provider·service·operation·scope 불일치를 DB에서 거부합니다. 같은 snapshot과 run의 재실행은 정규화 내용이 같을 때만 멱등 처리하며, 내용이 달라지는 upsert는 새 snapshot과 그 snapshot의 matching run을 함께 연결해야 합니다. `manual`·`fixture`·`admin_upload`는 snapshot 필수성의 명시적 예외이며 이전·새 행이 모두 예외 성격을 유지할 때 편집할 수 있습니다. 외부 lineage 없는 legacy 행과 snapshot-backed 외부 행 모두 marker를 예외 값으로 바꾸면서 lineage를 제거할 수 없습니다. marker가 이미 예외 값이어도 OLD snapshot/run의 실제 `source_kind`·provider가 외부이면 내용 변경과 계보 제거를 거부합니다. 유효한 새 snapshot과 matching run을 동시에 연결하는 정상 재수집 repair/upsert는 허용합니다. retention 삭제는 정규화 내용과 run을 유지하고 snapshot 포인터만 제거하며, 이후에도 새 원문을 연결하기 전에는 내용과 마지막 run을 바꿀 수 없습니다. `data_import_runs`는 origin과 무관한 provenance ledger이므로 16개 정규화 테이블 중 하나라도 run을 참조하면 snapshot 유무나 `source_kind`·provider가 외부/fixture/admin인지와 관계없이 부모 DELETE를 `23503`으로 거부합니다. 정규화 참조가 없는 succeeded·failed·fixture·admin run은 삭제할 수 있습니다. 16개 FK의 정확한 table/column mapping은 catalog audit로 고정합니다. 기존 8개 `NO ACTION`과 8개 `SET NULL`의 삭제 동작은 바꾸지 않지만, `BEFORE DELETE` guard가 referential action 전에 모든 live reference를 검사하므로 정책은 `confdeltype`에 의존하지 않습니다. 기존 non-NULL lineage와 snapshot-backed optional marker 불일치도 전체 정규화 테이블에서 소급 감사합니다.
- 한 import run의 `source_kind`·provider·service·operation·scope는 생성 후 바뀌지 않고, 모든 snapshot은 그 단일 범위를 공유합니다. legacy 다중 범위 실행은 자동 보정하지 않고 실행 ID와 충돌 범위를 출력해 중단하며, snapshot을 범위별 run으로 분리·격리한 뒤 재적용합니다.
- provider·service·operation·scope별 idempotency key와 provider 범위 natural key를 DB unique 제약으로 보장해 동시 재수집도 중복 행을 만들지 않게 합니다. 멱등 marker의 가장 오래된 행만 partial unique `ON CONFLICT` arbiter이고 grandfathered 동생 행이 남아 있으면 선삭제할 수 없습니다. 실행 중 marker는 후속 중복을 격리하되 새 동일 범위 run을 BEFORE trigger의 `23505`로 직접 거부하며 `ON CONFLICT` arbiter 의미를 갖지 않습니다. 신규 행은 두 계약을 모두 적용받습니다.
- checkpoint는 범위별 마지막 성공 위치이며 `advance_data_import_checkpoint(...)`의 기대 version CAS로만 한 단계 전진합니다. stale writer는 `40001`로 실패하고 source scope 변경, 이전 run 역행, DELETE·TRUNCATE는 금지합니다.
- 수집 내부 테이블은 RLS를 활성화하고 `anon`·`authenticated` 정책과 직접 권한을 만들지 않습니다. 두 역할은 checkpoint 함수도 실행할 수 없고, 서버 전용 `service_role`만 직접 UPDATE·DELETE·TRUNCATE 없이 CAS 함수를 호출합니다. 이 권한은 브라우저와 FastAPI MCP에 노출하지 않습니다.
- `service_role`은 앱 운영에 필요한 DML과 허용된 RPC 권한은 유지하지만 현재와 향후 public 앱 테이블의 `TRUNCATE` 권한은 갖지 않습니다. PostGIS 같은 확장 관리 객체의 ACL은 확장 소유자가 관리하며 앱 테이블 계약에서 제외합니다. 행 trigger를 건너뛰는 파괴적 초기화는 통제된 migration owner 경로에서만 수행합니다.
- 기준 코드·시간표·같은 요일 open/closed 영업시간과 자정을 넘는 영업시간은 유효기간이 겹칠 수 없습니다. exclusion constraint 전에 legacy pair audit를 수행하고 정확한 충돌 행 ID로 중단하며 데이터를 조용히 삭제·병합하지 않습니다. 장소 행의 MVCC 쓰기 펜스로 교차 요일 검사를 직렬화하므로 오래된 `REPEATABLE READ` writer는 `40001`로 실패합니다. 정확히 `00:00`에 끝나는 구간은 다음 날을 점유하지 않습니다.

확정된 `candidate`·`active` 일정은 항목과 이동 구간뿐 아니라 여행 일자의 날짜·시간 창도 변경할 수 없습니다. 봉인과 Day/여행 날짜 변경은 같은 `trip_plan` 행에 MVCC 쓰기 펜스를 세우므로 `READ COMMITTED`에서는 최신 상태를 다시 검사하고, 오래된 `REPEATABLE READ` writer는 `40001`로 실패합니다. 이 write-skew 경계는 실제 2세션 계약으로 검증합니다. 일정 항목의 시작과 종료는 모두 같은 제주 현지 Day 안에 있어야 합니다. 일정 버전의 `version_no`는 생성 후 바꿀 수 없고 base는 더 작은 번호만 가리킵니다. 날씨 영향과 추천 후보의 `trip_day_id`는 legacy NULL을 보존하되 신규 행에는 필수이며 item·leg·compute run과 같은 Day를 복합 FK로 공유합니다. legacy NULL-Day 결과는 부모 compute/item/leg의 Day와 자신의 계보를 함께 동결하고, 결과의 `trip_day_id`와 같은 Day의 부모를 한 번에 지정하는 명시적 repair만 허용합니다.

## ArchUnit 규칙

`services/spring-api`의 `ArchitectureTest`는 Controller의 Repository 직접 의존 금지, Controller의 Service 경유, 도메인 간 순환 의존 금지, Domain의 Global 내부 구현 의존 제한, application 현재 사용자 계약의 Spring 비의존성, MVC 계층 이름 규칙을 검사합니다. production-neutral domain 계약 fixture가 `CurrentUserAccessor`를 실제 소비하므로 향후 도메인 사용 시에도 `Jwt`나 `SecurityContext`가 누출되지 않는지 지속해서 검증합니다.

## 서비스 간 경계

- 외부 공개 `/api/v1/**`, 사용자 인증·인가, DB와 외부 API는 Spring API가 담당합니다.
- FastAPI MCP는 private network의 `/mcp`로만 호출합니다.
- Spring은 정규화된 facts를 전달하고 FastAPI는 계산 결과를 `structuredContent`로 반환합니다.
- FastAPI는 DB·외부 API·사용자 JWT에 직접 접근하지 않습니다.
- Spring 관점의 wire 계약은 이 저장소의 `docs/designs`에서 관리하고, FastAPI 구현 계약은 [AI 저장소 문서](https://github.com/Timing-Jeju/jeju_AI/blob/develop/docs/FASTAPI_MCP_CONTRACT.md)에서 관리합니다.
- 양쪽 계약을 바꿀 때는 두 저장소에 Issue와 PR을 각각 만들고 계약 버전과 fixture 호환 순서를 먼저 합의합니다.

## CI 경계

- 공통 정책과 저장소 자동화 검사는 모든 변경에서 실행합니다.
- `services/spring-api` 변경은 Spring 검사와 Docker Health Check를 실행합니다.
- 서비스 간 계약 변경은 이 저장소에서 Spring과 계약 검사를 실행합니다.
- FastAPI의 uv 잠금, Ruff, mypy와 pytest는 `jeju_AI` 저장소의 독립 CI에서 실행합니다.
- 문서만 변경하고 서비스 계약을 건드리지 않으면 무거운 서비스 검사를 생략합니다.
- 각 Job은 독립적으로 실행되지만 최종 `quality-gate`가 결과를 하나로 집계합니다.
