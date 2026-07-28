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

`/api/v1/**`는 Supabase Auth access token이 필요한 stateless Resource Server입니다. 운영과 최신 로컬 CLI는 비대칭 signing key와 JWKS를 사용하며, 기본·운영 issuer/JWKS는 HTTPS만 허용합니다. 로컬 HTTP는 정확한 `local`/`local-hs256` profile의 loopback 또는 `host.docker.internal`에서만 허용하고 legacy HS256은 `local-hs256` 전용입니다. 환경변수와 검증 계약은 저장소의 [인증·인가 설정](../../docs/AUTHENTICATION.md)을 따릅니다.
