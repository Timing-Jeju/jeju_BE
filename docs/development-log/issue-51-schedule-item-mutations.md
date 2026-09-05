# Issue #51 일정 항목 변경 API 개발 기록

## 범위와 기준

- 기준: `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`
- 브랜치: `feat/51-schedule-item-mutations`
- endpoint: 일정 항목 PATCH/DELETE, 전체 순서 PUT, Day 이동 POST
- 기준 계약: `docs/contracts/domains/schedules/contract.json`과 Issue #88

## Red → Green → Refactor

최초 RED에서는 유효한 `PATCH /api/v1/trips/{tripId}/schedule-items/{itemId}`가 새
`user_edit` version을 활성화하고 `200`을 반환해야 한다고 Controller 통합 테스트로 먼저
고정했다. endpoint가 없는 상태에서 기대 `200` 대신 실제 `404`로 실패했다.

```text
./gradlew test --tests 'com.timingjeju.api.domain.schedule.controller.ScheduleControllerIntegrationTest.PATCH_schedule_item은_새_user_edit_version을_활성화하고_200을_반환한다'
Status expected:<200> but was:<404>
```

Green에서는 PATCH/DELETE/reorder/move를 공통 idempotency·강한 ETag·expected active selector에
연결했다. 모든 변경은 여행 row를 잠근 뒤 기존 active의 item을 새 UUID로 복제하고 survivor
progress를 새 item에 연결한다. completed target, 외부 item/Day, 잘못된 permutation과 제주
현지 날짜·시간창 위반은 canonical Problem Details로 닫는다.

Refactor에서는 네 작업의 version 생성·leg 재구성·seal·pointer CAS를 한 transaction 경계로
통합했다. 의미가 같은 기존 leg는 provider 원본이나 geometry를 복제하지 않고 정규화된 이동
근거와 duration만 재사용하며, 새 from-item 종료시각에서 departure/arrival을 다시 계산한다.
snapshot 또는 보수적 PostGIS 도보 근거로도 다음 item 시간창을 만족하지 못하면
`SCHEDULE_LEG_INCOMPLETE`로 전체 rollback한다. 요청 시 외부 API·MCP·LLM 호출은 없다.

## 검증

- Controller: 네 endpoint, duplicate/unknown JSON, semantic JSON hash, 1 MiB와 HTTP framing 검증 성공
- 실제 PostgreSQL: PATCH memo null/progress, DELETE first/middle/last, exact permutation,
  cross-Day move, completed guard, 두 동시 device의 단일 commit, 실패 rollback 성공
- schedule canonical validator 및 관련 Python 38 tests 성공
- OpenAPI frontend-readiness mode 28: `28 operations` 성공
- `./gradlew --no-daemon clean check`: 13분 56초, 성공
  - 일반 test 1,737건과 JaCoCo 성공
  - 환경 조건부 live test만 skip
- `./scripts/docker-smoke-test.sh`: 성공
  - API health, clean/legacy migration, 실제 2-session 동시성, 음수 제약,
    schema/PostGIS fixture 검증 성공
  - 종료 후 `timing-jeju-smoke` container/network/volume 잔존 없음

## 운영 경계

- 공개 JSON schema는 `docs/contracts/domains/schedules/contract.json`을 그대로 OpenAPI에 투영한다.
- semantic JSON property 순서와 공백은 idempotency hash에 영향을 주지 않으며 DELETE query는
  canonical selector payload로 hash한다.
- `Transfer-Encoding`, 중복·비정상·실제 body와 불일치하는 `Content-Length`는 operation 전에
  `400 INVALID_REQUEST`로 거부한다.
- 기존 Supabase schema의 schedule version/item/leg/progress와 deferred constraint로 충분하므로
  이 Issue는 새 migration이나 환경변수를 추가하지 않는다.
