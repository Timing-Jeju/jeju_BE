# Issue #109 비동기 command input 위치 TTL 정리

## 범위 재구성

- 기준: `origin/develop@4a82a6b`
- 브랜치: `chore/109-command-location-cleanup-pure`
- 기존 누적 통합 branch와 worktree는 변경하지 않았다.
- 공개 HTTP API와 OpenAPI 변경은 없다.
- #109가 소유하는 command 위치 cleanup, worker 직전 admission, additive migration과 관련 계약만 포함한다.

## Red

테스트를 운영 코드보다 먼저 추가한 뒤 아래 실패를 확인했다.

```text
python3 -m unittest scripts.tests.test_command_location_cleanup
FAILED (failures=9)
핵심 실패: additive cleanup migration과 canonical redaction 함수가 없어 계약을 만족하지 못함

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
python3 -m unittest scripts.tests.test_command_location_cleanup scripts.tests.test_command_input_snapshot
Ran 23 tests in 4.817s
OK

cd services/spring-api
./gradlew --no-daemon spotlessApply test \
  --tests 'com.timingjeju.api.application.commandinput.McpCommandLocationResolverTest' \
  --tests 'com.timingjeju.api.application.commandinput.cleanup.*' \
  --tests 'com.timingjeju.api.global.commandinput.cleanup.*' \
  --tests 'com.timingjeju.api.global.commandinput.JdbcCommandInputSnapshotRepositoryTest' \
  --tests 'com.timingjeju.api.architecture.ArchitectureTest'
BUILD SUCCESSFUL
```

## Refactor와 검증 제한

- application port/orchestrator와 global scheduler/JDBC adapter 책임을 분리했다.
- failure code와 metric tag는 분류된 값만 사용하고 raw 위치·structured input·DB cause를 노출하지 않는다.
- 문서에 실제 MCP worker가 아직 없음을 명시하고 후속 worker의 필수 resolver 호출 경계를 고정했다.
- Refactor 후 Python 정적 계약 29개와 관련 Java unit/Architecture 테스트를 `--rerun-tasks`로 재실행해 `BUILD SUCCESSFUL in 1m 10s`를 확인했다.
- 위임 범위에 따라 실제 DB 적용, Testcontainers, Docker, live Supabase, 전체 heavy quality gate는 실행하지 않았다.
- 따라서 관련 경량 테스트가 성공해도 Docker와 전체 품질 게이트 증거가 필요한 최종 `READY_FOR_REVIEW` 조건은 충족하지 않는다.

## Issue 댓글용 증거

Red는 Python 9 failures(새 migration/함수 부재)와 Java 100 compile errors(새 cleanup 타입·repository method 부재)로 확인했다. Green과 Refactor 후 DB 비의존 Python 23개 및 관련 Java unit/Architecture 테스트가 성공했다. tests-first `3024659`, 최소 구현 `52590d9`, 문서/정리 커밋 순으로 이력을 분리했다. 실제 DB·Testcontainers·Docker·live Supabase·전체 heavy gate는 현재 위임에서 제외되어 실행하지 않았다.
