# #225 계획 anchor provenance 구현 일지

## 2026-09-09 좌표 파생 hash 금지 정책 후속 교정

- 원격 develop `be3b13be81c44f01b7d13ed2fd537cc9a8f6b4fe`, 기존 #225 `c699bd121e1bcd7d78fb31c60caed002a0c60e74`를 읽기 전용 검증했다. 해당 develop을 포함한 기존 #225 HEAD에서 별도 worktree `/private/tmp/timing-jeju-issue225-hash-policy`, 로컬 `fix/225-planned-route-hash-policy`를 생성했다. root dirty worktree와 다른 Issue worktree는 수정하지 않았다.
- Issue의 2026-09-08 추가 검토 댓글은 공개 좌표 EWKT hash를 허용하는 과거 판단이다. 최신 사용자 승인 정책은 Spring 서버의 현재·간접 위치 수신·저장과 coordinate-derived hash를 금지하므로, PM 지시로 이 후속 교정이 우선한다. 이 정책 차이를 숨기거나 과거 댓글을 최종 승인으로 사용하지 않는다. 원격 push·Issue 댓글·실제 Supabase 적용·배포는 하지 않았다.
- 테스트 목록: 최종 함수의 정확한 7필드·좌표 부재; 각 trip/version/contract/kind/ID 및 endpoint 순서 민감성; 좌표와 owner/item/source/mode/departure/provider/operation 불변성; 독립 Java UTF-8 길이-prefix SHA-256; legacy 015/016 rehash; hash 외 행·ACL·trigger 보존; fresh/upgrade schema fingerprint; 실패 후 rollback 및 reapply; 축소 identity 중복 fail-closed; migration inventory.
- RED: 운영 SQL 변경 전 `python3 -m unittest scripts.tests.test_planned_route_hash_policy.PlannedRouteHashPolicyTest.test_final_hash_uses_exactly_seven_public_identity_fields_without_coordinates -v`에서 1건 실패(exit 1). 핵심: `final route hash still retains coordinate-derived input`, 최종 함수에 `ST_AsEWKT(origin_location/destination_location)` 존재. registry 기대값 추가 후 관련 21건 중 7건이 055 mount/manifest/문서 미등록으로 실패했다.
- GREEN: 신규 `20260918000017_planned_route_request_hash_policy.sql`은 015/016을 수정하지 않고 7필드 함수만 교체한다. `ACCESS EXCLUSIVE` lock·단일 transaction 안에서 named provenance guard만 잠시 중지하고 `request_hash`만 backfill한 뒤 복원한다. `CREATE OR REPLACE`로 함수 OID·owner·ACL을 유지하고 security invoker/empty search_path를 명시한다. FK/CHECK/UNIQUE/RLS/source-lineage를 해제하지 않는다.
- 동일 trip/version/양끝 kind·ID이면서 다른 출발시각/항목/교통수단의 legacy 행은 새 identity에서 충돌할 수 있다. 기존 UNIQUE를 완화하거나 행·leg를 병합/삭제하지 않고, 원문 hash 없는 `23514` 감사 오류로 migration 전체를 rollback한다. 운영 적용 전 별도 중복 감사·결정이 필요하며 자동 복구는 이 변경 범위가 아니다.
- `python3 -m unittest scripts.tests.test_planned_route_hash_policy scripts.tests.test_canonical_migration_order scripts.tests.test_push_notification_database -v`: 24건 PASS. 017 SHA-256 `a52ecfae11f9cc24fa47ca6a10061cf3c4dffc0ee4a493b797ac68af179effd2`, 016 dependency/init055, Compose 3개, Unix smoke·Java/static inventory·아키텍처 문서를 정렬했다. PowerShell은 기존 manifest 소비를 유지한다. 015/016 SHA는 불변이다.
- PG16/17 dedicated migration 통합 테스트와 Spring 저장 경로의 정확한 digest assertion을 추가했다. #210의 공유 Docker 사용과 충돌하지 않도록 PM 승인 전 PostgreSQL 통합 테스트·전체 gate·Docker smoke는 대기한다. 현재 증거만으로 `READY_FOR_REVIEW`를 선언하지 않는다.
- 비-Docker 후속 검증: `./gradlew spotlessApply compileTestJava architectureTest --tests '*MobilityOwnershipContractTest'` PASS(1m11s); `./gradlew spotlessCheck unitTest architectureTest` PASS(1m30s), XML 합계 1,399건·실패/오류 0·플랫폼 조건 skip 9건. backend layout/DB hardening 52건 PASS. Obsidian `20_WIKI/2026-09-09-issue-225-planned-route-hash-policy-log.md` 작성 완료. 이 기록은 PostgreSQL 통합·전체 gate·Docker 성공을 뜻하지 않는다.
- PM의 공유 Docker 사용 승인 후 `./gradlew integrationTest --tests '*PlannedRouteHashPolicyMigrationIntegrationTest' --tests '*JdbcScheduleMutationStoreIntegrationTest'`를 실행했다. 1차 89건 중 87건 PASS, 2건은 테스트 catalog 조회의 `text || "char"` 모호성으로 실패했다. 테스트의 `tgname`/`tgenabled`를 `::text`로 명시했고, 운영 SQL은 변경하지 않았다.
- 최종 `./gradlew spotlessApply integrationTest --tests '*PlannedRouteHashPolicyMigrationIntegrationTest' --tests '*JdbcScheduleMutationStoreIntegrationTest'` PASS(4m41s): PG16/17 dedicated 4건 + Spring mutation 85건, failure/error/skip 0. legacy 함수는 좌표만 바꿔도 hash가 바뀌는 것을 실제 DB에서 먼저 확인했고, 017 적용 후 정확한 7필드 Java SHA-256 oracle, 좌표/제외 필드 불변, 각 필드·endpoint 순서 민감성을 검증했다. 공개 원천 좌표 변경 이후에도 hash는 동일하지만 stale geometry의 봉인은 별도 guard가 거부했다.
- PG16/17에서 legacy 015/016의 hash-only backfill과 non-hash 행·함수 OID/owner/ACL·trigger 보존, migration transaction에 주입한 실패의 schema/data/hash rollback, fresh/upgrade 전체 schema·ACL fingerprint 일치, reapply 무변경, 축소 identity 중복의 값 비반사 `23514` 전체 rollback을 확인했다. 015/016 및 017 SHA-256은 위 기록과 동일하다.
- 종료 후 container/network/volume 목록이 실행 전과 동일했다. 신규 Testcontainers/Ryuk/anonymous volume 잔여 0, 보호 live-demo 3개 + FaithLog 3개 컨테이너·custom network 3개·persistent volume 3개 보존. 기존 live-demo API unhealthy, viewer restarting, postgres exited 상태는 수정하지 않았다. 공식 전체 quality gate·Docker smoke는 PM 별도 조정 대상으로 아직 미실행이며 READY_FOR_REVIEW가 아니다.

