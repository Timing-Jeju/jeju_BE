# #239 날짜별 활동 시간 저장 — 2026-09-11

#224 PR #251 병합 develop `93bcb7541037353d0e70e63fccdde54534137bcc`에서 `feat/239-day-activity-windows`를 분리했다. 이전 Phase A SHA를 복구했다고 가정하지 않고 현재 소스로 재현·구현했다.

## 구현

- 날짜 PATCH를 날짜 기준 reconcile로 바꿔 겹치는 Day ID·활동 시간을 보존한다. day_no UNIQUE 충돌을 피하도록 증가 시 역순, 감소 시 정순으로 갱신한다.
- Day 저장값을 JDBC/domain/required nullable 응답에 연결한다. 시각은 정확한 HH:mm이며 미입력 null을 기본 시각으로 대체하지 않는다.
- 전체 Day PUT, closed JSON·정확한 시간·중복/누락 검증, owner lock·revision·terminal·no-op, 멱등 snapshot 원자 저장을 구현했다.
- 활성/후보 일정의 참조 Day 변경은 재생성 충돌로 거부한다. 기존 일정 및 생성 worker는 변경하지 않는다.
- 새 migration021/슬롯059는 부분 null·초/소수초·24:00을 기존 값 수정 없이 감사 실패시키고 정상 데이터에 validated CHECK를 추가한다.
- 기존 BE 1~30일 지원은 유지하고 FE 1차 입력 1~5일과 구분했다. OpenAPI mode38은 기존 mode33의 실제 37개 경로에 PUT 하나를 추가하며 역사 모드를 유지한다.

## RED → GREEN 근거

- 날짜 PATCH의 기존 Day ID 손실: `/tmp/jeju239-day-preservation-red.log` → `/tmp/jeju239-day-preservation-green.log`.
- 저장 시각 누락 조회: `/tmp/jeju239-projection-red.log` → `/tmp/jeju239-projection-green.log` (15개 실패/오류/skip 0).
- 새 codec 미구현 compile RED, HH:mm 응답 누락 assertion RED, legacy 초 절삭 RED → 관련 단위/HTTP 검사 GREEN.
- 전체 교체 port 미구현 RED → JDBC 17개 GREEN. active 일정 변경이 503이던 RED를 409 재생성 충돌로 수정했고 동시 writer와 두 번째 Day DB 실패 rollback을 검증했다.
- migration 부재 RED → PG16·17에서 legacy 무변조 거부 및 null/정상 시간 CHECK 검증 GREEN. 첫 PG16 시도는 SSL 연결 설정 오류로 실패했으며 통과로 집계하지 않았다. 동일 격리 fixture 재실행 `/tmp/jeju239-migration-retry.log`는 두 버전 모두 PASS.
- 실제 JDBC+MVC+멱등 registry로 저장, 새 controller 재조회, 동일 body replay, 다른 body 충돌을 검증했다: `/tmp/jeju239-http-persistence.log` PASS. 이는 MockMvc 기반 HTTP 계층 검사이며 staging/native 앱 검증이 아니다.
- 공개 경로 없음 404 RED → 미지 GPS 필드 400 및 idempotency 무호출 GREEN.
- 계약/Python 회귀 중 추가 endpoint·migration·mode의 이전 고정 목록 실패를 확인하고 정확한 새 목록으로 갱신했다. validator를 생략하거나 historical 모드를 제거하지 않았다.
- OpenAPI 생성과 mode38 검증, TypeScript client 38개 생성 및 산출물 검증 PASS: `/tmp/jeju239-openapi-validation.log`, `/tmp/jeju239-client-generation.log`.

## 남은 완료 조건

최종 커밋의 정상 push hook 전체 quality gate/Docker, 같은 SHA 독립 Reviewer 승인과 공식 recorder, 정규 PR 및 CI가 남아 있다. 현재 소스 독립 검토에서 finding 0건을 받았지만 전체 승인으로 기록하지 않았다. Obsidian 일지와 함께 이 상태를 보존한다. live Supabase 적용, 운영 배포, 실제 FE/native 재시작 및 Notion/Figma 연결은 수행하지 않았다.

최종 집중 검증은 unitTest 1,381개(실패/오류 0, 기존 skip 9), architectureTest 48개(실패/오류/skip 0), JDBC mutation 24개(실패/오류/skip 0)로 통과했다. 새 1/5/30일 복원과 HTTP registry replay를 포함한다. 변경한 Python 테스트의 한글 목적 docstring은 AST 검사로 누락 0건을 확인했다. 최종 전체 gate는 각 수정 HEAD의 정상 push 훅에서 실행한다.

최종 조회 경쟁 검증에서 READ_COMMITTED가 이전 revision과 새 Day 시간을 섞는 RED를 재현했다(`/tmp/jeju239-read-snapshot-red.log`). `findOwned`의 신규 read-only transaction을 REPEATABLE_READ로 고쳐 root·Day를 같은 snapshot에서 읽는다. 기존 writer transaction 안에서는 owner root lock을 유지한다. 두 세션 barrier를 포함한 전체 JDBC mutation 25개가 실패/오류/skip 0으로 통과했다(`/tmp/jeju239-read-snapshot-green.log`).

