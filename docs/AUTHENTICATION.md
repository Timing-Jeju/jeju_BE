# Supabase 인증·인가 설정

## 검증 경계

Spring API는 Supabase Auth가 발급한 access token을 OAuth2 Resource Server로 검증합니다. 회원가입, 로그인, 로그아웃, token 갱신과 비밀번호 재설정은 Supabase Auth SDK가 담당하며 Spring은 같은 기능의 endpoint나 자체 JWT 발급기를 만들지 않습니다.

- `/api/v1/**`: 인증 필수
- `/actuator/health`, `/actuator/info`: 공개
- `/v3/api-docs/**`, `/swagger-ui/**`: 해당 springdoc 기능이 활성화된 경우에만 공개
- 그 밖의 경로: 기본 거부

서버 세션은 만들지 않습니다. 인증은 쿠키가 아닌 `Authorization: Bearer <access-token>` 헤더만 사용하므로 CSRF는 비활성화했습니다. CORS는 환경변수에 명시된 정확한 Origin만 허용하고 wildcard 및 credential 요청은 허용하지 않습니다.

## JWT 검증 계약

서명 검증 후 다음 claim을 모두 확인합니다.

| claim | 계약 |
| --- | --- |
| `exp` | 만료 전이며 30초 clock skew 이내 |
| `nbf` | 활성 시점 이후이며 30초 clock skew 이내 |
| `iss` | `SUPABASE_JWT_ISSUER`와 정확히 일치 |
| `aud` | `SUPABASE_JWT_AUDIENCE` 포함, 기본값 `authenticated` |
| `role` | `authenticated`만 허용 |
| `sub` | UUID 형식 |
| `session_id` | 존재하면 UUID 형식 |

`anon`, `service_role`, 잘못된 audience/issuer와 UUID가 아닌 `sub`는 401로 거부합니다. `user_metadata`, 이메일과 nickname은 인증·소유권 판단에 사용하지 않습니다. 검증된 신원은 `CurrentUser` 값 객체로 변환하며 도메인 코드에 Spring Security `Jwt`를 전달하지 않습니다.

인증 실패는 `401 AUTH_TOKEN_INVALID`, 인증된 사용자의 접근 거부는 `403 AUTH_ACCESS_DENIED`입니다. 두 응답은 한국어 `message`와 요청 추적용 `traceId`를 포함하며 token, JWT payload와 개인정보는 응답이나 로그에 기록하지 않습니다.

## 환경변수

| 변수 | 용도 | 로컬 | 운영 |
| --- | --- | --- | --- |
| `SUPABASE_JWT_ISSUER` | access token의 정확한 `iss` | 필수 | 필수 |
| `SUPABASE_JWT_AUDIENCE` | 기대 `aud`, 기본 `authenticated` | 선택 | 선택 |
| `SUPABASE_JWKS_URL` | 공개 signing key 조회 주소 | JWKS 사용 시 필수 | 필수 |
| `SUPABASE_JWT_SECRET` | legacy HS256 검증용 shared secret | `local-hs256`에서만 필수 | 주입 금지 |
| `APP_CORS_ALLOWED_ORIGINS` | 쉼표로 구분한 프론트엔드 Origin allowlist | 필수 | 필수 |
| `SPRINGDOC_API_DOCS_ENABLED` | OpenAPI JSON 활성화 | 기본 `true` | 공개하지 않으면 `false` |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | Swagger UI 활성화 | 기본 `true` | 공개하지 않으면 `false` |

운영은 profile을 지정하지 않고 JWKS 전략을 사용합니다. `SUPABASE_JWKS_URL`이 없거나 `SUPABASE_JWT_SECRET`이 주입되면 애플리케이션 시작에 실패합니다. 운영 secret은 배포 플랫폼의 Secret 기능에서만 관리하며 `service_role` 또는 secret key를 사용자 요청 인증에 사용하지 않습니다.

## 로컬 Supabase CLI

Supabase CLI 2.110.0으로 실제 signup token을 확인한 결과는 `alg=ES256`, `iss=http://127.0.0.1:54321/auth/v1`, `aud=authenticated`, `role=authenticated`, UUID `sub`였습니다. 따라서 로컬 기본도 비대칭 JWKS를 사용합니다.

```text
SPRING_PROFILES_ACTIVE=local
SUPABASE_JWT_ISSUER=http://127.0.0.1:54321/auth/v1
SUPABASE_JWT_AUDIENCE=authenticated
SUPABASE_JWKS_URL=http://127.0.0.1:54321/auth/v1/.well-known/jwks.json
SUPABASE_JWT_SECRET=
APP_CORS_ALLOWED_ORIGINS=http://localhost:3000
```

Spring을 Docker에서 실행하고 Supabase CLI를 호스트에서 실행하면 JWKS 조회 주소만 `http://host.docker.internal:54321/auth/v1/.well-known/jwks.json`으로 설정합니다. issuer는 token의 실제 값인 `http://127.0.0.1:54321/auth/v1`을 유지합니다.

이전 CLI나 별도 self-hosted 설정이 HS256 access token을 발급하는 경우에만 다음 호환 경로를 사용합니다.

```text
SPRING_PROFILES_ACTIVE=local-hs256
SUPABASE_JWT_SECRET=<supabase status -o json에서 로컬로 얻은 값>
```

shared secret을 명령 출력, 문서, fixture 또는 Git에 남기지 않습니다. `./scripts/supabase-smoke-test.sh`는 임시 가짜 사용자를 signup하고 실제 token claim과 Spring 보호 API를 검증한 뒤 Supabase volume과 임시 파일을 모두 정리합니다.

## JWKS와 key rotation

Supabase 공식 JWKS 경로는 `/auth/v1/.well-known/jwks.json`입니다. Spring의 Nimbus decoder가 공개키 조회와 캐시를 담당하며 애플리케이션 시작 시에는 JWKS 네트워크 호출을 요구하지 않습니다. 한 번 조회한 key는 캐시에 남아 있으므로 JWKS endpoint의 일시 장애 중에도 같은 `kid`의 기존 token을 검증할 수 있습니다. 아직 조회하지 않은 `kid`나 cache 밖의 key는 JWKS 장애 중 거부됩니다.

Supabase Edge의 JWKS cache는 10분이며 signing key 교체 시 standby key 생성 또는 이전 key 폐기 후 최소 20분의 전파 시간을 권장합니다. 애플리케이션에서 이보다 긴 별도 캐시를 추가하지 않습니다.

2026년 7월 self-hosted Auth 변경으로 `API_EXTERNAL_URL` 기본값에 `/auth/v1`이 포함되었습니다. `GOTRUE_JWT_ISSUER`도 이 값을 사용하므로 self-hosted 설정을 올릴 때 issuer에 `/auth/v1`이 중복되거나 누락되지 않았는지 실제 access token에서 다시 확인합니다.

## 검증

```bash
cd services/spring-api
./gradlew clean check
cd ../..
./scripts/quality-gate.sh
./scripts/supabase-smoke-test.sh
./scripts/docker-smoke-test.sh
```