## 첫 구현: 임의 item facts 저장 차단

- 기준 develop: f7fc751. 선행 #221/#222 병합 후 최신 base에서 최종 검증한다.
- 실제 PostgreSQL에서 직접 위치, nested 현재 위치, 파생 지역/nearest place/grid/geohash 및 배열/null JSON이 저장되는 RED 8건을 확인했다. 로그: `/tmp/jeju-225-facts-red.log`.
- `trip_items.facts`에는 승인된 자유 형식 필드가 없으므로 현재 closed shape를 빈 객체 `{}`로 고정했다. 향후 evidence는 승인된 버전별 정규화 projection 계약으로 추가해야 한다. `trip_legs.facts`의 기존 leg derivation marker는 이 변경 대상이 아니다.
- private SECURITY INVOKER trigger가 INSERT/UPDATE를 sanitized 23514로 거부해 실패 row에 원문을 반사하지 않는다. 추가 CHECK가 구조를 고정한다. PUBLIC/anon/authenticated/service_role의 직접 함수 EXECUTE는 회수했다.
- 기존 non-empty facts가 있으면 table lock 안에서 감사하고 migration 전체를 중단한다. 자동 삭제·자동 매핑·현재 위치 판정을 하지 않는다.
- Supabase CLI가 PATH에 없어 저장소 선행 작업과 같은 pinned `npx supabase@2.116.0 migration new`를 사용했다. CLI 생성 timestamp가 기존 예약 suffix보다 앞서므로 append-only 순서에 맞춰 `20260918000013` / init `051`로 이름을 정렬했다.
- manifest SHA256, Compose 3개 및 Unix smoke init/upgrade 목록을 등록했다. PowerShell은 기존 manifest 소비 경로를 유지한다.
- GREEN: `/tmp/jeju-225-facts-green.log` 8건; `/tmp/jeju-225-facts-guards.log` 12건(정상 service writer, UPDATE 거부, ACL 포함) 성공. 원문 marker가 SQLException에 반사되지 않는지 검사했다.
- migration manifest RED/GREEN: `/tmp/jeju-225-manifest-red.log`, `/tmp/jeju-225-manifest-green.log` 13건 성공. 기존 #215 검사는 suffix 마지막이 아니라 해당 역사적 파일명을 직접 참조하도록 정정했다.

