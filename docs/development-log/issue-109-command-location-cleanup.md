# Issue #109 비동기 command input 위치 TTL 정리

## 범위 재구성

- 기준: #38 current-stack 후보 `ca3f0dd8a20dbe4430a8b57ed3f1faafaaf20879`
- 브랜치: `feat/109-location-retention-current-stack`
- 기존 누적 통합 branch와 worktree는 변경하지 않았다.
- 공개 HTTP API와 OpenAPI 변경은 없다.
- #109가 소유하는 command 위치 cleanup, worker 직전 admission, additive migration과 관련 계약만 포함한다.

## Red

테스트를 운영 코드보다 먼저 추가한 뒤 아래 실패를 확인했다.

```text
python3 -m unittest scripts.tests.test_command_location_cleanup
Ran 5 tests
FAILED (failures=8; 4개 test method에서 compose별 subtest 포함)
핵심 실패: `20260916000000` additive cleanup migration과 canonical redaction 함수가 없어 계약을 만족하지 못함

cd services/spring-api
./gradlew --no-daemon compileTestJava
BUILD FAILED
핵심 실패: CommandLocationCleanup* 타입, McpCommandLocationResolver,
CommandInputSnapshotRepository.findUsableLocation이 존재하지 않음(100 compile errors)
```

저장소 hook은 실패하는 테스트-only 커밋을 허용하지 않았다. hook을 우회하지 않고 Green 소스를 작업 트리에 둔 상태에서 테스트 파일만 먼저 커밋해 tests-first 이력을 보존했다.

## Green

최소 구현은 다음을 추가했다.

- due row를 `(location_expires_at, id)` 순서와 `FOR UPDATE SKIP LOCKED`로 최대 500건 선택하는 제한 DB 함수
- 위치 5필드 NULL과 `location_redacted_at`을 한 statement에서 기록하는 원자 transition
- `service_role` exact function 실행만 허용하는 최소 권한
- 5분 fixed delay, batch 500, cycle 최대 10 batch·1분·3회 retry의 기본 비활성 scheduler
- MCP argument 조립 직전 DB에서 expiry/redaction을 다시 검사하는 resolver

검증 결과:

```text
python3 -m unittest scripts.tests.test_command_location_cleanup \
  scripts.tests.test_command_input_snapshot \
  scripts.tests.test_push_notification_database
Ran 30 tests
OK

cd services/spring-api
./gradlew --no-daemon spotlessApply test \
  --tests 'com.timingjeju.api.application.commandinput.McpCommandLocationResolverTest' \
  --tests 'com.timingjeju.api.application.commandinput.cleanup.*' \
  --tests 'com.timingjeju.api.global.commandinput.cleanup.*' \
  --tests 'com.timingjeju.api.global.commandinput.JdbcCommandInputSnapshotRepositoryTest' \
  --tests 'com.timingjeju.api.architecture.ArchitectureTest'
BUILD SUCCESSFUL (선택한 8개 suite, 71 tests, 실패/오류/skip 0)
```

## Refactor와 검증 제한

- application port/orchestrator와 global scheduler/JDBC adapter 책임을 분리했다.
- failure code와 metric tag는 분류된 값만 사용하고 raw 위치·structured input·DB cause를 노출하지 않는다.
- 문서에 실제 MCP worker가 아직 없음을 명시하고 후속 worker의 필수 resolver 호출 경계를 고정했다.
- #38의 `20260915000000_jeju_timetable_route_scope.sql` 뒤에 `20260916000000_compute_run_input_location_cleanup.sql`을 배치하고 Docker init 순서를 `046 → 047 → 099 seed`로 유지했다.
- Refactor 후 Python 정적 계약과 관련 Java unit/Architecture 테스트를 재실행했다.
- 위임 범위에 따라 실제 DB 적용, Testcontainers, Docker, live Supabase, 전체 heavy quality gate는 실행하지 않았다.
- 따라서 관련 경량 테스트가 성공해도 Docker와 전체 품질 게이트 증거가 필요한 최종 `READY_FOR_REVIEW` 조건은 충족하지 않는다.

## Issue 댓글용 증거

Red는 Python 5개 중 4개 method의 8 failures(새 migration/함수 부재)와 Java 100 compile errors(새 cleanup 타입·repository method 부재)로 확인했다. Green 후 DB 비의존 Python 30개 및 관련 Java 71개 테스트가 성공했다. 현재 브랜치의 tests-first `18d4b64a`, 최소 구현 `9ece9ecc`, 문서/정리 `370c4021` 순으로 이력을 분리했다. 실제 DB·Testcontainers·Docker·live Supabase·전체 heavy gate는 현재 위임에서 제외되어 실행하지 않았다.
