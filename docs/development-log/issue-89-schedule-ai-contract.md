# Issue #89 일정 생성·AI 보정 비동기 API 계약 개발 일지

## 2026-08-26

### Preflight

- Issue #89 OPEN, 선행 #72/#108 CLOSED를 확인했다.
- fetch 후 `origin/develop` `39ed577f4c2b839177faea0ab774e8d3102ed988`에서 `docs/89-c08-api-contract` 독립 worktree를 만들었다.
- 기존 #89 branch/worktree/PR은 없었다. canonical worktree의 다른 작업과 미추적 파일은 건드리지 않았다.
- 운영 Java, DB migration, Notion/Figma, 승인 상태와 PR은 범위에서 제외했다.

### Red

운영 계약보다 `scripts/tests/test_schedule_ai_contract.py`를 먼저 추가했다. 테스트는 6 endpoint identity, lifecycle/expiry, header, Problem matrix, owner, DB discriminator/FK, poll/retention, idempotency/concurrency, 두 hash, readiness mutation을 각각 거부하도록 작성했다.

```text
python3 -m unittest scripts.tests.test_schedule_ai_contract
exit 5
FileNotFoundError: docs/contracts/domains/schedule-ai/contract.json
FAILED (errors=1)
```

의도한 schedule-ai canonical 계약 부재로 실패했다.

### Green

- `docs/contracts/domains/schedule-ai/contract.json`과 사람이 읽는 `contract.md`를 추가했다.
- REST catalog에 담당 6 endpoint만 추가했다.
- `scripts/validate_schedule_ai_contract.py`가 duplicate JSON key, exact semantic digest, 6 route/status, catalog alignment, #89 readiness, local evidence 실재, 실제 migration discriminator/FK와 schema gap을 검사한다.
- generation/revision은 `compute_run_inputs`의 `itinerary_generation`/`schedule_revision` discriminator와 exact-one-parent를 사용하며 `commandInputHash`와 `mcpInputHash`를 분리했다.
- Notion/Figma는 외부 authoritative evidence가 없으므로 `not-linked`, metadata/example/implementation은 `not-ready`로 유지했다.

```text
python3 scripts/validate_schedule_ai_contract.py
Issue #89 schedule-ai 계약 검증 통과

python3 -m unittest scripts.tests.test_schedule_ai_contract \
  scripts.tests.test_contract_suite_integration \
  scripts.tests.test_rest_contract_readiness
Ran 65 tests in 4.773s — OK

python3 scripts/validate_rest_contracts.py
REST 계약 readiness 검사 성공
```

### Refactor

- Linux/Windows 품질 게이트에 같은 validator를 연결하고 contract suite가 40개 catalog endpoint와 validator 실행을 exact 검사하게 했다.
- 전체 계약 semantic digest로 임의 field/value/list/order mutation을 fail-closed 처리하면서, 오류 메시지는 traceback 없이 반환하도록 책임을 분리했다.
- 실제 schema 부재는 기능을 발명하지 않고 후속 owner로 기록했다: generation candidate status/expiry #79, revision candidate/FK/apply arbiter #104, revision MCP call-log parent #69, worker lifecycle #74.
- 공개 흐름 owner는 generation #53/#95/#79/#54, revision #69/#105/#104/#81로 endpoint별 분리했다.
- 비밀정보 검사와 `git diff --check`를 통과했다. 부모 요청에 따라 full quality gate, Docker, push, 승인 상태와 PR은 실행하지 않는다.

### Reviewer MAJOR 보완