## 남은 구현과 검증

- JDBC 및 sealing resolver 구현은 아래 단계에서 완료했다. route snapshot 연결까지 포함한 전체 검증은 남아 있다.
- route snapshot의 origin/destination exact-one 참조, owner/version/item lineage, 공개 좌표 및 request hash provenance.
- legacy route의 증명 가능한 backfill과 불명확/활성 자료 fail-closed 경계. 좌표 일치만으로 provenance를 발명하지 않는다.
- 전체 DB fresh/upgrade/PG16·17/concurrency, schema/ACL fingerprint, OpenAPI, 전체 품질 gate 및 독립 리뷰.
- 첫 facts migration은 후속 변경에서 수정하지 않고 필요한 보강은 새 forward migration으로 추가한다. #225 전체 완료나 PR 준비 상태가 아니다.

## Legacy rollback 보강

- `/tmp/jeju-225-legacy-rollback.log`: PostgreSQL 16·17에서 non-empty legacy facts가 있으면 새 migration이 실패하고 원래 JSON과 전체 schema/RLS/ACL fingerprint가 동일하게 유지되는 것을 검증했다(2건 PASS).
- 기존 자료 marker 및 좌표가 migration 실패 예외에 반사되지 않는지도 확인했다. 테스트의 첫 compile 오류(AssertJ varargs 미지원)는 개별 assertion으로 정정했다.

## 공개 계획 anchor resolver

- forward migration `20260918000014` / init `052`에서 `timing_jeju_planner_private`를 만들었다. 기존 owner helper schema의 service_role USAGE 회수는 유지하고, 새 resolver에만 service_role USAGE/EXECUTE를 부여했다. SECURITY INVOKER와 빈 search_path를 사용한다.
- 공개 place/stop, 같은 여행의 숙소 및 입출도 이벤트를 해석한다. item은 trip/version/item 계보와 중복 place FK의 일치 여부를 검사한다. 삭제·stale 공개 장소를 계산에 재사용하지 않는다. 공개 FK가 없는 사용자 숙소 이름을 좌표로 자동 매핑하지 않는다.
- JDBC 도보 fallback과 sealing에서 `facts.location` 해석을 제거했다. 기존 시간·연속 순서·겹침·leg 및 title-only 예외 검사는 유지했다.
- RED `/tmp/jeju-225-anchor-red.log` 8건: resolver 미구현. GREEN `/tmp/jeju-225-anchor-green.log` 8건. 추가 ACL/중복 FK/삭제 anchor 경계는 `/tmp/jeju-225-anchor-boundaries-green.log` 11건 PASS. 첫 추가 fixture는 placeId를 비우면서 제목도 비워 기존 CHECK에서 거부되어 공개 숙소 제목을 명시했다.
- 회귀 RED `/tmp/jeju-225-schedule-regression.log` 2건: 필수 참조 검사가 경로 해석보다 늦어 오류 코드가 달라졌다. 필수 참조를 leg 파생 전에 검사하도록 수정해 `/tmp/jeju-225-schedule-regression-green.log` 전체 55건 PASS.
- schema/RLS/ACL fingerprint에 새 private schema를 포함하고 Java/Python canonical suffix 및 init/upgrade 목록을 등록했다. registry 13건 PASS(`/tmp/jeju-225-resolver-registry-final.log`).
- 독립 Reviewer가 d23971c와 resolver/JDBC bounded diff를 검토해 필수 finding 0건을 보고했다. 전체 #225 승인이나 recorder 실행은 아니다.

