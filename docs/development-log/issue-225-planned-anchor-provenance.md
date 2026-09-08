# #225 계획 anchor provenance 구현 일지

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
