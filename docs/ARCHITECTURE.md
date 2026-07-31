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
- 운영 또는 공유 환경에 적용된 migration은 수정하지 않고, 모든 후속 변경은 더 큰 timestamp의 새 migration으로만 추가합니다.
- 마이그레이션은 최초 public 스키마 → `20260730000000` 기본 무결성 → `20260730010000` 외부 적재 기반 → `20260730020000` 적재 일관성 → `20260730030000` 일정 일관성 순서로 누적 적용합니다.
- 로컬 Supabase와 운영 Supabase는 같은 마이그레이션을 사용하지만 Auth·DB 인스턴스와 사용자 데이터는 공유하지 않습니다.
- Supabase 소유 `auth` 스키마·`auth.users`·`auth.uid()`는 애플리케이션 마이그레이션이 생성·교체·삭제하지 않습니다.
- 일반 PostgreSQL Docker 검증용 호환 객체와 fixture는 `db/local-postgres`에 격리하며 운영에 적용하지 않습니다.
- Spring classpath와 `db/migration`에는 Flyway를 도입하지 않습니다. Supabase migration과 이중으로 스키마 이력을 관리하지 않으며, 도입 여부는 별도 Issue에서 결정합니다.

## 외부 데이터 적재 경계

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
장소 · 교통 · 날씨 정규화 read model
              │
              ▼
Spring 공개 API · 일정 계산용 facts
```

- import run은 parser/schema 버전을, raw snapshot은 parser version과 payload hash를 보존해 재처리와 감사가 가능하도록 합니다. 공개 API는 raw payload가 아니라 정규화 read model만 읽습니다.
- 외부 정규화 행은 `parsed`/`tombstoned` snapshot과 동일한 import run을 반드시 연결하고 provider·service·operation·scope 불일치를 DB에서 거부합니다. 같은 snapshot과 run의 재실행은 정규화 내용이 같을 때만 멱등 처리하며, 내용이 달라지는 upsert는 새 snapshot과 그 snapshot의 matching run을 함께 연결해야 합니다. `manual`·`fixture`·`admin_upload`는 명시적 예외이며 이전·새 행이 모두 예외 성격을 유지할 때 편집할 수 있습니다. 외부 lineage 없는 legacy 행과 snapshot-backed 외부 행 모두 marker를 예외 값으로 바꾸면서 lineage를 제거할 수 없습니다. marker가 이미 예외 값이어도 OLD snapshot/run의 실제 `source_kind`·provider가 외부이면 내용 변경과 계보 제거를 거부합니다. 유효한 새 snapshot과 matching run을 동시에 연결하는 정상 재수집 repair/upsert는 허용합니다. retention 삭제는 정규화 내용과 run을 유지하고 snapshot 포인터만 제거하며, 이후에도 새 원문을 연결하기 전에는 내용과 마지막 run을 바꿀 수 없습니다. 기존 non-NULL lineage와 snapshot-backed optional marker 불일치도 전체 정규화 테이블에서 소급 감사합니다.
- 한 import run의 `source_kind`·provider·service·operation·scope는 생성 후 바뀌지 않고, 모든 snapshot은 그 단일 범위를 공유합니다. legacy 다중 범위 실행은 자동 보정하지 않고 실행 ID와 충돌 범위를 출력해 중단하며, snapshot을 범위별 run으로 분리·격리한 뒤 재적용합니다.
- provider·service·operation·scope별 idempotency key와 provider 범위 natural key를 DB unique 제약으로 보장해 동시 재수집도 중복 행을 만들지 않게 합니다. 멱등 marker의 가장 오래된 행만 partial unique `ON CONFLICT` arbiter이고 grandfathered 동생 행이 남아 있으면 선삭제할 수 없습니다. 실행 중 marker는 후속 중복을 격리하되 새 동일 범위 run을 BEFORE trigger의 `23505`로 직접 거부하며 `ON CONFLICT` arbiter 의미를 갖지 않습니다. 신규 행은 두 계약을 모두 적용받습니다.
- checkpoint는 범위별 마지막 성공 위치이며 `advance_data_import_checkpoint(...)`의 기대 version CAS로만 한 단계 전진합니다. stale writer는 `40001`로 실패하고 source scope 변경, 이전 run 역행, DELETE·TRUNCATE는 금지합니다.
- 수집 내부 테이블은 RLS를 활성화하고 `anon`·`authenticated` 정책과 직접 권한을 만들지 않습니다. 두 역할은 checkpoint 함수도 실행할 수 없고, 서버 전용 `service_role`만 직접 UPDATE·DELETE·TRUNCATE 없이 CAS 함수를 호출합니다. 이 권한은 브라우저와 FastAPI MCP에 노출하지 않습니다.
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
