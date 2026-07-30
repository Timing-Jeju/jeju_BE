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
- 마이그레이션은 최초 public 스키마 → 데이터 무결성 강화 → 외부 적재 기반 순서로 누적 적용하며 기존 파일을 수정하지 않습니다.
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

- raw snapshot은 parser/schema 버전과 payload hash를 함께 보존해 재처리와 감사가 가능하도록 합니다. 공개 API는 raw payload가 아니라 정규화 read model만 읽습니다.
- `import_run_id`와 `source_snapshot_id`로 원천 실행부터 정규화 행까지 lineage를 추적하고, provider·service·operation·scope 불일치를 DB에서 거부합니다.
- provider·service·operation·scope별 idempotency key와 provider 범위 natural key를 DB unique 제약으로 보장해 동시 재수집도 중복 행을 만들지 않게 합니다.
- checkpoint는 범위별 마지막 성공 위치입니다. 같은 provider·service·operation·scope의 `succeeded` 실행만 참조할 수 있고, 참조된 실행을 실패 상태로 되돌리는 변경도 DB가 거부합니다.
- 수집 내부 테이블은 RLS를 활성화하고 `anon`·`authenticated` 정책과 직접 권한을 만들지 않습니다. raw snapshot, 거부 레코드와 checkpoint는 Spring의 서버 전용 `service_role`만 접근하며 브라우저와 FastAPI MCP에는 노출하지 않습니다.

확정된 `candidate`·`active` 일정은 항목과 이동 구간뿐 아니라 여행 일자의 날짜·시간 창도 변경할 수 없습니다. 일정 항목의 시작과 종료는 모두 같은 제주 현지 Day 안에 있어야 합니다. 일정 버전의 `version_no`는 생성 후 바꿀 수 없고 base는 더 작은 `version_no`만 가리키므로 순환 계보가 생기지 않으며, 날씨 영향과 추천 후보는 item·leg·compute run과 같은 `trip_day_id`를 복합 FK로 공유합니다.

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
