# #106 회원 탈퇴 삭제 worker 개발 일지

## 2026-09-13 Phase A–C

- encrypted subject JIT resolve 뒤 Spring pending-account deny gate를 확인하고 Storage API, 앱 소유 데이터 정리·법적 보존 비식별화, Supabase Auth Admin 삭제, fenced subject clear 순으로 실행한다.
- lease heartbeat/expiry recovery/fencing, retryable·terminal 분류, max attempts, completed-step resume와 destructive step 전 cancellation을 단위 테스트로 고정했다.
- 기존 `SESSIONS_REVOKED` step은 resume 호환을 위해 이름을 유지하되 실제 전역 logout이 아니라 Spring deny gate 검증을 뜻한다. Supabase 공식 계약에는 subject-only Admin logout endpoint가 없고 Auth Admin user delete 전 발급 JWT는 exp까지 남을 수 있다.
- Auth adapter는 `DELETE /auth/v1/admin/users/{userId}`만 사용하며 2xx·404를 성공, 429·5xx·network를 retryable, 나머지 4xx를 terminal로 분류한다. service-role credential은 exception과 설정 문자열에서 redaction한다.
- profile image는 `storage.objects` SQL을 사용하지 않고 `profile-images/{subject}` 아래를 Storage list API로 유한 탐색한 뒤 최대 1000개씩 Storage delete API로 삭제한다.
- 앱 데이터 adapter는 owner/fence request row를 잠그는 짧은 transaction 안에서 여행·세션·소셜·저장·AI 데이터를 삭제하고 consent 및 운영 로그의 user link와 profile PII를 제거한다. profile row는 Auth 삭제 전까지 deny gate 식별을 위해 deleted 상태로 남긴다.
- worker는 기본 disabled이고 URL 또는 service-role placeholder가 빠진 상태에서 enabled로 설정하면 context가 fail-fast한다.

잔여 검증은 실제 Supabase/운영 DB를 사용하지 않는 현 단계 지시에 따라 live 연동, Docker, Testcontainers, 전체 품질 gate에서 제외했다.