## Route provenance 구현 중 — 아직 최종 커밋/승인 아님

- resolver14 새 설치/upgrade schema·ACL 비교 2건 PASS(`/tmp/jeju-225-resolver-upgrade.log`).
- Supabase CLI로 생성 후 suffix15/init053로 등록한 route migration은 아직 수정 중이다. owner/trip/version/item, 양끝 typed public anchor 및 source place/stop FK를 고정하고 좌표는 resolver 결과와 대조한다. request hash에 계약 버전·계보·공개 좌표·이동수단·출발시각·source를 포함한다.
- RED `/tmp/jeju-225-route-red.log`: 출처 없는 좌표 snapshot이 저장됐고 새 계보 컬럼이 없었다. 첫 GREEN 2건 후 wrong owner/version/item/kind/anchor/coordinates/hash/contract와 불변성 경계를 보강했다. self-loop RED→GREEN 포함13건 PASS(`/tmp/jeju-225-route-boundaries-green.log`).
- raw payload와 위치성 arbitrary summary 및 TMAP 저장이 허용되는 RED3건(`/tmp/jeju-225-route-storage-red.log`)을 확인했다. raw payload는 빈 객체, summary는 명시 필드/타입만 허용하며 #216 승인 전 fixture 외 provider 저장은 기본 거부한다. 이 guard를 provider 저장 승인 근거로 해석하지 않는다.
- 독립 Reviewer가 source_snapshot_id ON DELETE SET NULL과 immutable UPDATE 충돌을 발견했다. 실제 PG DELETE RED(`/tmp/jeju-225-route-retention-red.log`) 후 원천 FK 해제만 허용해 hash/version/import ledger 보존을 검증했다. BEFORE trigger의 generated FK 출력은 비교에서 제외하되 실제 kind/id 입력은 비교한다. Reviewer가 해결을 확인했다. storage/retention 포함17건 PASS(`/tmp/jeju-225-route-storage-retention-green.log`).
- 다른 버전 leg 연결 및 수동 복사의 snapshot ID 재사용 RED2건(`/tmp/jeju-225-route-leg-red.log`) 뒤 정확한 leg lineage guard와 동일 출발시각·유효기간 내 별도 버전 snapshot 복사를 구현했다. 관련19건 PASS(`/tmp/jeju-225-route-leg-green.log`).
- 다른 버전이지만 좌표가 같은 snapshot을 전역 cache에서 선택해 mutation이 실패하는 RED(`/tmp/jeju-225-route-cache-red.log`)를 확인했다. JDBC 후보 조회에 trip/version/from/to를 추가했다. 일정 mutation 전체 회귀 PASS(`/tmp/jeju-225-route-schedule-full-green.log`).
- legacy는 좌표 일치만으로 보존하지 않는다. 단일 leg FK, 공개 planned anchor, 시간/수치/소유 계보 및 승인된 합성 source가 일치하는 경우만 새 hash로 전환하는 경계를 구현 중이다. 증명 가능한 legacy RED2건(`/tmp/jeju-225-route-legacy-red.log`); GREEN 재검증 진행 중.
- 남은 작업: legacy ambiguous/unreferenced/active rollback·동시성, 현재/역사적 seed 및 DB smoke 전환 정렬, 전체 schema/ACL/PG16·17 gate, 최신 base와 공식 독립 승인. 이 단계는 #225 전체 완료가 아니다.

