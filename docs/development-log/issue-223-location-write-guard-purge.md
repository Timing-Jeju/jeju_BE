# Issue 223 위치 쓰기 차단과 파생 계보 정리

## 최신 검증 상태 (2026-09-09)

선행 #222/#225는 병합됐으며 그 develop을 통합한 #223 브랜치에서 작업한다. 017 guard/purge·부모/input 계보 제약·worker claim 차단·owner-only zero verifier와 등록을 구현했다. 아래 초기 작업 기록의 미구현 목록은 당시 상태이며 현재 남은 작업은 같은 SHA 전체 quality gate/Docker, 독립 공식 리뷰다. 017을 등록했으며 실제 DB 적용·배포는 하지 않았다.

정상 command의 MCP wire hash를 안전으로 간주했던 기대는 폐기했다. 잔여 MCP 로그와 opaque idempotency receipt는 모두 미분류 자료로 전환을 차단한다. #224 runtime 사전 검증 및 staging 인수는 별도이며 전체 FE 통합 완료가 아니다.

## 초기 기준과 상태 이력

- develop `4023095`에서 독립 브랜치를 만들고 #225의 `0d656c0`을 테스트 선행 의존성으로 임시 통합했다. 후속 PR은 #222·#225 병합 후 최신 develop에서 전체 gate와 공식 리뷰를 다시 검증한다.
- 현재는 TDD 초반이다. `LocationWriteGuardIntegrationTest`에서 service_role의 event 직접 좌표·nested alias·grid/hash 및 live 직접 좌표·current place·nested nearest place 신규 쓰기가 모두 허용됨을 RED로 확인했다. 위치 없는 event/live 1건은 기존 동작 성공이다 (`/tmp/jeju-223-write-guard-red.log`, 7건 중 예상 RED 6건).
- pinned Supabase CLI 2.116.0의 `migration new`로 생성한 파일을 canonical suffix 017 (#225의 FK index 보강 016 다음)으로 옮겼다. 아직 미커밋·미등록 WIP이며 배포 대상이 아니다.
- 첫 guard는 event metadata를 빈 object 또는 source enum(manual/time/system/mobile)으로, live를 계획 item/leg·status·observed_at의 위치 없는 projection으로 제한한다. live facts/next_action의 기존 계산 서술은 출처를 검증할 수 없어 신규 작성 계약에서 허용하지 않는다. 공개 장소·정류장·계획 anchor 좌표는 수정하지 않는다.

## 초기 작업 목록 (이후 실행 기록 참조)

1. compute input의 위치·redacted·digest-only guard RED→GREEN.
2. recursive JSON audit 및 closed shape 검증, worker 재기록 차단을 포함한 명시적 lock.
3. location_supplied=true 전체(이미 redacted 포함)에서 부모 run과 파생 결과의 대상 ID 집합 고정.
4. active/applied 버전 및 recursive base 후손, 다른 run의 참조가 모호하면 전체 rollback.
5. execution event append-only를 migration transaction 안에서만 관리하여 직접 위치·위치 metadata를 정리하고 정상 non-location 감사 계보를 보존.
6. live 및 MCP/result/candidate/일정 파생물을 명확한 FK 계보 안에서만 정리. 부모 run 삭제만으로 candidate/proposed schedule version이 사라진다고 가정하지 않는다.
7. idempotency 기록은 run FK가 없으므로 owner/path/idempotency 연결을 증명하지 못하면 삭제를 추정하지 않는다.
8. zero-residue verifier가 object/count만 반환하고 policy marker가 작성돼야 commit. 현재 migration은 이 조건을 구현하지 않았으므로 완료로 취급하지 않는다.
9. PG16/17 정상·모호성 rollback·ACL/RLS·공개 geodata 보존·Docker·전체 공식 gate·독립 리뷰.

## 독립 검토의 삭제 경계

- location_supplied=true 행은 coarse_location=NULL이어도 command hash가 남는다.
- generation candidate와 recovery proposed version은 parent run 삭제 후에도 남을 수 있다.
- live→compute와 recovery→risk의 NO ACTION 참조, active pointer·base 후손·다른 run/input·route snapshot 참조를 먼저 감사한다.
- 비draft version/item/leg 불변성과 execution event append-only를 부모 trip 삭제로 우회하지 않는다.

운영 DB·Supabase 프로젝트 적용, 실제 provider 호출 및 배포는 수행하지 않는다. 공식 quality gate와 다른 Docker 작업은 겹치지 않는다.

## event/live guard 첫 GREEN

- `/tmp/jeju-223-event-live-bound-guard-green.log`: 신규 직접·nested·grid·current place·nearest place 위치 거부 6건과 정상 non-location 유지 1건 PASS.
- 첫 GREEN 시도에서 테스트 SQL에 합성 좌표 상수를 직접 넣어 JDBC 예외의 SQL 문자열에 좌표가 남았다. 실제 입력처럼 bind parameter로 변경했고 오류 값 비반사 검증을 유지했다. DB 정책을 완화하거나 오류 검증을 삭제하지 않았다.
- 신규 guard만 검증했으며 compute input guard·legacy purge·marker·registry·전체 gate는 미완료다.

- 다음 단계의 compute GRID_100M/PLACE/STOP 쓰기 거부 3건과 non-location hash 저장 1건을 테스트로 작성했다. #222 공식 gate 단독 실행을 보장하기 위해 아직 실행하지 않았고, 이에 대응하는 production input guard도 아직 추가하지 않았다.

- 추가 migration 회귀 초안: PG16/17에서 직접/nested event 위치 정리·source 보존·append-only 복원·public geodata 보존, 의미 불명 metadata 전체 rollback, raw/redacted 위치 입력의 부모 run·MCP hash 동시 제거를 작성했다. Docker 실행은 #222 gate 종료 이후이며 현재는 컴파일만 확인했다. 해당 purge 구현은 아직 작성하지 않았다.

## 실행 전 fixture 검토와 계보 감사 준비

- 독립 Reviewer가 terminal compute fixture의 기존 execution-phase CHECK 선행 실패를 발견했다. `started_at`이 있는 failed run에 `facts_snapshot_at`과 `source_data_version`을 함께 넣었으며 `compileTestJava`를 통과했다. 아직 purge RED 실행 근거는 아니다.
- PG16/17 × queued/running 네 가지 active-worker rollback 테스트를 추가했다. 위치 입력이 연결된 실행 가능 작업은 삭제·lease/fencing 변경 없이 스키마와 run/input/MCP/event/live 전체 digest가 유지돼야 한다. DB 실행은 #222 단독 공식 gate 종료 이후다.
- JSON 감사 대상은 command/generation input, compute result summary, risk/weather computed facts, recommendation facts, recovery summary/change before·after, event/live, AI message structured payload, item/leg facts다. 공개 geodata 테이블은 사용자 generic JSON 감사 대상과 구분한다.
- 출력 일정은 generation candidate schedule version 및 recovery proposed version에서 시작해 base version의 후손을 재귀 추적한다. compute의 평가 대상 version을 곧바로 출력으로 분류하지 않는다.
- 현재 generic idempotency registry에는 run FK나 owner/path/key 대응을 보장하는 생성 SQL이 없다. hash 일치만으로 삭제하지 않으며, 관련 기록의 출처가 모호하면 감사 중단 대상으로 다룬다.

- 추가 준비: 기존 event/live 행의 직접 좌표 UPDATE 값 비반사 거부 2건, private guard 함수의 anon/authenticated/service_role 직접 EXECUTE 불가 3건, PG16/17에서 recovery proposed version과 재귀 base 후손 제거·평가 대상 원본 보존 2건. 모두 DB 실행 전이며 `compileTestJava`는 통과했다.

## Hash-only 재기록 경계 — 해결 전 완료 금지

- 독립 코드 검토로 MCP 기본 비활성은 DB write guard가 아님을 확인했다. `JdbcRunLeaseRepository`는 input join 없이 claim하며, `JdbcMcpCallAuditWriter`는 전달받은 command/MCP hash를 저장한다. 현재 Java에는 parent 생성과 input 저장을 하나의 transaction으로 묶는 서비스가 없다.
- Input 없는 parent hash, owner/trip/base/type·정의된 hash 관계 불일치, input과 연결되지 않은 MCP log는 무위치로 추정하지 않는다. command hash와 wire hash는 서로 다른 값이므로 단순 같음 비교를 하지 않는다.
- Parent와 정확히 하나의 무위치 input을 commit에서 검증하는 deferred linkage는 가능하지만 새 쓰기 계약이며, MCP wire provenance 검증까지 대신하지 않는다. #224 이전 DB 쓰기·claim 경계를 명시적으로 닫는 구현과 RED 검증이 추가로 필요하다. 현재 event/live/input guard 초안만으로 #223을 완료 처리하지 않는다.

## Purge RED 실행

- `/tmp/jeju-223-input-purge-red.log`: `LocationDataPurgeMigrationIntegrationTest` 14건이 모두 의도한 이유로 RED다. PG16/17에서 불명 metadata 및 queued/running 중단 누락, 직접 위치 잔존, raw/redacted 위치 input·hash 잔존, proposed/후손 결과 잔존을 재현했다. 잘못된 FK/CHECK 선행 실패는 없다.
- 같은 실행의 `LocationWriteGuardIntegrationTest` 16건은 정류장 fixture의 source_provider 생략으로 normalized-source lineage 검사에서 먼저 실패했다. 신규 위치 guard RED 근거로 사용하지 않는다. 공개 합성 정류장을 `source_provider='fixture'`로 명시해 별도 재실행한다. 운영 guard/검증을 완화하지 않았다.

## Command input guard GREEN

- 두 번째 fixture 확인에서 service_role이 private canonical hash 함수를 직접 실행할 수 없다는 기존 ACL이 작동했다. 실제 Java 경계처럼 role 전환 전에 hash를 계산하고 INSERT에는 bind parameter로 전달하도록 테스트를 수정했다. 함수 권한은 바꾸지 않았다.
- `/tmp/jeju-223-input-bound-hash-red.log`: 16건 중 GRID_100M/PLACE/STOP 3건만 쓰기가 잘못 허용돼 의도한 RED, 기존 event/live·UPDATE·ACL·무위치 입력 13건 PASS.
- WIP017에 command input BEFORE INSERT/UPDATE guard를 추가했다. `location_supplied=false`와 모든 위치 metadata NULL만 허용하고, 위반은 입력값 없는 23514로 거부한다. 함수 직접 EXECUTE는 모든 API 역할에서 금지한다.
- `/tmp/jeju-223-input-guard-green.log`: 16건 모두 PASS, 1m12s. 이 증거는 신규 event/live/input 쓰기 guard에만 해당한다. legacy purge 14건은 아직 RED이며, unknown hash 재기록·recursive audit·marker·registry·전체 gate는 남아 있다.
- #225 공식 solo gate를 시작하므로 다음 Docker 검증은 해당 gate가 종료되고 자원이 정리된 뒤 수행한다.

## 불명 metadata audit GREEN

- WIP017에 사용자 generic JSON 전용 recursive location-key 감사와 event metadata 정리 helper를 추가했다. 공개 지리 테이블에는 적용하지 않는다. 위치 필드 제거 때문에 비게 된 wrapper만 제거하며, 알 수 없는 비위치 leaf·원래 빈 container는 남겨서 감사가 중단되게 한다.
- 위치 필드를 제외한 나머지가 {} 또는 허용된 source 하나인 경우만 다음 단계로 진행한다. 그 외에는 고정 23514 `legacy non-location metadata requires audit`로 transaction 전체를 중단한다.
- `/tmp/jeju-223-event-audit-green.log` PG16/17 2건 PASS(2m15s): 오류 값 비반사 및 schema/RLS/ACL·event/live 데이터 digest 전체 동일. 실제 purge·worker/hash closure·zero verifier·marker는 아직 남아 있으므로 migration은 계속 미등록 WIP다.

- `/tmp/jeju-223-active-audit-green.log` PG16/17 × queued/running 4건 PASS(3m51s). parent/input/event/live의 명시 lock 아래 ID만 temp scope로 고정하고 실행 가능한 부모가 있으면 입력값 없는23514로 중단한다. 오류 뒤 schema/RLS/ACL·run/input/MCP/event/live digest 전체 동일, lease/fencing 불변을 확인했다. 이 검증 이후 #225 최종 solo gate를 시작하므로 다른 Docker 검증은 대기한다.

## 다음 검증 준비와 직접 위치 정리 WIP

- 기존 직접/nested 잔존 RED에 대응해 event의 감사된 위치 필드만 NULL/제거하고 source와 행 자체는 보존하는 UPDATE를 추가했다. append-only trigger는 동일 transaction 안에서 잠시 관리한 뒤 즉시 복원한다. 위치 또는 출처 불명 서술이 있는 live projection만 삭제하고 trip/version/manual event는 보존한다. 해당 추가 코드는 아직 재검증 전이다.
- 신규 hash closure 테스트4건: 입력 없는 worker claim, parent-only 지연 제약, input 없는 MCP INSERT 거부, 동일transaction의 유효한 무위치 input/parent claim·최소 audit. 코드가 wire hash와 command hash를 동일시하지 않도록 기대값을 분리했다. 아직 DB RED 실행 전이며 production closure 변경은 없다.
- legacy unknown hash 테스트6건(PG16/17): redacted 입력을 삭제해 출처가 없어진 parent/MCP, parent input_hash 불일치, MCP command hash 불일치에서 고정 audit 오류와 전체 schema/data rollback을 요구한다. 아직 DB RED 실행 전이다.
- 새 테스트는 compileTestJava 통과. #225 solo gate 종료 전 다른 Docker 테스트는 실행하지 않는다. raw wire hash 검증은 command linkage만으로 완성되지 않으며 #224/0.8 계약 전환과 함께 별도 검증해야 한다.

## 생성 출력 계보 테스트 준비

- PG16/17 × raw/redacted 4건을 추가했다. location generation input, 부모 generation run, MCP command/wire hash, 미적용 draft 후보 3개를 함께 제거하고 원래 base version과 Day를 보존하도록 요구한다. `compileTestJava` PASS(`/tmp/jeju-223-generation-purge-compile.log`). DB RED 실행 전이다.
- 독립 fixture 리뷰에서 CHECK/FK/expiry 선행 실패는 발견되지 않았다. 현재 후보는 빈 draft이므로 봉인된 candidate 및 item/leg 제거는 이 4건의 검증 범위에 포함되지 않는다. 봉인 후보 회귀와 해당 원자적 정리는 별도 추가해야 한다.
- #225 전체 gate에서 병합된 #222 날씨 fixture가 facts.location 저장을 시도해 새 closed facts guard에 거부되는 실패를 확인했다. 해당 gate 종료·Docker 정리 전 #223 DB 테스트를 실행하지 않는다.

## Hash closure RED와 claim WIP

- `/tmp/jeju-223-hash-closure-red.log` 20건 중 17 PASS, 신규 3건만 의도한 RED: input 없는 parent claim 허용, 지연 constraint 누락, input 없는 MCP hash INSERT 허용. 정상 same-transaction input/parent claim·MCP audit는 PASS.
- Claim 쿼리에 기존 immutable input과 owner/trip/base/type/contract/algorithm/command hash 및 무위치 metadata의 일치를 요구하는 join을 추가했다. 새 private 함수에 의존하지 않아 기존 schema에서도 입력 없는 작업을 건너뛸 수 있다. 아직 GREEN 실행 전이며 exhausted-run 처리, deferred linkage, MCP guard도 남아 있다.
- #225 새 full solo gate c63f803 실행 중에는 #223 Docker 테스트를 재실행하지 않는다.
- WIP017에 compute parent/input의 최종 상태를 검사하는 deferred constraint와 MCP command lineage BEFORE guard를 추가했다. parent lock으로 input 삭제와 직렬화하며 API 역할의 직접 함수 EXECUTE는 회수했다. 실행 재검증 전이며 generation/revision parent의 deferred closure는 아직 없다. MCP wire hash의 무위치 출처까지 증명하지 않는다.
- 독립 검토에서 exhausted-run 선행 복구 UPDATE에 같은 input 조건이 없어 orphan 한 건이 정상 claim까지 방해할 수 있음을 지적했다. orphan exhausted와 valid queued 공존, input 삭제, parent hash 불일치, MCP command 불일치 테스트를 추가했다. 아직 RED 실행 전이고 exhausted 복구 코드도 변경하지 않았다. 두 세션 경쟁·실제 commit·cascade·generation/revision 범위 추가 검증이 남아 있다.
- PostgreSQL16/17 실제 commit/cascade·두 세션 경쟁 테스트4건을 새 `ComputeInputLineageConcurrencyIntegrationTest`로 준비했다. 첫 독립 검토에서 service_role input DELETE는 기존 SELECT/INSERT-only ACL에 막힘을 확인했다. 권한을 넓히지 않고 service_role 권한 거부와 owner의 deferred 삭제 거부를 분리했으며 경쟁 삭제도 owner 세션으로 바꿨다. parent cascade는 service_role로 유지한다. 아직 DB 실행 전이다.

## 잔존 검사 구현 전 실제 schema 확인

- 사용자 generic JSON: trip_preferences.raw_answers, trip_items.facts, itinerary_generation_runs.structured_input, ai_messages.structured_payload, trip_legs.facts, trip_execution_events.metadata, compute_runs.result_summary, risk_events.computed_facts, trip_weather_impacts.computed_facts, recommendation_candidates.facts, recovery_options.change_summary, recovery_option_changes.before_value/after_value, live_state_snapshots.facts. 공개 provider raw/정규화 geodata와 분리해야 한다.
- generation candidate FK는 version 삭제 cascade이나 generation의 base FK는 NO ACTION이다. candidate row의 version을 곧바로 삭제 대상으로 믿지 말고 input base와 다른 동일 trip 출력인지 먼저 검증해야 한다. 원래 base는 보존 대상이다.
- ai_messages.generation_run_id는 ON DELETE SET NULL이므로 parent만 지워도 structured payload가 남는다. 대화/메시지 출처를 입증하지 못하면 자동으로 사용자 원문을 삭제하지 말고 감사 중단해야 한다.
- sealed candidate는 candidate→rejected가 허용되고 direct DELETE는 draft/rejected만 가능하다. 이미 active/superseded/applied/selected된 출력은 정리하지 않는다. base의 후손과 외부 참조를 먼저 감사해야 한다.
- #170의 revision.request_hash는 canonical request hash이고 #108의 commandInputHash는 durable command 전체 해시다. 기존 계약은 request hash와 command hash의 동일성을 보장하지 않는다. MCP hash뿐 아니라 revision request hash도 이름만 보고 동일 값으로 비교하면 안 된다.
- Jdbc MCP client는 arguments에 requestId를 더해 wire hash를 만든다. command linkage 검증은 실제 wire JSON의 위치 출처를 대체하지 않으므로 #224/0.8에서 별도 ingress/arguments 검증과 계약 전환이 필요하다.
- 기존 JdbcRunLeaseRepositoryIntegrationTest는 session-only trip과 input 없는 임시 hash parent를 만들고 있어 새 계약의 정상 worker fixture로 사용할 수 없다. 실제 owner와 canonical 무위치 input을 parent와 같은 transaction에서 생성하도록 후속 fixture 전환이 필요하다. canonical command 중복에 대한 기존 unique(schedule_version_id,run_type,input_hash,algorithm_version)는 별도 재계산 접수 계약에서도 확인해야 한다.
- 최신 준비 테스트와 claim 코드는 `/tmp/jeju-223-ready-tests-compile.log`의 compileTestJava PASS. 신규 guard24건/legacy purge32건/실제 commit·경쟁4건 중 추가분은 아직 DB 재실행 전이다. 부모/input 정상 저장 테스트는 부모의 canonical hash 일치 및 지연 constraint 즉시 검증을 함께 요구하도록 보완했다.

## 2026-09-09 command lineage 후속 검증

- `/tmp/jeju-223-command-lineage-green-preflight.log`: 28개 중27개 통과. PG16/17 각 commit/삭제 ACL 및 두 세션 claim-vs-input-delete 경쟁 4개는 모두 통과했다. exhausted orphan 테스트의 최초 실패는 동일 fixture hash uniqueness 충돌이므로 동작 RED 근거에서 제외했다.
- fixture의 orphan hash를 별도 합성 값으로 분리한 뒤 `/tmp/jeju-223-exhausted-lineage-red.log`에서 24개 중1개가 의도대로 실패했다. 입력 없는 만료 attempt5 parent가 recovery에서 failed로 변경되어 보존 assertion이0이었다.
- recovery UPDATE에도 claim과 동일한 owner/trip/base/type/contract/algo/command hash 및 위치 메타데이터 부재 조건을 적용했다. 불명확한 parent는 분류하거나 삭제하지 않는다. 정상 input의 만료 마지막 시도는 기존 오류코드로 종료하고 input을 보존하는 positive 회귀도 추가했다.
- 017 전체 purge/감사/zero verifier/등록은 아직 미완료이며 배포하지 않는다.
- GREEN: `/tmp/jeju-223-exhausted-lineage-green.log`24건, 이어 정상 종료 positive 포함 `/tmp/jeju-223-exhausted-positive-green.log`25건 모두 failure/error/skip0. 독립 bounded review finding0. 실제 PG16/17 commit·경쟁4건과 이번 transactional25건은 구분해서 기록하며 전체 gate 성공을 의미하지 않는다.
- 봉인된 생성 후보의 실제 item/leg 정리 검증을 준비했다. generation fixture를 PG16/17×raw/redacted×빈draft/봉인candidate8사례로 늘렸다. 봉인 시 항목6개·구간3개 존재, purge 후 제거 및 공개place/원래Day/base 보존을 검사한다. `/tmp/jeju-223-sealed-candidate-compile.log` compileTestJava PASS; DB 실행 전이며 성공으로 기록하지 않는다. #225 공식 전체 gate의 전역 Docker 자원 검증과 겹치지 않도록 실행을 대기한다.
- 독립 fixture 검토에서 실제 schema에 없는 sealed_at 참조1건을 발견했다. status=candidate 및 기존 public.assert_schedule_version_sealable 실행으로 정정했다. `/tmp/jeju-223-sealed-candidate-review-compile.log` PASS. 이는 DB 동작 실행 증거가 아니며 후속 RED에서 실제 seed·seal 성공과 purge 실패를 분리해 확인한다.
- 기존 JdbcRunLeaseRepositoryIntegrationTest 정상 fixture를 session-only 여행/fake hash/input 없음에서 user-owned 여행/정규 command hash/parent+input 동일 transaction으로 전환했다. 두 정상 run을 비교하는 사례는 알고리즘 fixture 버전을 구분하여 기존 unique 계약을 유지한다. orphan 거부 사례는 별도 LocationWriteGuard/PG16·17 테스트에서 유지한다. DB 실행은 아직 대기 중이다.
- 독립 FK 검토: snapshot이 있는 봉인 candidate는 snapshot DELETE의 leg SET NULL UPDATE와 version/item NO ACTION 참조가 충돌할 수 있다. 일반 순차 DELETE로 해결됐다고 가정하지 않는다. 대상 밖 입력/compute/recovery/generation/메시지 계보를 감사하고, 실제 snapshot 포함 사례의 rollback 또는 제한된 명시 제거 절차를 PG16/17에서 검증한 뒤 구현한다. ai_messages의 generation FK SET NULL만으로 payload가 삭제된다고 주장하지 않는다.
- 위 생성 fixture를 합성 route snapshot이 연결된 봉인 후보까지 확대했다(PG16/17×raw/redacted×draft/sealed/sealed_route12사례, purge suite 전체40사례 예정). route 포함 시 snapshot3개 존재/제거도 검증한다. provider='fixture'만 사용하고 실제 TMAP 저장 허용으로 해석하지 않는다. runtime DB 검증은 #225 full gate 이후이며 이 준비만으로 FK cycle 해결을 주장하지 않는다.
- 현재 로컬 seed만 무위치 계약으로 전환했다: execution 위치null/source만 유지, live current좌표·place·next_action null/facts{}, 정상 compute2/generation1의 canonical input snapshot과 MCP command hash 연결. 입력과 부모는 seed의 기존 begin/commit 안에서 함께 저장된다. 공개 geodata와 별도 historical fixture는 수정하지 않았다. 생성 seed의 기존 단일 QA 후보는 command candidateCount1로 정렬했으며 실제 제품의3후보 성공 흐름 구현으로 주장하지 않는다. DB 검증·migration등록 전이다.
- 정적 preflight45개 중2실패: 하나는 아직223 base에 없는225의 과거 facts.location assertion 수정이며 선행 병합으로 해소할 대상이다. 다른 하나는 신규 hash 호출9개의 명시 타입 누락이다. typed column도8인자 정확한 cast를 요구하는 회귀 RED(`/tmp/jeju-223-hash-column-types-red.log`) 후 신규 호출 모두 text/smallint/uuid/jsonb/boolean을 명시하고 Java SQL을 text block으로 정리했다. 정적 검사기는 명시 cast된 단순/qualified 컬럼만 추가 허용하고 cast 누락8경계는 계속 거부한다. 전체 direct exact14개와 타입 경계2개 GREEN(`/tmp/jeju-223-hash-column-types-green.log`).
- 일정 조회·여행 점수·옵션 private MCP 정상 fixture에 공통 LocationFreeComputeInputFixture를 연결했다. 호출자 transaction을 요구하고 owner가 있는 feasibility parent의 명시 타입 canonical hash를 계산해 input을 같은 transaction에 저장한다. 점수 비교의 여러 run은 기존 unique 의미를 유지하도록 합성 알고리즘 버전을 구분했다. private MCP fixture는 고정 a64 대신 실제 command hash를 전달하며 wire hash는 별도다. compileTestJava PASS(`/tmp/jeju-223-normal-input-fixtures-compile.log`), 신규 helper 포함 direct exact15개 정적 타입 검사 PASS(`/tmp/jeju-223-normal-fixture-hash-static.log`). DB 실행 및 MCP live 검증은 아직 하지 않았다.
- current Java canonicalizer/repository 기반 무위치 save→deferred constraint 즉시 검사→find/usable-location 없음→worker claim→find 동일 hash 회귀를 추가했다(LocationWriteGuard26사례 예정). 과거 위치 허용 repository 테스트를 current 무위치 계약 검증으로 오인하지 않도록 새 현재 경로를 직접 검사한다. compile 결과와 DB 실행은 구분한다.

### 봉인 후보의 FK 순환 정리 설계와 검증 경계

- reviewer 독립 검토상 version cascade로 leg를 먼저 지운 뒤 snapshot을 지우는 순서는 기존 봉인 trigger와 양립할 수 있다. 단, 현재 구현/DB 통과 근거는 아니다.
- migration의 22개 table ACCESS EXCLUSIVE lock과 ID-only scope 감사 뒤, route의 planned version/origin item/destination item 세 NO ACTION FK만 일시 DEFERRABLE로 바꾸는 방식을 검증한다. 제약을 DROP하거나 guard trigger를 끄지 않는다.
- 이름으로 SET CONSTRAINTS를 호출하기 전에 세 이름이 예상 relation에 유일하게 존재하는지 감사해야 한다. DEFERRED 이후 candidate→rejected, recovery changes/options 정리, descendant-first version 삭제, scope snapshot 삭제, IMMEDIATE 재검사, 원래 NOT DEFERRABLE 복원을 같은 transaction에 둔다.
- active와 superseded, 적용 흔적, scope 밖 leg/snapshot/input/run/base/live 참조, 미분류 ai_messages payload는 자동 삭제하지 않고 고정 오류로 rollback한다. base schedule은 계산 output으로 오인하지 않는다.
- PG16/17 generation purge 테스트에 FK OID·정의·condeferrable·condeferred·convalidated 동일성 assertion을 추가했다. 아직 실행하지 않았다. 중간 실패 rollback에서 data/schema/trigger/ACL도 별도로 검증해야 한다.
- 공식 문서: https://www.postgresql.org/docs/16/sql-altertable.html (FK attribute 변경), https://www.postgresql.org/docs/16/sql-set-constraints.html (IMMEDIATE 전환 시 미처리 변경 재검사, 이름 중복 주의). 문서상 가능하다는 사실은 실제 프로젝트 삭제 순서 검증을 대신하지 않는다.

### 현행 guard 및 동시성 재검증

`/tmp/jeju-223-current-guards-concurrency.log`: integrationTest exit0, 6m49s. LocationWriteGuard26, ComputeInputLineageConcurrency4, LocationPurgeLockConcurrency4 = 34 PASS, skip0. 실제 canonicalizer/repository roundtrip, 정상 exhausted 처리/입력 없는 legacy 보존, PG16/17 두 세션의 claim·input delete 경쟁과 MCP/preferences relation lock을 검증했다. 이는 아직 미구현 purge·zero-residue 전체 완료 근거가 아니다.

### Legacy command/hash 감사 RED와 구현

`/tmp/jeju-223-legacy-hash-audit-red.log`: PG16/17 × missing_input/parent_hash/log_hash 6건 모두 의도한 AssertionError(예외 발생 부재), 4m44s. fixture 오류는 없었다.

017 WIP에 ID-only audited_command_input_scope와 전체 부모·input·MCP 역방향 감사를 추가했다. owner/trip/base/type/schema/closed structuredInput/contract/algo를 고정하고, 원문이 존재하는 input은 기존 8인자 hash 함수로 재계산한다. redacted input은 원문을 재구성하지 않으며 위치 삭제 대상 계보만 식별한다. exact parent ID와 command hash가 일치하지 않거나 legacy MCP 부모가 모호하면 고정 `legacy compute hash lineage requires audit`로 transaction 전체를 되돌린다. revision request hash나 MCP wire hash를 command hash와 동일시하지 않는다.

위치 없는 정상 queued input/MCP 행을 그대로 보존하는 PG16/17 positive 2건을 추가했다. 현재 재검증은 `/tmp/jeju-223-legacy-hash-audit-green.log` 8건 실행 중이다. SQL 신규 재계산과 positive fixture를 포함한 explicit 8인자 direct call inventory는17로 갱신했다. static19건 중18PASS, known old facts.location assertion1실패(최신225 base를 통합하면 해결할 대상)이며 `/tmp/jeju-223-audit-hash-static.log`에 기록했다.

### 감사 GREEN 및 선행 base 통합

`/tmp/jeju-223-legacy-hash-audit-green.log`: 기존6 rollback + 정상 비위치 보존2 = 8PASS(skip0), 6m18s. 같은 손상 hash를 input/parent/MCP에 맞춘 추가 PG16/17 2건은 이후 추가했으므로 이 결과에 포함하지 않는다. 새 감사 블록의 독립 bounded source review finding0(공식 승인 아님).

#225 PR236 merge ec2225d3ca5324b45a41860eb4232ed9dcd5d72b/원격 CI34248979124SUCCESS 후 #223에 최신 origin/develop을 통합했다. 현재 HEAD a10e7ab4b0b1139fb076c854a78e39946fccb3d3. WIP은 /tmp/jeju-223-before-225-base-final 및 named stash에 보존한 뒤 충돌 없이 적용했다. 017은 여전히 미커밋·미등록 WIP이며 이전013–016 SQL은 수정하지 않았다.

### 후보 삭제 초안과 독립 FK 재검토

현재 017 적용 전 초안 `/tmp/jeju-223-output-scope.sql`, `/tmp/jeju-223-output-purge.sql`을 준비했다. generation candidate/proposed + base 후손 ID scope를 먼저 고정하고, active/superseded/applied/selected·외부 input/run·수동 event/progress·외부 candidate/recovery risk/leg 참조는 중단한다. known scope의 live projection, recovery options/changes, parent/result를 정리한 뒤 candidate를 rejected로 전환하고 leaf version→route 순으로 정리한다. 세 route FK만 명시 지연하고 IMMEDIATE 검증 뒤 OID/정의/trigger timing을 원복한다. mid-delete integrity 오류는 고정 문구로 변환해 값이 반사되지 않게 한다. 아직 DB 통과 근거가 아니다.

독립 reviewer가 ai_messages의 generation FK만으로 conversation owner/trip가 보장되지 않는 경계1건을 찾았다. 초안에 scope.id→compute_run_inputs의 owner/trip와 ai_conversations의 일치 조건을 추가했다. user/system 메시지 또는 다른 owner/trip·trip NULL이면 자동 삭제하지 않는다. 대응 DB regression은 아직 미실행이다.

`source_snapshot_id`는 route self FK가 아니라 external_api_snapshots FK이므로 route ID와 비교하지 않는다. idempotency registry의 응답 bytea/hash는 run FK가 없으므로 별도 감사가 필요하다. 미분류 일반JSON/멱등성 기록 및 zero verifier/정책 marker/등록이 끝나기 전017은 출하하지 않는다.

PG16/17에서 snapshot DELETE를 합성 trigger로 중간 실패시키고 전체 data/schema/ACL/trigger 상태 및 FK OID 원복을 확인하는 2개 case를 추가했다. 현재 진행 중인 generation12 RED에는 이 추가2건이 포함되지 않는다.

### 후보 전체 정리 RED → GREEN

`/tmp/jeju-223-generation-closure-red.log`: 12건 모두 residual generation run 1건(기대0)의 의도한 실패, 9m53s. draft/sealed/sealed_route × raw/redacted × PG16/17 fixture가 모두 유효함을 확인했다.

초안의 메시지 owner/trip 보강을 포함해 017에 output scope와 purge 순서를 적용했다. `/tmp/jeju-223-generation-closure-green.log`: 12 positive + PG16/17 snapshot DELETE 중간 실패 rollback2 = 14PASS(skip0), 11m41s. candidate/input/MCP/version/item/leg/route 삭제, 원본 Day·base/public place 보존, FK OID/definition/trigger timing 원복 및 오류 중 data/schema/ACL/all trigger state rollback을 확인했다. 아직 전체 #223 완료는 아니다.

메시지 role/owner/trip negative5종과 정확한 assistant/tool 삭제를 각 PG16/17 컨테이너 안에서 연속 rollback→정상 적용으로 검증하는 테스트2건을 추가했다. source hash call inventory18과 static19PASS. 아직 해당 DB 결과는 대기 중이다.

zero verifier 초안은 `/tmp/jeju-223-residue-verifier.sql`이며, 이름/count만 제공하고 API 역할 실행 권한을 주지 않는다. 미분류 api_idempotency_records가 있으면 zero로 간주하지 않는다. 신규 guard23번째 lock(api_idempotency_records)의 static RED `/tmp/jeju-223-idempotency-lock-red.log`를 확인했고, 해당 실제 relation lock PG16/17 2case를 준비했다. verifier/23번째 lock은 아직017 미적용 상태다.

#224 pre-hash ingress 보강 근거: https://github.com/Timing-Jeju/jeju_BE/issues/224#issuecomment-5588695562 . Idempotency acquire의 별도 transaction이 body parsing 이전 hash를 저장하는 실제 경로를 확인했다. release 후 삭제만으로 신규 비수집을 주장하지 않는다.


### 잔여량·잠금 검증 및 generic JSON 후속

`/tmp/jeju-223-zero-lock-green-legacy-red.log`: 11건 중 신규 검사10PASS, 과거 schema 테스트1건 의도한 RED(`compute input lineage required`), 8m27s. PG16/17의 opaque 멱등성 기록 중단/rollback·빈 잔여량/marker 권한2, 복구 proposed/후손 정리2, MCP/preferences/idempotency 두 세션 잠금6이 통과했다. 017은 이제23개 relation lock과 owner-only zero verifier/성공 marker를 포함한다. 실제 사용자 receipt의 임의 삭제는 하지 않는다.

과거 #108/#109 보존·redaction repository 테스트는017 직전 schema로 고정했다. 새 schema의 실제 canonicalizer/repository/lease 동작은 LocationWriteGuard에서 별도로 검증한다. 과거 fixture 수용을 위해 신규 위치 금지 제약을 완화하지 않는다. 변경된 역사 테스트14건 재검증 대기.

독립 bounded review에서 recursive key 목록에 계약상 금지된 geohash 누락1건을 발견했다. 중첩 배열의 geohash/Geo-Hash/geo_hash/GEOHASH를 정상 필수필드가 있는 preferences INSERT로 검증하는4개 RED 테스트를 추가했다. generic JSON11필드의 오류 비반사 테스트11건 및 정상 공개 선택 보존1건과 함께 실행 중이다. generic trigger와 geohash SQL 수정은 RED 확인 뒤 적용한다.


`/tmp/jeju-223-generic-geohash-red-history-green.log`: 과거 schema repository14PASS. generic11필드에는 의도한 privacy-first 오류 부재 RED를 확인했다. geohash4 및 정상 선호1은 fixture의 필수 arrival/departure region 누락으로 실패했으므로 유효한 geohash RED로 계산하지 않는다. 해당 fixture에 명시 계획 지역을 추가하고 재검증한다. 생성/revision의 parent-input 지연 검증8건도 추가했으며 SQL 초안은 적용 전 독립 검토 중이다.


`/tmp/jeju-223-geohash-planner-parent-red.log`: geohash4는 필수 region 보정 뒤 실제 INSERT 허용의 의도한 RED, 정상 선호1PASS. 생성 fixture status 누락은 이후 queued로 보정했다. revision metadata는 이미 즉시 불변 제약으로 차단되므로 별도 기존 제약 보존 테스트로 분리했다.

`/tmp/jeju-223-generic-green-planner-red.log`:50건 중44PASS. generic11/파생 geohash4/정상 공개 선택/기존 guard 전부GREEN. 생성·수정 parent-only2/input 삭제2/generation metadata 불일치1은 의도한 예외 부재 RED. 나머지 revision metadata1은 기존 즉시 거부를 지연 검사로 오인한 테스트 오류로 위와 같이 수정했다. 독립 source review finding0의 planner parent guard를017에 적용하고 전체50건 GREEN을 확인한다.


### 현재 guard·정상 repository 검증 및 등록

`/tmp/jeju-223-parent-normal-green.log`: guard50/worker12/schedule7=69PASS. score1은 setup의 SET CONSTRAINTS ALL IMMEDIATE가 이후 parent INSERT에도 유지되어 입력 부착 전에 새 지연 제약이 즉시 실행되는 fixture 오류였다. parent/input 두 lineage 제약만 생성 중 DEFERRED로 바꾸고 부착 후 IMMEDIATE를 확인하도록 수정했다. 현재 purge50+score1 전체 실행 중이다.

017을 init055/manifest/canonical suffix에 등록하고 SHA를 고정했다(아직 미커밋이므로 검증 중 SQL 변경 시 등록 SHA도 재검증 필요). 등록 RED14건 중 suffix 미등록1의 의도한 실패 → 등록 GREEN21건. 과거 #109 위치 cleanup 동시성 script는016에서 실행하고, 현재 guard/부모-input 경쟁은 별도 PG16/17 검사로 구분한다. Bash freshDB에 seed 이후 marker/zero 검사를 추가했다. 독립 검토에서 PowerShell 대응 검사 누락1건을 찾아 static RED 후 보완, 관련24PASS. 실제 PowerShell/Docker gate 완료는 아직 아니다.

생성·수정 각각 실제 commit과 두 세션 parent 잠금/input 삭제 경쟁, 삭제 rollback 후 input 보존과 부모 cascade를 검증하는 PG16/17 2건을 추가했다. 현재 실행 중인 purge suite에는 포함되지 않으며 다음 검사 대상이다. typed direct hash inventory19로 반영했다.


공통 static 전체 `/tmp/jeju-223-common-static.log`:825건 중822PASS/3SKIPPED,37.167s. `/tmp/jeju-223-deploy-policy.log` 배포 SQL 정책PASS. 실제 DB suite와 같은 SHA 전체gate를 대체하는 결과는 아니다.

현재-schema ScheduleRevisionRunSchemaIntegrationTest의 parent fixture도 input과 같은 transaction에 저장하도록 정렬했다. idempotency 두 세션 INSERT에서 INSERT=1인 연결만 input을 생성하며, 충돌0행에는 추가 input을 만들지 않는다. 신규 parent/input 경쟁과 이 fixture에 대한 독립 source review finding0(컴파일/실행 전, 공식승인 아님). direct typed hash inventory20/정적19PASS. generic geohash 기존 잔여량이 있는 PG16/17 migration은 데이터를 자동 삭제하지 않고 rollback해야 하는2건을 추가했으며 다음 실행 대상이다.


### 전체 purge GREEN

`/tmp/jeju-223-full-purge-score-green.log`: purge50 + score1 =51PASS, skip0/failure0/error0,40m23s. PG16/17에서 기존 모든 활성/모호성 중단·hash 재계산·raw/redacted 계보 삭제·generation/recovery 출력 정리·메시지 owner/trip·FK 복원/중간실패 rollback·opaque receipt gate를 현재017 전체로 확인했다. score의 지연 제약 fixture 수정도 통과했다. 이후 추가한 generic geohash 잔여량2건은 이51건에 포함하지 않으며 다음 실행 대상이다.


추가 suite `/tmp/jeju-223-concurrency-upgrade-current-schema.log` 실행 중 여행 삭제 fixture1건의 `compute input lineage required` RED를 확인했다. `JdbcTripMutationIntegrationTest`는 NOT_SUPPORTED로 실제 autocommit을 사용하므로 revision parent를 먼저 저장하던 fixture를 같은 TransactionTemplate의 parent+input+terminal 전이로 정렬했다. 기존 input contract `command/v1`을 부모의 `revision/v1`과 일치시켰다. queued generation도 부모+closed input을 같은 transaction에 저장하도록 변경했다. 현재 실행 중 bytecode에는 이 수정이 없으므로 종료 후 해당 class 재실행이 필요하다. 실제 private MCP2건은 환경 자격증명 부재로SKIPPED이며 연동 완료로 계산하지 않는다.

### MCP wire hash 독립성 추가 감사

- 기존 51건 통과 중 정상 command에 연결된 MCP 로그 보존 2건은 안전성을 입증하지 못하므로 해당 기대를 폐기한다. command hash와 wire hash는 서로 다른 입력에서 계산되며, MCP 0.7 revalidate의 optional current_position은 command의 location_supplied와 독립적이다. 나머지 통과 증거의 범위는 유지한다.
- 새 미분류 wire 차단 테스트 최초 실행은 recovery fixture의 닫힌 스키마 위반으로 실패했다. 이는 RED 증거로 사용하지 않는다. riskEventId/optionCount 계약으로 fixture를 수정하여 재실행한다.
- 같은 실행에서 JdbcTripMutationIntegrationTest 13건은 모두 통과했다. 실제 transaction으로 parent와 input을 함께 저장하는 fixture 수정이 확인됐다.
- 신규 fresh seed 테스트는 현재 스키마에 opaque MCP 로그를 공급하지 않으면서 정상 일정·입력 3건을 유지하는지 검증한다. historical pre-017 fixture와 현재 smoke fixture의 경계를 명시한다.

- `/tmp/jeju-223-wire-seed-red.log`: PG16/17 wire2는 차단 예외 부재, freshseed2는 MCP row 3 != 0으로 총4건 모두 의도한 RED다. fixture 오류는 없다.
- 017 owner-only verifier에 `unclassified_mcp_compute_call_logs`를 추가했다. 위치 부모와 함께 입증되어 제거한 로그 외 잔여 로그는 추정 삭제하지 않고 전체 transaction을 rollback한다. command hash/wire hash를 혼동하지 않는다.
- seed의 opaque MCP3은 marker 없는 pre017 QA에만 생성한다. 현재 seed는 정상 입력·일정을 유지하며 미분류 로그를 공급하지 않는다. 017 미커밋 SHA만 manifest에 갱신했고 선행 migration은 수정하지 않았다.
- 후속 runtime 조건: https://github.com/Timing-Jeju/jeju_BE/issues/224#issuecomment-5589962394 . 실제 wire argument의 위치 차단은 hash 생성 전이어야 하며 MCP_ENABLED=false 유지가 필요하다.

### 커밋 전 마지막 검증

- `/tmp/jeju-223-wire-seed-green.log`: PG16/17 wire 차단2, 현재 seed2, raw/redacted 위치 input·부모·MCP 정리4 =8PASS, failure/error/skip0,6m22s. 동일한 fixture로 RED4→GREEN을 확인했다.
- `/tmp/jeju-223-static-final.log`: scripts/tests825건 중822PASS/3SKIP. 별도 migration/hash/lock/smoke 정적36PASS, deploy SQL policy·shell syntax·secret scan·diff check PASS.
- 최신 command/wire 분리·zero verifier·seed·worker 상호작용의 독립 bounded source review 추가 finding0. 전체 동일 SHA gate/Docker와 공식 승인 전이므로 READY_FOR_REVIEW 또는 통합 완료로 판정하지 않는다.

### 최초 전체 gate의 fixture 회귀 수정

- `563904e` 공식 gate `/tmp/jeju-223-quality-563904e-solo.log`: 공통 검사·format·compile PASS, unit1351(failure0/skip9), slice56PASS. integration에서 여행 선호4건의 부모/input 누락과 운영 진단1건의401/400 차이를 확인하고 watchdog SIGINT로 중단했다. 공식 gate 성공 기록은 없다. 관련 disposable 프로세스/컨테이너 정리를 확인했다.
- 여행 선호의 활성 일정·점수 fixture는 실제 autocommit parent-only INSERT였다. parent와 LocationFreeComputeInputFixture를 같은 TransactionTemplate 안에서 저장하도록 고쳤다.
- `/tmp/jeju-223-preferences-operator-isolated.log`: 여행 선호14PASS, 운영 진단1PASS,1m1s. 진단 API 실패는 코드 수정 없이 단독실행에서 재현되지 않아 원인을 단정하지 않는다. 재발 시5개 응답 상태/HTTP version/고정 HTML 오류 여부만 보여주는 assertion 진단을 추가한다. body/header/token은 출력하지 않는다.
- 017은563904e에 커밋됐으므로 이후 수정하지 않는다. 이번 수정은 Java 테스트 fixture/진단뿐이며 새 커밋의 전체 gate를 다시 실행한다.

### 과거 seed를 사용하는 title-only upgrade fixture 정렬

- 공식 pre-push gate `/tmp/jeju-223-push-quality-07eeb54.log`에서 title-only upgrade가 pre009 seed의 opaque MCP audit3 때문에017에서 차단됐다. 정상 차단이므로 운영 SQL은 수정하지 않는다. 해당 전체 gate는 실패로 중단되어 push되지 않았다. watchdog 종료 뒤 남은 동일 실행의 worker/daemon도 identity를 확인해 종료하고 disposable 컨테이너 정리를 확인했다.
- 정상 일정 schema upgrade 테스트에서 자신이 직접 만든 고정UUID audit3만 제거하고 affected count3을 확인한다. suffix017 이후 residue 합계0 assertion을 추가했다. 미분류 MCP가 있으면 전체 rollback하는 별도 테스트는 유지한다.
- `/tmp/jeju-223-upgrade-title-green.log`: title-only upgrade1건이 내부에서PG16/17 모두 실행해PASS,1m48s. 독립 bounded source review finding0. 017immutable 유지.
- 별도 gate 이후 push 훅의 동일 검증이 중복되지 않도록 이후 검증은 공식 pre-push full gate로 수행한다. 새 SHA 전체 검사·Docker·원격반영은 아직 남아 있다.


## 2026-09-09 revision request hash 감사 보강 (진행 중)

- 4925927의 pre-push 전체 gate는 새 개인정보 감사 누락 발견으로 중단했다. gate 성공/원격 push/PR 승인을 주장하지 않는다. 해당 실행의 잔여 Gradle worker와 daemon만 종료했다.
- 정상 command input의 존재와 hash 일치는 독립 revision request hash의 비위치를 증명하지 못한다. PG16/17 회귀 RED 2건에서 기대 잔여 1건을 실제 0건으로 반환하는 누락을 확인했다. 로그: `/tmp/jeju-223-revision-hash-red.log`.
- 기존 커밋 017은 그대로 유지하고 018 verifier 보강을 추가했다. 017+018 개별 commit의 rollback 결함을 피하도록 checksum 고정 생성기와 단일 transaction SQL을 추가하고 기본 Docker/Java/PowerShell 경로를 연결 중이다.
- 생성기 RED 2건(모듈 부재) 후 정적 테스트 24건 통과. 로그: `/tmp/jeju-223-group-generator-red.log`, `/tmp/jeju-223-group-static.log`. DB 그룹 GREEN은 실행 중이며 아직 성공으로 기록하지 않는다.
- Supabase ledger까지 포함한 실제 적용 경로, timeout/연결 종료, 전체 fresh/upgrade와 동일 SHA 품질 gate는 남아 있다. READY_FOR_REVIEW 아님.

- PG16/17 verifier+017 전체 rollback 회귀 4건 PASS (`/tmp/jeju-223-revision-group-green.log`). 원본 017의 schema/ACL 및 테스트 대상 행 상태 복구와 marker 부재를 확인했다.
- 독립 초안 리뷰가 Bash/PS marker 기대와 canonical 수동 suffix loop 누락을 발견하여 018/group으로 수정했다. 수정 전 canonical 실행은 중단했으며 성공 증거로 사용하지 않는다.
- Supabase history 생성기 RED 1건 후 이력 preflight/최종 동일 transaction 등록 구현. 일반 CLI db push 대신 검증된 두 파일 그룹을 쓰는 계약으로 한정하며 실제 staging은 SKIPPED다. 합성 ledger와 canonical fresh/upgrade를 현재 검증 중이다.

- `/tmp/jeju-223-ledger-canonical-green.log` canonical fresh/origin 및 #50→#51 fingerprint 2건, Supabase 합성 이력 PG16/17 2건 PASS(총4). 이후 추가한 이력 INSERT 실패/부분이력/timeout/연결 종료는 별도 실행 중이다.
- `/tmp/jeju-223-group-all-static-fixed.log`: Python 정적 829건 중826PASS/3SKIP. canonical group mount와 중복 생성 SQL hash 호출 수 검사를 보강했다. 추가한 위치 revision 양성 회귀는 아직 DB 실행 전이다.

- `/tmp/jeju-223-ledger-interruption-green.log` PG16/17 이력 INSERT 실패·누락/부분이력·재실행 거부·중간 statement timeout·연결 종료 6건 PASS. 실패 시 위치 event/live 및 schema 복구를 확인했다. 잠금 timeout 분기는 이후 추가했으며 전체 검증에서 확인할 예정이다.
- 위치 revision 양성 fixture는 최초에 terminal 상태로 직접 INSERT하여 기존 queued 생성 규칙에 걸렸다(`/tmp/jeju-223-revision-location-positive.log`). 제품 결함 RED로 기록하지 않는다. queued 생성 후 허용된 failed 전이로 fixture를 수정하고 재실행 중이다.

- `/tmp/jeju-223-revision-location-positive-fixed.log` PG16/17 위치 계보가 입증된 종료 revision 정리/원본 일정 보존 2건 PASS. lifecycle 생성 규칙을 우회하거나 변경하지 않았다.
- 최종 잠금 timeout 분기를 포함한 interruption6건과 전체 정적 검사를 실행 중이다. 전체 품질 게이트·독립 정식 승인·실제 staging은 아직 미완료다.

- 최종 `/tmp/jeju-223-group-lock-interruption.log`: PG16/17 statement timeout·연결 종료·잠금 timeout 6건 PASS. `/tmp/jeju-223-group-final-static.log`:829건826PASS/3SKIP. 생성 SQL 두 종류의 `--check`와 diff 공백 검사 PASS.
- 이 증거는 수정된 소스의 집중 검증이며, 커밋 후 같은 SHA pre-push 공식 전체 품질 게이트와 독립 리뷰를 별도로 수행한다. 실제 staging/provider/native는 여전히 SKIPPED다.

## 2026-09-09 최종 fixture 및 Docker 정리 보강

HEAD717fbb0의 공식 gate는 unit1351(실패0/skip9), slice56, integration926(실패0/skip4, 1h50m26s), OpenAPI11, architecture48과 coverage/build를 통과했다. Docker health·fingerprint·legacy audits·두 세션·schema·negative·최종 fixture도 통과했다. 앞서 금지된 metadata 키가 append-only 검사보다 먼저 거부되던 입력은 허용된 source 값 변경으로 수정했으며017/018은 불변이다.

그러나 cleanup의 Docker 이미지 삭제 CLI가 5분 넘게 반환하지 않았다. 정확한 smoke 이미지 태그·container/network/volume project label 조회는 모두 성공/잔류0이었지만, 기존 gate의 성공 근거로 대체하지 않았다. 이번에 생성한 삭제 명령 PID51119/51120만 TERM으로 종료했다. gate는 cleanup 실패 및 push 실패로 종료했고 승인 기록은 생성하지 않았다.

새 `docker_cleanup_command.py`는 기존 watchdog의 process incarnation/group guard를 재사용한다. Unix cleanup의 명령마다30초 제한과 자식 그룹 종료 검증을 적용하며 stdout 임시 파일은 RLIMIT_FSIZE로64KiB를 넘지 못한다. 실행 중 그룹 재검증으로 부모 선종료 이전에 자식을 추적한다. 124는 실제 timeout 후 소유 그룹 종료가 입증된 경우에만 반환하며 native exit124는 일반 실패로 구분한다. 조회 실패·출력 초과·소유권 불확실성은 성공으로 취급하지 않는다.

shell cleanup의 모든 Docker 명령/조회가 제한된 실행기를 사용한다. 이미지 timeout은 container/network/volume/image 조회가 전부 성공·잔류0이고 다른 cleanup 실패가 없을 때에만 명시적 복구로 기록한다. 원래 본 작업 실패 코드는 항상 보존한다. Docker daemon이나 다른 프로젝트에 신호·prune을 수행하지 않는다. PowerShell의 기존 정리 경로는 이번 Unix CLI 응답 지연 보강 범위 밖이며 동등한 제한 시간이 구현됐다고 주장하지 않는다.

TDD: 실행기 부재 RED4 → GREEN4. 독립 리뷰의 부모 선종료 잔류 및 실행 중 출력 초과 처리 공백은 실제 RED2(자식 잔류, 초과 출력을124로 오인)로 확인한 뒤 수정했다. 신규 process/recovery 및 기존 isolation/layout 테스트 총39개 PASS(15.307s), shell 문법과 diff 검사 PASS. 실제 Docker focused 검증을 실행 중이며 최종 결과와 커밋 후 정규 전체 gate를 별도로 확인한다.

최종 focused 결과: `/tmp/jeju-223-bounded-cleanup-docker.log`의 실제 Docker smoke가 exit0으로 완료됐다. 이미지 삭제 응답 timeout을 감지하여 소유 프로세스를 종료한 뒤 네 종류 자원의 조회 성공·잔류0을 확인한 복구 메시지가 기록됐다. health·fingerprint·legacy audits·동시성·schema·negative·fixture 모두 통과했다. `/tmp/jeju-223-cleanup-all-static.log`는839개 중836 PASS/3 SKIP(47.114s)이다. 독립 source 재리뷰 추가 finding0. 커밋 후 같은 SHA 전체 gate와 공식 승인은 아직 별도 필요하다.

## 2026-09-09 post-merge 독립 리뷰 보정

- 최신 `origin/develop` `73cb2b93`에서 `fix/223-post-merge-location-guard`를 만들었다. 병합된 017·018 원문은 수정하지 않았다. #242 예약 019/057과 충돌하지 않는 forward migration 020/058을 선택했다.
- Issue 댓글로 설계를 먼저 기록하려 했으나 외부 보안 설계 게시가 실행 승인 계층에서 거부됐다. 우회하지 않고 이 개발일지와 runbook에 같은 경계를 기록했다.
- RED: Python group 계약 7건 중 3건이 020 부재, raw CLI enabled, version-only ledger로 실패했다. PG16 focused 6건은 독립 MCP/revision hash와 `current_position`/camelCase/중첩 좌표 배열 모두 예외가 발생하지 않아 실패했다.
- GREEN: 020이 generic JSON alias/두 numeric tuple helper를 보강하고 residue verifier와 신규 write guard가 같은 helper를 사용한다. typed provenance가 없는 동안 service_role의 revision/MCP 독립 hash INSERT 및 hash UPDATE는 SQLSTATE 23514로 차단한다.
- PG16 focused 6건 PASS. PG16/17 forward migration 2건은 legacy alias에서 schema/data/revision 전체 rollback, 정리 후 두 독립 hash INSERT/UPDATE 및 중첩 좌표 배열 차단을 확인했다. 공용 trigger의 서로 다른 row type field 해석으로 42703이 발생한 중간 Green 실패는 table별 trigger 분리 후 두 버전 모두 23514로 고정했다.
- 기존 017+018 Supabase ledger/최종 감사 rollback PG16/17 2건 PASS. ledger는 CLI 2.116.0 source의 `version`, `name`, `statements text[]` shape와 맞추되 statements에는 개별 실행을 가장하지 않는 고정 source checksum marker를 기록한다.
- raw Supabase sequential path는 `[db.migrations].enabled=false`이고 smoke runner가 CLI 실행 전에 exit 64로 거부함을 실제 실행으로 확인했다. 실제 Supabase CLI 설치·staging·provider/live 적용은 수행하지 않았다.
- 전체 Python 842건 실행에서 task 관련 migration inventory/hash count 결함은 보정했다. 나머지 9 failure/1 error는 sandbox의 `ps` 금지와 아직 미병합 PR #240의 collector exit 129 재현이며 이 branch에서 unrelated watchdog/cleanup 코드를 수정하지 않는다.
- 최종 재검증에서 `LocationProvenanceFailClosedMigrationIntegrationTest`와 전체 `LocationWriteGuardIntegrationTest`를 함께 PG16/17에서 실행해 3분 내 PASS했다. 유효한 typed command input hash fixture로 독립 hash UPDATE 거부까지 확인했다.
- 관련 Python 계약 116건, 두 generated SQL `--check`, shell 문법, manifest SHA-256(`ae34d8d5...595670`), `git diff --check`가 PASS했다. PowerShell fresh smoke의 최종 revision 기대도 020으로 정렬했다.
- #240은 2026-09-09 확인 시 OPEN이므로 요청한 자원/병합 선행 조건에 따라 전체 품질 게이트와 Docker 전체 smoke는 이번 post-merge 보정에서 실행하지 않았다. 따라서 집중 검증은 완료했지만 전체 DoD와 독립 Reviewer 승인은 아직 남아 있다.
