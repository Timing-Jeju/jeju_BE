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