- Red: common auth 양방향 정렬, closed typed schema, generation/revision DTO, 상태별 presence, 모든 Problem condition/example, 8개 owner, injected catalog와 unrelated endpoint non-impact 테스트를 먼저 추가했다. 전용 18개에서 failures 4/errors 2를 확인했다. 원인은 `MISSING_ACCESS_TOKEN`, schema/result/problemConditions 부재, validator의 `--catalog` 미지원이었다.
- Green: missing token을 `AUTHENTICATION_REQUIRED`로 바로잡고 20개 closed object schema, 5개 상태 response presence, 24개 exact Problem condition/example을 추가했다. catalog 6행 전체 객체를 canonical projection으로 주입 비교하고 첫 행의 모든 field mutation을 거부하되 unrelated endpoint mutation은 통과시켰다. endpoint 구현 owner 8개와 공통 #108/#74 mapping도 exact 검사한다.
- Refactor Red/Green: Problem condition의 endpoint scope가 alias만 있고 endpoint 자체에 code matrix가 없음을 단일 테스트 `KeyError: endpointGroups`로 재현했다. 여섯 실제 method/path로 닫힌 group과 각 endpoint의 status/code matrix를 추가하고 condition scope와 양방향 비교해 전용 20/20을 통과시켰다. catalog mutation은 이후 6행 각각의 모든 field로 확대했다.
- 외부 evidence: `docs`·`fixtures` 전체 tracked 파일과 관련 filename/content를 read-only로 탐색했다. Issue #89 여섯 Notion 행의 page ID/URL/version export와 Figma endpoint별 node/action/loading/empty/error/version tuple은 없었다. 일반 Figma file/function reference는 endpoint evidence가 아니므로 `not-linked/not-ready`를 유지했다.

### Reviewer 재검토 보완

- #95/#105와 #53/#69를 `gh issue view`로 다시 읽고 결과 readback과 intake 격리 acceptance criteria를 로컬 계약에 투영했다.
- Red: DTO provenance/apply URL, terminal oneOf, intake-only 503, forbidden body와 owner binding 테스트를 먼저 추가했다. 전용 25개에서 failures 3/errors 4를 확인했다. terminal example도 별도 단일 테스트에서 `failedPreMcp` 부재 KeyError로 실패했다.
- Green: generation/revision result에 base schedule, factsAsOf, stale을 추가하고 후보별 concrete apply URL, revision diff/preserved fields를 typed required로 고정했다. #95/#105 readback owner와 6 endpoint owner를 implementation owner에 양방향 연결했다.
- failed/cancelled는 pre-MCP의 `startedAt/mcpInputHash` omitted·null 금지와 post-start의 두 필드 required를 oneOf로 분리하고 양쪽 example을 검사한다.
- create 503은 `ASYNC_INTAKE_UNAVAILABLE`로 바꿔 intake persistence/queue admission 실패만 허용했다. worker/private MCP unavailable은 durable commit이 가능하면 202다. GET body는 `{}`와 null도 받지 않는 `BodyForbidden`이며 catalog 두 GET ref도 exact 동기화했다.
- 외부 Notion/Figma evidence 상태는 변하지 않았고 `not-linked/not-ready`를 유지했다.

### Reviewer 최종 terminal provenance 보완

- #52 call-log 경계를 다시 읽고 terminal을 `preStart`, `startedPreDispatch`, `postDispatch`로 나누는 테스트를 먼저 추가했다. focused 3개에서 failure 1/error 2로 Red를 확인했다. 기존 2-way discriminator, payload validator 부재, DB provenance mapping 부재가 원인이었다.
- `startedPreDispatch`는 `startedAt`만 허용하고 `mcpInputHash`를 omitted한다. `postDispatch`는 #52 MCP call log가 소유하는 lowercase 64-hex `mcpInputHash`와 `startedAt`을 모두 요구한다. 세 단계의 failed/cancelled example과 DB presence mapping을 exact validator로 고정했다.
- 외부 Notion/Figma evidence blocker와 `not-linked/not-ready` 상태는 변하지 않았다.

### Reviewer running projection 보완