## Legacy·fixture 후속 검증

- `/tmp/jeju-225-route-legacy-green.log`: PostgreSQL16·17에서 단일 leg·공개 anchor·owner/version/item·시각·수치가 일치하는 합성 legacy snapshot 보존과 새 계약 hash 전환 2건 PASS. Audit 임시 테이블에는 ID만 기록한다. 현재 승인되지 않은 실제 provider 결과와 모호한 자료는 보존 대상으로 인정하지 않는다.
- `/tmp/jeju-225-route-legacy-boundaries.log`는 검증 성공 근거가 아니다. 동일 worktree Gradle 검사를 겹쳐 실행해 결과 binary 파일 NoSuchFileException이 발생했다. 별도 seed 검사와의 동시 실행을 종료하고 이후 직렬 재검증한다.
- seed RED(`/tmp/jeju-225-route-seed-red.log`): 과거 schema seed의 중복 item facts가 migration13에 거부되고, 현재 schema에서는 old route shape가 거부됐다. 합성 seed의 item facts를 비우고 별도 canonical 필드로 보존된 의미를 사용한다. route 시각/수치를 leg와 정렬하고 closed summary/빈 raw로 바꿨다.
- 현재 schema는 명시적 fixture item ID mapping으로 typed anchor를 저장하고, 과거 schema는 같은 합성 값의 old shape를 사용해 후속 migration을 검증한다. 장소명/좌표 자동 매핑이나 실제 사용자 데이터 정리는 수행하지 않았다. seed GREEN 재검증 진행 중.

### 2026-09-08 추가 경계 검증 (미커밋 migration 15)

- 합성 seed의 경로 좌표를 반올림된 상수 대신 명시적 canonical place ID 조회로 교체했다. 비교 조건 `ST_Equals`는 완화하지 않았다. 현재 PG16/17 seed와 과거 title-only upgrade는 `/tmp/jeju-225-route-seed-canonical-green.log`에서 통과했다.
- 독립 리뷰의 세 finding을 테스트로 재현했다. 장소 변경 후 seal이 잘못 성공하고, 양끝 정류장 snapshot의 메모 편집 복사가 실패했다 (`/tmp/jeju-225-route-review-red.log`). 정류장 변경 테스트는 처음 active 수정 금지 규칙에 걸렸으며 draft fixture를 보완한 뒤 `/tmp/jeju-225-route-stop-draft-red.log`에서 변경이 잘못 허용됨을 확인했다. 중간 `sealed_at` fixture 오류는 성공 증거로 쓰지 않는다.
- 새 leg를 snapshot 없이 삽입한 뒤 같은 transaction에서 snapshot을 복사·연결하도록 변경했다. leg trigger는 stop ID를 검사하고 seal 함수는 현재 planned anchor의 canonical source ID와 공개 geometry를 재검증한다. 세 회귀 테스트는 `/tmp/jeju-225-route-review-green.log`에서 통과했다.
- 역사 v1 fixture는 provenance가 없는 route를 의도하므로 provider/hash를 바꾸거나 삭제하지 않았다. Docker smoke는 052까지 역사 계약을 검증하고, 053의 지정된 23514 실패 후 새 연결에서 schema/RLS/ACL 및 route/leg digest 보존을 확인하도록 수정했다. 실제 Docker 실행은 아직 미완료다.
- registry 13개와 shell 문법 검사는 통과했다. 전체 mutation + PG16/17 route migration/rollback/seed + historical title-only 회귀는 `/tmp/jeju-225-route-final-regression.log`에서 직렬 실행 중이다. 이번 HEAD의 공식 품질 게이트·공식 승인·PR은 아직 없다.

