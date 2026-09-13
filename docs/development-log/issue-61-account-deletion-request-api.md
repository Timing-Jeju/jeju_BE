# #61 회원 탈퇴 요청·상태 조회 API 개발 일지

## 2026-09-13

- 기준: `origin/chore/261-predeploy-integration-batch` exact `9cf06e8a5eb61bd1ff7fa20d77e9f745cae7b1cc`
- 브랜치: `feat/61-account-deletion-request-api`
- 범위: 탈퇴 접수 202, opaque capability 상태 조회, recent Auth session port, versioned 256-bit AEAD, JDBC/migration, OpenAPI·프론트 계약
- 제외: #106 삭제 worker orchestration, live Supabase, Docker/Testcontainers, 전체 heavy gate

## TDD 증거

- Red: `./gradlew --no-daemon test --tests '*AccountDeletionServiceTest' --tests '*AesGcmVersionedKeyRingTest' --tests '*AccountDeletionMigrationContractTest'`
  - 신규 account-deletion model/service/security/repository 계약 클래스가 없어 `compileTestJava`가 실패했다.
- Green: account-deletion 관련 unit/MVC/OpenAPI/migration 테스트 18개가 성공했다.
- Refactor: #82 계약에 맞춰 UUID 초안을 canonical ULID로 바꾸고, token hash를 먼저 찾은 뒤 dummy constant-time 비교하여 검증 전 path ID 존재성을 숨겼다. service 패키지의 port/adapter를 분리하고 공개 controller/migration inventory를 갱신했다.
- Architecture: `./gradlew --no-daemon architectureTest` 48개 성공.
- Contract: profile-legal validator, REST readiness, canonical migration order, RLS security migration 테스트가 모두 성공했다.

탈퇴 최초 접수 transaction은 기존 `PushNotificationWithdrawalBoundary`를 호출해 해당 사용자의 push eligibility를 즉시 차단한다. `auth.sessions`는 DML 없이 service-role 전용 security-definer helper의 최소 boolean 결과만 읽는다. status token과 Auth subject는 용도별 AAD로 암호화하며 token 검증 hash 외 평문을 DB에 저장하지 않는다.