- running의 started-only polling을 허용하는 conditional oneOf와 terminal 전이 테스트를 먼저 추가해 focused 2개가 `runningStateVariants`와 payload validator 부재로 error 2건인 Red를 확인했다.
- running을 `startedPreDispatch`(`startedAt` required, `mcpInputHash` omitted)와 `postDispatch`(두 필드 required)로 닫았다. 같은 DB/call-log provenance를 유지하며 polling 중 failed/cancelled 전이도 dispatch 전후 presence가 보존되고 post-dispatch hash loss는 거부된다.
- 외부 Notion/Figma evidence blocker와 `not-linked/not-ready` 상태는 변하지 않았다.

## 2026-09-04 최신 develop 재통합

- `REMOTE_SETUP_MODE=apply`로 `origin/develop`을 fetch하고, 최신 `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`에서 `docs/89-c08-api-contract-reintegrate` 전용 worktree를 새로 만들었다. 기존 #89 브랜치의 재통합 commit은 섞지 않고 #89 전용 source commit의 9개 파일만 의미 단위로 이식했다.
- 새 브랜치에서도 테스트를 먼저 추가했다. `python3 -m unittest scripts.tests.test_schedule_ai_contract -v`는 네 테스트 모두 `docs/contracts/domains/schedule-ai/contract.json` 부재의 `FileNotFoundError`로 실패했다. Red commit은 `9fdc66c`이며, 최종 테스트는 같은 요구를 더 세밀한 mutation 계약으로 refactor했다.
- 최신 catalog의 기존 40개 endpoint와 #89의 6개 endpoint를 합쳐 exactly 46개로 유지했다. #113 push endpoint와 다른 domain validator를 보존했고, catalog 중복·누락·범위 밖 추가도 거부한다.
- 외부 live Notion/Figma를 호출하지 않았고 repository 안에도 여섯 endpoint의 authoritative readback이 없다. 양쪽 version은 `not-linked`, metadata/example/implementation은 `not-ready`, evidence는 `null`로 유지하며 local/source-ready만 주장한다.
- 운영 Java, DB schema/migration, 실제 DB, PostgreSQL/Testcontainers, Docker, live Supabase/Notion/Figma와 전체 heavy gate는 부모 승인 범위에 따라 실행하거나 변경하지 않는다.

### Reviewer MAJOR — apply first-match precedence

- Red: 두 apply endpoint가 겹치는 오류의 ordered precedence를 갖는지, 그리고 candidate base=`A`/request=`A`/locked active=`B`와 already-applied·expired·stale 중첩을 하나의 public code로 축약하는지 테스트를 먼저 추가했다. focused 3개는 `firstMatchPrecedence` 부재 `KeyError` 3건과 `resolve_apply_overlap` 부재 `ImportError` 1건으로 실패했다. Red commit은 `fc23bed`다.
- Green: 양 endpoint에 동일한 exact first-match 순서를 추가했다. 인증→형식→trip/run/candidate 404 은닉→완료 replay/registry conflict→already-applied→run/candidate status→expiry→locked active CAS→candidate base stale→lineage/봉인→429/503→success 순서다.
- 겹침 결과는 완료 replay가 현재 candidate 문제보다 우선하고, fresh request에서는 already-applied가 status/expiry/CAS/stale보다 우선한다. already-applied가 아니면 status, expiry, locked active CAS, candidate stale, lineage 순으로 최초 하나만 선택한다. candidate base=`A`, request=`A`, locked active=`B`는 `ACTIVE_SCHEDULE_VERSION_CONFLICT`; already-applied까지 겹치면 `CANDIDATE_ALREADY_APPLIED`다.
- validator는 두 endpoint의 배열을 exact 비교하며 순서 reverse mutation을 거부한다. 테스트 oracle도 인증·형식·404 은닉·멱등·candidate 상태·잠금 후 CAS·stale/lineage 중첩을 단일 결과로 검증한다.

## 2026-09-11 #223/#239 이후 계약 최신화