- Docker preflight RED(`/tmp/jeju-225-route-docker-preflight.log`): 역사 v1 선택 migration loop가 기존 023 public place tombstone을 생략하여 052 resolver 작성 시 컬럼이 없었다. 023을 선행 실행하도록 smoke만 수정했다. 커밋된 014 SQL은 변경하지 않았다. `/tmp/jeju-225-route-docker-history-green.log`에서 재검증 중이다.
- 병렬 작업 주의: 다른 worktree여도 공식 watchdog은 Docker의 전역 새 자원을 검사한다. #222 integration suite 자체는 통과했지만 #225의 동시에 실행 중인 container가 잔여 자원으로 감지됐다. 앞으로 공식 quality gate와 다른 Docker 작업을 겹치지 않는다.

- 직렬 통합 회귀 `/tmp/jeju-225-route-final-regression.log` PASS(12m15s): mutation 80, PG16/17 route migration·rollback·seed 10, historical title-only upgrade 1, 총 91건 실패·skip 0.
- `/tmp/jeju-225-route-expiry-rollback-green.log` PASS: 만료 등호·관측 전·출발 변경 거부 3건 및 snapshot 복사 실패 시 신규 leg/version 전체 rollback 1건.
- 두 번째 Docker 실행에서는 역사 v1 및 053 fail-closed rollback이 통과했고, 별도 concurrency fixture loop의 같은 023 누락을 검출했다. 두 resolver 실행 loop의 선행 조건을 검사하는 단위 RED(`/tmp/jeju-225-smoke-dependency-red.log`) 후 두 loop를 정렬해 GREEN(`/tmp/jeju-225-smoke-dependency-green.log`, 14건)을 확인했다. 전체 Docker 재실행은 `/tmp/jeju-225-route-docker-final.log`에서 진행 중이다.

## 016 후속 참조 무결성 보강

- migration015와 핵심 경로 구현은 `0d656c0ad92e118447306d108e4ae5959ff2dc1d`로 커밋·푸시했다. 이후 015는 수정하지 않았다.
- Docker 동시성 fixture 두 custom/title-only 항목의 임시 location facts를 {}로 전환했다. 다른 정상 Java fixture 9곳도 같은 방식으로 정리했으며 위치 거부·legacy rollback 테스트는 유지했다.
- `/tmp/jeju-225-route-docker-closed-facts.log`에서 FK leading index 누락을 검출했다. CLI로 새 016을 생성하고 16개 FK 선두 index를 등록했다. `/tmp/jeju-225-route-index-pg-green.log`의 PG16/17 전체 schema 계약 및 `/tmp/jeju-225-route-index-docker-green.log`의 Docker 전체 preflight·정리(exit0)가 통과했다.
- 관련 fixture 회귀 80건 중 79건은 성공했고, 여행 직접 자식 FK가 CASCADE여야 한다는 기존 계약 1건이 실패했다 (`/tmp/jeju-225-closed-fixture-regression.log`). 실제 route를 포함한 여행 삭제도 RED(`/tmp/jeju-225-route-trip-delete-red.log`)로 재현했다.
- 미커밋 016을 `planned_route_reference_integrity.sql`로 정리하고 owner-trip FK만 ON DELETE CASCADE로 교체했다. 공개 place/stop 및 계획 anchor FK는 유지한다. negative route hash SQL은 유효한 기존 계보를 복사한 뒤 hash만 잘못 넣어 실패 원인을 분리했다.
- 최종 `/tmp/jeju-225-route-owner-integrity-green.log` PASS(2m20s): 실제 여행 삭제 시 route 제거/public place 3개·owner 보존, 기존 trip 삭제 계약, PG16/17 schema 검증 총4건. registry14건과 diff 검사도 PASS. 독립 advisory 추가 finding0.
- 위 Docker preflight는 owner-trip CASCADE 최종 보강 이전 증거이다. 최신 base/최종 HEAD의 전체 공식 gate·Docker·공식 리뷰는 #222 병합 후 다시 수행한다. 아직 PR·공식 승인·운영 적용은 없다.

