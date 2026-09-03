# Issue #45 여행 수정·삭제 API 개발 기록

## State

- 기준 브랜치: `origin/develop` `792d522fc948c6a8ecf637c3fcfc8c1bb1d63c32`
- 작업 브랜치: `feat/45-trip-update-delete`
- 구현 API: `PATCH /api/v1/trips/{tripId}`, `DELETE /api/v1/trips/{tripId}`
- 공개 계약은 `docs/contracts/domains/trips/contract.json`과 생성 OpenAPI를 기준으로 한다.
- 최신 develop의 일정 조회와 MCP private HTTP 변경을 병합하고 migration 순서를 035/036으로 고정했다.

## Evidence

### Red → Green → Refactor

- HTTP Red에서 PATCH/DELETE endpoint 부재를 확인한 뒤 JWT owner 은닉, UUID 검증, ETag/CAS,
  Problem Details와 no-op/invalidated 응답을 구현했다.
- PostgreSQL Red에서 aggregate cascade, concurrent PATCH/DELETE, schedule invalidation과 외부 fact 보존을
  실제 PostgreSQL transaction으로 검증했다.
- 독립 리뷰의 DELETE terminal-state 지적을 회귀 테스트로 전환했다. `completed`, `cancelled`, `failed`는
  삭제되며 `live` 또는 non-terminal generation run이 있을 때만 `TRIP_DELETE_CONFLICT`를 반환한다.
- DELETE framing 리뷰 지적에 대해 `Transfer-Encoding`, 비정상·중복 `Content-Length`, 선언 길이와 실제
  stream body 불일치를 service 진입 전에 모두 400으로 닫는 테스트와 구현을 추가했다.
- OpenAPI inventory를 최신 develop과 합쳐 mode 23으로 올리고 일정 조회와 여행 PATCH/DELETE를 함께 검증한다.
- 재리뷰에서 최신 develop의 `schedule_revision_runs`, `compute_run_inputs`가 canonical aggregate 목록에
  누락된 점을 MAJOR로 확인했다. exact aggregate validator와 실제 populated PostgreSQL cascade 회귀를
  추가해 계약과 DB의 삭제 경계를 일치시켰다.

### 검증 결과

- `JdbcTripMutationIntegrationTest`: 실제 PostgreSQL Green
- `TripControllerRedIntegrationTest`, `TripUpdateDeleteMigrationContractTest`: Green
- OpenAPI 생성 후 frontend-readiness mode 23: 23 operations Green
- 관련 Python 계약·migration·develop 통합 회귀 테스트: 61건 Green
- `spotlessCheck`, `git diff --check`, conflict marker 검사: Green
- 첫 보완 HEAD `5a9a93c`의 공식 전체 품질 게이트는 SUCCESS였으며, 독립 Reviewer가 신규 aggregate 계약
  누락 1건을 `CHANGES_REQUESTED`로 기록했다. 해당 보완의 새 전체 게이트와 재검토는 다음 HEAD에서 수행한다.

## Gaps

- Developer는 review state를 직접 승인으로 바꾸거나 자동 병합하지 않는다.
- 전체 품질 게이트 성공과 독립 Reviewer findings 0이 확인된 뒤에만 PR을 생성한다.
- 후속 #50 일정 항목 추가는 이 Issue의 persisted revision/strong ETag 계약이 develop에 병합된 뒤 재개한다.

## Risks

- Servlet container가 malformed request를 controller 이전에 거부할 수도 있지만 controller에 도달한 모든
  DELETE 요청도 raw framing과 stream을 다시 fail-closed 검사한다.
- 여행 삭제는 aggregate 자식만 cascade하며 외부 장소·import provenance와 user row는 보존해야 한다.
- migration 번호를 바꾸면 Compose, smoke test, 문서와 계약 테스트의 적용 순서도 함께 갱신해야 한다.
