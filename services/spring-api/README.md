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

## 인증·인가

`/api/v1/**`는 Supabase Auth access token이 필요한 stateless Resource Server입니다. 운영과 최신 로컬 CLI는 비대칭 signing key와 JWKS를 사용하며, 기본·운영 issuer/JWKS는 HTTPS만 허용합니다. 정확한 `local` profile은 로컬 JWKS, 정확한 `local-hs256` profile은 legacy HS256만 허용하며 다른 profile과 조합할 수 없습니다. 검증된 현재 사용자는 Spring 비의존 `application.security` 계약으로 제공됩니다. 환경변수와 검증 계약은 저장소의 [인증·인가 설정](../../docs/AUTHENTICATION.md)을 따릅니다.
