# #106 회원 탈퇴 삭제 worker 개발 일지

## 2026-09-13 Phase A–C

- encrypted subject JIT resolve 뒤 Spring pending-account deny gate를 확인하고 Storage API, 앱 소유 데이터 정리·법적 보존 비식별화, Supabase Auth Admin 삭제, fenced subject clear 순으로 실행한다.
- lease heartbeat/expiry recovery/fencing, retryable·terminal 분류, max attempts, completed-step resume와 destructive step 전 cancellation을 단위 테스트로 고정했다.
- 기존 `SESSIONS_REVOKED` step은 resume 호환을 위해 이름을 유지하되 실제 전역 logout이 아니라 Spring deny gate 검증을 뜻한다. Supabase 공식 계약에는 subject-only Admin logout endpoint가 없고 Auth Admin user delete 전 발급 JWT는 exp까지 남을 수 있다.
- Auth adapter는 `DELETE /auth/v1/admin/users/{userId}`만 사용하며 2xx·404를 성공, 429·5xx·network를 retryable, 나머지 4xx를 terminal로 분류한다. service-role credential은 exception과 설정 문자열에서 redaction한다.
- profile image는 `storage.objects` SQL을 사용하지 않고 `profile-images/{subject}` 아래를 Storage list API로 유한 탐색한 뒤 최대 1000개씩 Storage delete API로 삭제한다.
- 앱 데이터 adapter는 owner/fence request row를 잠그는 짧은 transaction 안에서 여행·세션·소셜·저장·AI 데이터를 삭제하고 consent 및 운영 로그의 user link와 profile PII를 제거한다. profile row는 Auth 삭제 전까지 deny gate 식별을 위해 deleted 상태로 남긴다.
- `APP_ACCOUNT_DELETION_ENABLED` 단일 flag가 탈퇴 API, pending 접근 차단, scheduler/worker를 함께 제어한다. 기본은 false이고 URL, service-role placeholder, worker ID 또는 암호화 key가 빠진 상태에서 true로 설정하면 context가 fail-fast한다.

잔여 검증은 실제 Supabase/운영 DB를 사용하지 않는 현 단계 지시에 따라 live 연동, Docker, Testcontainers, 전체 품질 gate에서 제외했다.

## 2026-09-15 #262 develop 통합 충돌 해결

- #262의 `629a1fb4`를 #263 작업 브랜치로 병합한다. 최신 develop의 #53 migration 00022–00031과 #242 RLS 00032를 먼저 적용하고, 계정 탈퇴 20260919 migration 다섯 개는 071–075 Docker init 슬롯으로 이동한다. 이미 공유 DB에 적용된 이력이 있다면 별도 reconciliation이 선행돼야 하며 여기서는 live DB를 변경하지 않는다.
- Red: `python3 -m unittest discover -s scripts/tests -p test_account_deletion_migration_order.py -v` → 충돌 상태의 `manifest.json`을 파싱할 때 `JSONDecodeError`가 발생했다.
- Green: 동일 테스트 1개와 `test_canonical_migration_order.py` 14개, `test_rest_contract_readiness.py` 54개 통과. Spring architecture inventory는 통합된 controller mapping 66개와 migration 73개를 검증하도록 갱신했다.
- 품질 게이트, Docker, 별도 Reviewer 승인 결과는 검증 완료 시 별도 기록한다.
