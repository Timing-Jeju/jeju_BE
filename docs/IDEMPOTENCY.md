# 변경 API 멱등성 계약

## 적용 범위

Issue #17은 후속 생성·계산·적용 API가 사용할 내부 application port와 PostgreSQL adapter만 제공합니다. 공개 `/api/v1/**` endpoint와 OpenAPI 경로는 추가하지 않습니다. 호출 endpoint는 인증된 Supabase JWT의 canonical `sub`, HTTP method, query·fragment가 제거된 path, `Idempotency-Key`, 원본 request body byte를 `IdempotencyRequest.create(...)`에 전달해야 합니다.

scope는 `ownerSub + method + normalizedPath + key`입니다. method는 대문자로, 연속 slash와 끝 slash는 정규화합니다. key는 canonical UUID 문자열이어야 하며 request fingerprint는 길이 구분한 method, normalized path, request body의 SHA-256입니다. 요청 원문, Authorization/Bearer token, 이메일 등 PII는 registry에 저장하지 않습니다.

## 실행과 저장

- `PROCESSING`: 최초 획득 marker이며 lease는 정확히 2분입니다.
- `COMPLETED`: status, 순서가 보존된 최소 header, body를 저장하며 완료 시점부터 TTL은 정확히 24시간입니다.
- request와 response body는 각각 1 MiB 이하입니다. 저장 header는 64 KiB 이하이며 Authorization, Cookie, Set-Cookie 계열은 거부합니다.
- 동일 scope·hash의 완료 요청은 operation을 다시 실행하지 않고 status/header/body를 그대로 재생합니다.
- 동일 scope의 다른 hash는 `409 IDEMPOTENCY_KEY_REUSED`입니다.
- lease 안의 동일 hash loser도 `409 IDEMPOTENCY_KEY_REUSED`이며 `retryAfterSeconds=1`을 제공합니다. HTTP adapter는 이를 `Retry-After: 1`로 변환해야 합니다.
- lease takeover마다 UUID attempt token을 새로 발급합니다. 만료된 이전 winner는 새 marker를 완료하거나 삭제할 수 없습니다.
- operation과 `COMPLETED` 전환은 같은 transaction입니다. 예외 또는 반환된 5xx는 rollback하고 marker를 정리하여 같은 요청의 재시도를 허용합니다. 프로세스 강제 종료로 정리가 불가능한 경우 2분 lease가 복구 경계입니다.
- loser의 조건부 UPDATE 직후 winner가 marker를 정리해 조회가 비는 경합은 최대 2회로 제한해 재획득합니다. 두 번 연속 같은 경합이면 5xx 대신 기존 `PROCESSING`/`Retry-After: 1` 계약으로 응답하여 무한 재시도를 막습니다.

## 후속 API 통합 규칙

Controller는 Repository를 직접 호출하지 않고 도메인 Service가 `IdempotencyUseCase.execute(request, operation)`에 업무 변경 callback을 전달합니다. callback 응답은 replay에 필요한 최소 비민감 body와 header만 포함해야 합니다. 사용자 profile, token, provider 원문 같은 PII 응답은 이 registry에 넘기지 않고 별도 Issue에서 암호화·재조회 전략을 먼저 확정합니다.

오류 code `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_KEY_REUSED`는 공통 Problem Details registry에 등록되어 있습니다. 후속 HTTP adapter는 `IdempotencyException`의 code/status/retryAfterSeconds를 공통 8필드 Problem Details와 필요 시 `Retry-After` header로 변환합니다.

운영 schema의 단일 기준은 `supabase/migrations/20260810000000_api_idempotency_registry.sql`이며 Flyway와 신규 환경변수는 사용하지 않습니다. `anon`과 `authenticated`에는 registry 직접 권한이나 RLS policy가 없고 서버 DB 역할만 접근합니다.
