# 2026-09-13 Issue #258 저장 장소 DELETE 테스트 실효성 보강

## 범위

- 최신 `origin/develop` `ef8aee5964ecb0b2cbb265783f14537e5ad85e2b`에서 `test/258-saved-place-delete-lock-proof`를 분리했다.
- 운영 Controller·Service·Repository, 공개 API·계약, DB schema·migration은 변경하지 않는다.
- WebContract의 DELETE 호출 계수 positive-control과 PostgreSQL 16/17 DELETE/DELETE 실제 row-lock 관찰만 보강한다.

## Red 증거

- 독립 Reviewer가 검토한 보강 전 기준에서는 valid strong `If-Match` 요청에 새 positive-control을 적용하면 호출 계수가 `expected: 1`, `actual: 0`으로 실패했다. 이 실패는 repository stub의 DELETE 진입 계수 누락을 드러냈다.
- 병합 당시 동시 DELETE 테스트는 두 `CompletableFuture`의 최종 결과만 확인했다. winner transaction 유지 barrier와 실제 DB 대기 assertion이 없어서 두 작업이 순차 실행돼도 통과할 수 있었으며, row-lock 직렬화를 증명하지 못했다.
- 현재 `develop`에는 첫 번째 Reviewer 수정인 모든 DELETE 진입 계수 증가가 이미 반영되어 있어 positive-control은 회귀 고정 테스트로 추가한다. Red를 재현하려고 기존 계수 구현을 인위적으로 제거하지 않는다.

## Green·Refactor

- valid single strong `If-Match`는 repository 호출 1회와 기존 404를 함께 검증한다. 누락·weak·wildcard·다중 값·malformed와 중복 header의 400 및 호출 0회 검증은 유지한다.
- winner DELETE를 별도 transaction 안에서 실행한 뒤 latch로 commit을 보류한다. loser의 실제 PostgreSQL backend PID를 수집하고 `pg_blocking_pids(pid)`가 blocker를 반환할 때만 winner를 release한다.
- release 후 winner 성공, loser `SAVED_PLACE_NOT_FOUND`, 최종 row 0을 검증한다. PG17 subclass가 같은 테스트를 상속하므로 PostgreSQL 16과 17에서 동일 계약을 실행한다.

## 검증 상태

- #250의 전체 gate·Docker 성공과 residue 0 종료를 확인한 뒤 자원을 직렬화해 실행했다.
- `./gradlew sliceTest --tests 'com.timingjeju.api.domain.savedplaces.controller.SavedPlacesWebContractIntegrationTest'`: 12개, 실패·오류·skip 0, `BUILD SUCCESSFUL`.
- `./gradlew integrationTest --tests '*JdbcSavedPlaceRepositoryIntegrationTest.동시_DELETE는_한번만_성공하고_다른_요청에는_404를_반환한다' --tests '*JdbcSavedPlaceRepositoryPg17IntegrationTest.동시_DELETE는_한번만_성공하고_다른_요청에는_404를_반환한다'`: PostgreSQL 16·17 각 1개, 총 2개, 실패·오류·skip 0, `BUILD SUCCESSFUL`.
- 테스트 종료 후 생성된 PostgreSQL 16·17과 Ryuk 컨테이너가 모두 정리됐음을 확인했다. 보호 대상 `timing-jeju-live-demo-*`와 FaithLog 리소스는 건드리지 않았다.
- `git diff --check` 통과, 변경은 테스트 2파일과 이 개발 일지뿐이다. 전체 품질 gate와 Docker smoke, 독립 Reviewer 승인은 후속 절차이며 그 전에는 `READY_FOR_REVIEW`가 아니다.