- 기존 #89 source 5개 commit을 보존한 채 `origin/develop` `06785b9a`와 pending #223 exact HEAD `7473dd6f`를 순서대로 merge해 stacked ancestry를 명시적으로 확보했다.
- Red: printable ASCII idempotency key, 위치 무수신 closed policy, 저장된 Day 활동 시간 pair, 최신 교통 이벤트 snapshot을 먼저 테스트했다. focused 4개는 기존 UUID schema 2 failures와 신규 policy 부재 3 errors로 실패했다.
- Green: create/apply `Idempotency-Key`를 1..128 printable ASCII로 고정했다. 현재·간접 위치는 받지 않고 사용자 직접 선택 `regionCode/placeId/tripItemId`만 허용하며 `tripItemId`는 owner 여행 소속만 사용한다. 위치성 field와 unknown field는 거부한다.
- #239의 `trip_days.activity_start_time/activity_end_time` 저장 pair는 target Day generation snapshot에 그대로 보존한다. pair 부재는 명시적 absent이며 임의 기본값을 사용자 사실로 기록하지 않는다.
- #47/#180 최신 공개 계약에 맞춰 arrival/departure nullable slot, flight/ferry, terminal XOR, +09:00 scheduledAt과 transport metadata를 snapshot에 고정했다. `commandInputHash`와 실제 redacted MCP wire의 `mcpInputHash` 분리는 유지했다.
- endpoint status/error와 apply first-match precedence는 변경하지 않았다. 운영 Java/DB schema도 변경하지 않았다.
- 실제 Notion/Figma read/write/readback은 수행하지 않았으며 외부 evidence는 계속 `not-linked/not-ready`다. Docker/Testcontainers/full gate/PR/live Supabase는 이번 #89 범위에서 실행하지 않는다.

### Reviewer 최종 Problem 계약 보완

- Red: `IDEMPOTENCY_KEY_INVALID`의 condition/detail/fieldErrors/example과 header schema 정렬, GET `NoQuery`/`BodyForbidden` 위반의 canonical 400 code와 condition scope, endpoint matrix 양방향 mutation을 테스트로 먼저 추가했다. focused 2개에서 failures 3/errors 1을 확인했다. 기존 Problem은 canonical UUID를 요구했고 두 GET의 400 matrix에는 path 오류만 있었다.
- Green: invalid key Problem을 #68의 1..128 printable ASCII로 통일했다. 정상 path라도 query가 존재하면 `INVALID_QUERY_PARAMETER`, `{}`나 `null`을 포함해 body가 존재하면 `REQUEST_BODY_NOT_ALLOWED`를 두 GET에서 반환하도록 exact condition/example과 `GET_2` scope를 추가했다.
- Refactor: validator가 header policy와 invalid-key Problem을 직접 비교하고 GET 거부 code/status/condition/scope를 고정한다. 기존 condition-to-endpoint matrix 양방향 검사를 유지해 한쪽만 바뀌는 계약 drift를 거부한다.

## 2026-09-13 최종 플랜 재통합