## 선행 API 병합 후 최종 gate 준비

- 016 참조 무결성과 fixture 보강은 `27cbec8c1a9cc5b802857556e2b43df247e051dd`로 커밋됐다. 013–016은 모두 immutable이며 수정하지 않는다.
- Places PR231 및 Weather PR233 병합 후 최신 develop `be3b13be81c44f01b7d13ed2fd537cc9a8f6b4fe`를 충돌 없이 통합했다 (merge `8e10d3ca0f248aa7fb5f63a42fe49f4ff0a24490`).
- 이 최종 범위에서 공식 전체 quality gate·Docker를 단독 실행한다. 다른 worktree의 PostgreSQL 테스트가 종료되고 disposable 자원이 정리된 뒤 시작한다. 기존 선별 검사·preflight를 최종 gate로 대체 주장하지 않는다.
- TMAP 경로 결과 영속 허용은 #216 미확정으로 계속 차단하며, 이 변경의 경로 저장·QA는 승인된 합성 fixture로 제한된다. 실제 provider·staging·운영 DB 적용 및 배포는 수행하지 않는다.

## 공식 gate의 로컬 fixture 배치 보정

- `dbefbaa` 전체 gate는 Docker 실행 전 배포 SQL 정책 검사에서 RED였다 (`/tmp/jeju-225-quality-dbefbaa-solo.log`). 새 legacy QA fixture가 `db/queries`에 있어 로컬 합성 계정 INSERT가 운영 적용 가능 SQL로 분류됐다.
- fixture를 기존 정책이 지정한 `db/local-postgres`로 이동하고 Java 테스트의 참조 두 곳만 수정했다. SQL 본문과 immutable migration은 변경하지 않았고 정책 allowlist를 추가하지 않았다.
- 배포 SQL 정책 검사와 기존 정책 회귀 22건 PASS (`/tmp/jeju-225-local-fixture-policy-green.log`). 최신 commit의 전체 gate로 다시 검증한다. 최초 실행은 최종 품질 게이트 성공 근거가 아니다.

- 공통 gate `/tmp/jeju-225-common-fc30462.log`에서 기존 정적 기대값 4건이 RED였다. 완료 여행 fixture에 예전 facts.location을 요구하던 assertion 1건과 migration013–016 이전의 목록/역사·현재 smoke 종료 지점 기대값 3건이다.
- 정적 검사를 위치 없는 custom fixture와 현재 등록 순서에 맞췄다. 역사 v1은052까지 검증하고053은 별도 실패·rollback audit로 실행하며, 현재 concurrency는054까지 실행한다는 구분을 그대로 검사한다. 관련25건 PASS(`/tmp/jeju-225-common-contract-green.log`).

- `5913f5b` 전체 gate는 공통821건(3 SKIPPED) 통과 후 Java format에서 중단됐다. `spotlessApply spotlessCheck`로 저장소 표준 포맷을 적용·검증했다 (`/tmp/jeju-225-format-green.log` PASS). 대상은 schedule mutation adapter 및 새/수정된 PostgreSQL 테스트3개이며, SQL migration과 정책은 그대로다. 최종 포맷 commit을 기준으로 전체 gate를 다시 실행한다.

## #222 병합 후 날씨 fixture 통합 회귀

