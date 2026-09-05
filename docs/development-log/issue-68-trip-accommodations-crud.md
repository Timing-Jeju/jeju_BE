# 2026-09-01 Issue #68 복수 숙소 CRUD

## State

- `feat/68-trip-accommodations-crud`에서 #45 strong trip ETag를 기준으로 구현했다.
- POST/PATCH/DELETE runtime, migration, Swagger/OpenAPI 27-operation projection을 연결했다.
- FE 소스는 수정하지 않았고 TypeScript client 생성 command와 handoff만 제공했다.

## Evidence

- 먼저 application, JDBC, controller, OpenAPI 계약 테스트를 작성해 실패 조건을 고정했다.
- 실제 PostgreSQL에서 날짜 gap/overlap, sequence compaction, no-op, identity 전환, active schedule 무효화·삭제 차단을 확인했다.
- 같은 멱등 키의 동시 POST는 한 row와 원응답 replay만 남기고, 같은 revision의 동시 PATCH는 하나만 성공한다.
- invalid legacy time row가 있으면 migration이 데이터를 보정·삭제하지 않고 중단하며 원본을 보존한다.
- canonical accommodation fixture와 validator의 ETag를 #45 exact strong 형식으로 정렬했다.
- 생성 OpenAPI의 27개 operation frontend-readiness 검사와 숙소 typed SDK 3개 생성 검사를 통과했다.

## 2026-09-04 Reviewer MAJOR 보완

- frontend generator와 handoff 문서를 최신 공개 계약인 mode 27로 정렬했다.
- 네트워크나 npm codegen 없이 synthetic OpenAPI와 `index.ts`를 대조하는 27-operation artifact 검증을 추가했다.
- 먼저 기존 generator의 `--mode 26`과 artifact 검증 부재를 검출하는 Python 계약 테스트를 Red로 확인한 뒤 Green으로 전환했다.
- `tour_places.name`이 100자를 넘거나 blank, control 포함, 앞뒤 ASCII 공백 포함, NFC 비정규화이면 저장·조회 projection에서 `ACCOMMODATION_DATA_UNAVAILABLE`로 차단한다.
- 먼저 100자 성공 및 101자/blank/control/NFC 비정규화 실패를 고정한 DB-free unit test를 Red로 확인한 뒤 Green으로 전환했다.
- 오류에는 원문 장소명을 포함하지 않아 PII 및 존재 정보를 노출하지 않는다.

## Gaps and risks

- 독립 Reviewer 승인, PR, `develop` 병합은 Developer 범위 밖이며 수행하지 않았다.
- FE adapter와 실제 axios/polling 연결은 별도 변경이다.
- TypeScript client 생성은 npm registry 접근이 필요하며 package와 TypeScript version을 script에서 고정했다.
- MCP planner의 숙소 boundary publication은 후속 planner-conditions/generation 이슈에서 연결한다.

## 2026-09-04 canonical trip mutation coordinator 재통합

- SOURCE_APPROVED를 철회하고 `/private/tmp/timing-jeju-pr205-fixes` HEAD `4476932`의 #50fix canonical coordinator 9개 파일을 SHA-256 exact provenance로 재통합했다.
- 먼저 accommodation store가 공통 coordinator/plan을 사용하지 않고 자체 trip root lock, revision CAS, `MutationRoot`를 보유한다는 architecture Red 2건을 확인했다.
- create/patch/delete를 `TripAggregateMutationCoordinator.execute` 안의 `maintain`/`invalidate`/`noChange` plan으로 전환하고 자체 root lock/CAS/schedule supersede를 제거했다.
- POST replay는 common stale/terminal 검사보다 먼저 반환해야 하므로 accommodation idempotency advisory lock과 marker `FOR UPDATE`만 store에 유지했다.
- DB-free 테스트로 terminal, stale, no-op, active/no-active invalidation, before-root→CAS→after-effect rollback 경계, replay ordering과 common `TripException`의 canonical accommodation 오류 번역을 검증했다.
- 최종 landing은 **#50fix-first → #68**이며, #50fix 반영 뒤 #68의 coordinator 9개 duplicate는 drop하고 accommodation 주입 diff만 유지한다.

## 2026-09-04 canonical sequence payload 보완

- DB compaction은 canonical sequence를 다시 쓰지만 create/patch plan payload가 pre-compaction sequence를 반환하는 문제를 발견했다.
- 먼저 두 번째 숙소 append 응답/snapshot과 날짜 변경으로 재정렬되는 PATCH가 각각 저장될 canonical sequence `2` 대신 `1`을 반환하는 DB-free Red 2건을 확인했다.
- 정렬·검증된 plan 목록에서 대상 accommodation의 index+1을 계산해 response payload에 사용하고, replay snapshot도 그 canonical payload를 그대로 보존하게 했다.
- #50fix canonical coordinator 9개 파일, coordinator effect 순서와 trip root/CAS 경계는 변경하지 않았다.