- 최신 `origin/develop` `ef8aee5964ecb0b2cbb265783f14537e5ad85e2b`에서 새 작업 트리를 만들고, 기존 원격 #89 브랜치의 관련 없는 위치 제거·PostgreSQL 변경은 병합하지 않았다. #89 전용 커밋만 순서대로 이식했다.
- First RED: generation 접수의 Trip `If-Match`, 정확히 3개 후보, 최초 생성/apply의 nullable active version, TMAP 원본·상세 geometry·사용자 원문 비영속 정책을 요구하는 한글 설명 테스트 4개를 먼저 추가했다. focused 실행은 failure 1/error 3으로 실패했다.
- 최소 GREEN: 공개 generation 경로를 `/schedule-generations`로 정렬하고, request를 `targetDayId`, nullable `expectedActiveScheduleVersionId`, `candidateCount=3`의 closed object로 고정했다. 성공은 세 전략을 한 번씩 포함한 후보 3개, 실패는 `insufficient_feasible_routes`와 후보 0개뿐이다. 후보에 `strategy`, `feasibility`, `scheduleUrl`, `applyUrl`을 필수화했다.
- 최초 생성의 `baseScheduleVersionId`와 generation/apply expected active는 `null`을 허용한다. generation create는 strong `If-Match`와 `Idempotency-Key`를 함께 요구한다.
- TMAP 원본·상세 geometry·사용자 원문은 영속 저장·로그 금지, TMAP 프로세스 메모리 TTL은 최대 23시간 50분, provider 파생 경로 수치 durable 저장은 금지로 닫았다. #216이 더 넓은 필드별 보존 근거를 승인하기 전에는 후보 24시간 보존과 재시작 복구를 동시에 충족할 수 없으므로 #79/#54 운영 구현은 차단 상태다.
- focused 검증은 validator, #89/contract-suite/rest readiness 99개, REST validator, `git diff --check`가 모두 통과했다. Notion/Figma readback, Spring runtime, DB migration, Docker, staging은 완료로 표시하지 않는다.

### TMAP process-bound 후보 설계 보완

- First RED: TMAP 후보 본문의 23시간 50분 미만 TTL, 재시작 시 복원 금지, apply의 live payload/승인 projection 이중 조건을 한글 설명 테스트 3개로 먼저 고정했다. focused 실행은 failure 1/error 2였다.
- 후보 payload는 AI 프로세스 메모리에만 두고 최대 `P23H50M` 유지한다. Spring의 7일 run 보존은 비민감 metadata 전용이며 후보 본문 보존을 뜻하지 않는다.
- queued/running lease는 immutable command snapshot으로 재계산할 수 있다. succeeded 뒤 process restart·eviction·payload loss가 발생하면 이전 TMAP 결과를 DB나 로그에서 복원하지 않고 `410 CANDIDATE_EVIDENCE_UNAVAILABLE`로 새 생성을 요구한다.
- apply는 아직 살아 있는 후보 payload와 #216에서 승인된 durable projection을 모두 요구한다. 승인 전 기본 rollout은 `feature_flag_off`다.
# 2026-09-13 독립 리뷰 후속 수정

- 최초 null-base 적용에는 Trip strong ETag를 사용하고 nullable 활성 버전을 별도로 검증한다.
- 접수 If-Match 누락·형식 오류·Trip stale·활성 버전 충돌과 적용 Trip stale 오류를 정의했다.
- transport command snapshot에서 note/customTerminalName/transportNumber 원문을 제외했다.
- 후보는 planExpiresAt 및 참조 evidence의 기존 만료 중 최솟값을 상속한다. 시간 duration 표기는 PT23H50M이다.
- 최초 RED: Trip ETag가 기존 schedule UUID 전용 pattern을 통과하지 못했다. GREEN: 계약 46개 테스트 및 REST validator 통과.
- #89의 Notion/Figma readback, 동일 SHA 전체 게이트, 공식 사람 리뷰는 아직 미완료다. #216 durable projection 승인도 미완료이므로 전체 구현 완료 또는 병합 승인을 의미하지 않는다.
# 2026-09-13 순차 후속: 생성 전용 실행 예산

- 공통 RunExecutionPolicy의 기본 deadline 60초와 lease 30초를 확인했다.
- First RED: 한글 목적 테스트를 추가해 executionPolicy 누락 KeyError를 확인했다
  (47개 중 1개 오류). AI 150초 실행과 현재 기본값이 호환된다고 가정하지 않았다.
- GREEN 대상: 생성 전용 recommend/evaluate 도구명, 시도당 recommend 1회,
  요청 timeout/lease 최소 165초, fencing heartbeat, 평가·저장을 포함한 deadline 계약.
- 운영 Java, 공통 기본값, DB, UI, source 승인 상태는 변경하지 않았다.
- #216 승인 및 #89 외부 readback/전체 gate/독립 리뷰는 여전히 완료 조건이다.