첫 정상 push(`95bde05`)는 초기 Python 875개 중 #114 통합 테스트의 이전 CLI choices 문자열 한 곳이 남아 실패했다. 아직 Docker/전체 통과 근거가 아니다. 역사 모드는 유지하고 새 mode38을 정확한 choices 목록에 추가한 뒤 해당 회귀 5개를 재검증했다. 다음 SHA에서 정상 push 전체 gate를 다시 수행한다.

두 번째 정상 push(`3f2864a`)는 공통 Python 875개(3 skipped)와 Spring 단위·Slice를 통과했으나 전체 통합의 `CanonicalMigrationOrderIntegrationTest.schedule50Then51Upgrade`에서 fingerprint 불일치로 실패했다. fresh install은 manifest의021을 실행하고 upgrade 테스트의 수동 suffix는020에서 끝난 것이 원인이다. 실패 실행은 watchdog SIGINT로 정리했고 승인·push 성공으로 취급하지 않는다(`/tmp/jeju239-push-3f2864a.log`). 수동 upgrade 목록에021을 추가하여 동일 최종 schema/ACL을 비교하도록 수정하고 해당 클래스 전체를 재검증한다(`/tmp/jeju239-canonical-upgrade-green.log`).

목록 보완 후 `CanonicalMigrationOrderIntegrationTest` 전체 7개가 실패/오류/skip 0으로 통과했다(8분54초). fresh↔기존 develop upgrade, schedule50→51 upgrade의 schema/ACL 일치, PG16·17 및 rollback·security fingerprint를 함께 확인했다. 다음 커밋에서 전체 gate를 재실행한다.

## 과거 생성 receipt 호환 보완

추가 독립 리뷰에서 배포 전 POST 완료 receipt의 원본 body에는 새 Day 활동 시간 필드가 없지만 최신 POST successSchema는 이를 required로 요구하는 MAJOR가 확인됐다. `7e09fe5`의 전체 gate는 약 1시간 동안 새 테스트 실패 없이 진행 중이었으나 소스 보완이 필요해 공식 watchdog에 SIGINT로 종료했다. 이 실행은 PASS가 아니며, Docker 정리를 확인했다.

- `/tmp/jeju239-legacy-contract-red.log`: 과거 schema/POST union 누락 RED.
- POST에만 최신TripDetail/닫힌TripDetailLegacyV1 응답 분기를 명시했다. GET/PATCH/신규Day PUT required는 유지한다. registry 원본 응답, scope, TTL 및 DB 데이터를 변경하지 않았다.
- `/tmp/jeju239-legacy-contract-green.log`: 관련 계약34개 PASS.
- `/tmp/jeju239-legacy-python-all.log`: 전체Python877개 PASS, 기존skip3.
- `/tmp/jeju239-legacy-openapi.log`: OpenAPI3개 PASS.
- `/tmp/jeju239-legacy-jdbc.log`: 실제PostgreSQL JdbcTripMutation26개 PASS, skip/실패0. 배포 전body를 receipt로 commit한 뒤 새HTTP요청은 원본bytes/status/Location/ETag를 재전송하고 여행행수를 유지한다. 완료시각+24h 직전까지 원본replay, 정확한만료경계에서 기존takeover를 검증했다.

최신GET으로 복원해야 한다는 클라이언트 인계와 과거response허용범위를 명시했다. 새커밋전체gate, generatedclient, 최종Reviewer/PR/CI가 남아 있다.

기본 runtime OpenAPI는 ready canonical projection 테스트와 달리 기존 DTO ref를 사용하고 있어 `/tmp/jeju239-legacy-runtime-red.log`에서 union누락을 추가 재현했다. POST ApiDocs에 문서 전용TripCreateResponse/TripDetailLegacyV1Response/TripDayLegacyV1Response를 지정해 기존 snapshot shape를 고정했다. `/tmp/jeju239-legacy-runtime-green.log`에서 기본·ready OpenAPI와 architecture/export가 PASS했다. 재생성된 실제 artifact의 POST는 TripCreateResponse ref이며 components의oneOf2분기를 직접확인했다. `/tmp/jeju239-legacy-client-fixed.log`에서38operations 생성client를 검사한다. 이전 `/tmp/jeju239-legacy-client.log`는 생성 자체PASS여도 기본runtime분기를 검증하지 못한 산출물로 구분한다.

## 전체 gate 시간 제한 보완

bba4d8e의 정상 push는 통합 테스트 7200초 한도에서 stage-timeout으로 종료됐다. rootSuiteComplete=false이며 통과나 push 완료가 아니다. 종료 stack은 TransportEventMigrationIntegrationTest의 과거 schema 초기화 단계다. 기존 #224 통합 실행도 1시간 50분 50초를 사용했다. 모든 테스트와 실패 검출을 유지하면서 sh/PowerShell의 통합 단계 한도를 10800초로 맞췄다. 완료 후 프로세스 정리 제한 120초와 실패 시 진단·실패 반환은 유지한다. 새 HEAD에서 전체 정상 gate를 다시 실행한다.