- `98d56f2` 전체 gate의 integrationTest에서 기존 날씨 테스트가 `trip_items.facts.location`을 저장하다 #225의 closed facts guard에 거부됐다. 검증을 SIGINT로 중단해 watchdog 진단을 남겼다(`/tmp/jeju-225-quality-98d56f2-solo.log`, exit126, interrupted/new-docker-resource-residue). 이후 process 종료와 Docker 컨테이너 0개를 확인했다. 이 실행은 전체 gate 성공 근거가 아니다.
- 대상 테스트 재현 `/tmp/jeju-225-weather-closed-facts-red.log`에서 동일 저장 실패를 확인했다. 공개 place FK 없는 일정은 조회 불가를 먼저 검증하고, 이어 위치 JSON 저장 시도 자체가 값 비반사 오류로 거부됨을 별도로 검증하도록 fixture를 변경했다. 운영 코드와 기존 migration은 변경하지 않았다.
- GREEN: 대상 날씨 9건 failure/error 0, `/tmp/jeju-225-weather-closed-facts-green.log`, 58s PASS. 독립 delta review finding 0. 전체 gate는 새 commit에서 다시 검증한다.

## 2026-09-09 최종 검사 후 도달 불가능한 조회 정리

- `c63f803` 단독 전체 gate는 공통821(3 skip), unit1351(9 skip), slice56, integration796(4 skip), OpenAPI11 및 runtime FE37 operation을 통과했다. architecture의 이전 migration 개수48/마지막012 기대값 한 건이 실패했다 (`/tmp/jeju-225-quality-c63f803-solo.log`). 현재 등록52개/013–016 연속성/마지막016을 검증하도록 테스트를 수정했다. 기존 migration은 변경하지 않았다.
- 후속 preflight에서 architecture는 통과했고 커버리지는 22058/24524(89.94%)로 기존90% 기준에 미달했다 (`/tmp/jeju-225-remaining-gates-preflight.log`). 기준을 낮추거나 제외 대상을 추가하지 않는다.
- #225의 버전·항목 범위 제한 후 새 version/item UUID를 생성하는 transaction 안에서는 `insertStoredSnapshotLeg`가 조회할 snapshot이 아직 존재할 수 없다. 독립 리뷰에서 확인한 이 도달 불가능한 조회와 전용 record를 제거했다. 변경 없는 기존 구간은 SourceLeg 복사/clone을 유지하고, 변경 구간은 기존 canonical 공개 좌표 기반 수동 편집 fallback을 그대로 호출한다.
- 교차 버전 snapshot 미사용 회귀에 fallback 두 구간과 외부 snapshot 보존 검증을 추가했다. 미커밋 delta 독립 리뷰 finding0. 공식 승인과 최종 전체 gate는 아직 미완료다.
- 코드 변경 전 mutation 회귀 `/tmp/jeju-225-dead-lookup-regression.log` PASS(1m21s); 변경 후 강화한 회귀와 커버리지·빌드·Docker를 순차 재검증한다.
- 강화 mutation85건 PASS(`/tmp/jeju-225-dead-lookup-green.log`,1m23s). 다음 architecture 검사에서 제거한 snapshot 조회의 place-null 문자열에 의존한 과거 source assertion 한 건이 실패했다. 실제 fallback이 사용하는 canonical anchor resolver 호출 및 해석 실패의 legIncomplete 검증으로 정렬했다. 이는 저장소 문자열 검사의 정정이며 canonical ID 검증을 약화하지 않는다.
- 현재 source architecture/test 및 bootJar PASS. 선별 integration85 실행이 전체796 실행의 JaCoCo 파일을 교체했으므로 후속 coverage67%는 전체 범위의 재측정이 아니며 성공 증거로 쓰지 않는다 (`/tmp/jeju-225-canonical-source-coverage.log`). 공식 gate가 모든 execution data를 지우고 전체 suite에서 다시 수집한 수치만 최종 판정에 사용한다.
- `/tmp/jeju-225-final-preflight-docker.log` exit0 PASS: 이미지/health, fresh·origin-develop·역사 upgrade fingerprint, 053 fail-closed rollback, 실제2세션 동시성, schema/음수무결성/PostGIS fixture 및 자원 정리. ACL 거부 계약의 예상 permission denied는 실패가 아니다. 최종 commit의 공식 전체 gate는 별도로 실행한다.
