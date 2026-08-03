# Timing Jeju Spring API

Timing Jeju의 공개 REST API, 인증·인가, 데이터베이스와 외부 API 연동, MCP 입력 조립과 결과 검증·저장을 담당하는 Spring Boot 서비스입니다.

## 실행

```bash
./gradlew bootRun
```

## 검사

```bash
./gradlew clean check
./gradlew unitTest sliceTest integrationTest architectureTest
```

Docker와 저장소 전체 검증은 저장소 루트에서 `./scripts/quality-gate.sh`를 실행합니다.

### PostgreSQL Repository 통합 테스트

Repository 통합 테스트는 `PostgreSqlRepositoryIntegrationTestSupport`를 상속합니다. 이 공통 기반은 `postgresql-integration` profile과 `@Tag("integration")`을 적용하고, Spring Boot `@ServiceConnection`으로 PostGIS 16 Testcontainer를 임의 포트에 연결합니다. 각 테스트는 Spring 테스트 트랜잭션에서 실행되어 종료 시 자동 rollback됩니다.

컨테이너 초기화에는 `db/local-postgres/auth_compat.sql`을 먼저 사용한 뒤 `supabase/migrations`의 timestamp SQL을 정렬해 원본 그대로 적용합니다. `seed_fixtures.sql`과 `supabase/seed.sql`은 적용하지 않으므로 테스트는 빈 canonical schema에서 시작하며, 필요한 데이터만 테스트 안에서 생성해야 합니다. Flyway, 고정 포트, 컨테이너 재사용 설정, 고정 DB 비밀번호와 별도 환경 변수는 사용하지 않습니다.

```bash
./gradlew integrationTest --tests '*Repository*Test'
```

Docker daemon을 사용할 수 없으면 테스트는 원인을 한국어로 안내합니다. PostgreSQL 17과 실제 Supabase 호환성은 일반 PostgreSQL Testcontainer로 대체하지 않고 저장소 루트의 `./scripts/supabase-smoke-test.sh`에서 두 번의 `supabase db reset`과 함께 검증합니다.

## 인증·인가

`/api/v1/**`는 Supabase Auth access token이 필요한 stateless Resource Server입니다. 운영과 최신 로컬 CLI는 비대칭 signing key와 JWKS를 사용하며, 기본·운영 issuer/JWKS는 HTTPS만 허용합니다. 정확한 `local` profile은 로컬 JWKS, 정확한 `local-hs256` profile은 legacy HS256만 허용하며 다른 profile과 조합할 수 없습니다. 검증된 현재 사용자는 Spring 비의존 `application.security` 계약으로 제공됩니다. 환경변수와 검증 계약은 저장소의 [인증·인가 설정](../../docs/AUTHENTICATION.md)을 따릅니다.

`GET /api/v1/auth/social/providers`와 `GET /api/v1/auth/social/naver/userinfo`만 Supabase 소셜 OAuth 연동용 공개 endpoint입니다. 카탈로그는 지원 목록이며 Supabase의 실제 활성화 상태가 아닙니다. Google·Kakao는 프론트엔드 Supabase SDK가 시작하고, Naver UserInfo adapter는 provider access token을 저장하지 않으며 엄격한 token/envelope 검증과 rate limit·bulkhead를 적용합니다. Redirect URLs의 단일 권위, Naver PKCE·이메일 신뢰 경계와 `APP_SOCIAL_LOGIN_PROVIDER_IDS`는 [소셜 로그인 설정](../../docs/SOCIAL_LOGIN.md)을 따릅니다.
