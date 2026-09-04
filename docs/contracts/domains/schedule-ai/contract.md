# 일정 생성·AI 보정 비동기 API 계약

Issue #89의 로컬 canonical 계약은 [`contract.json`](contract.json)이다. 공통 인증·멱등성·Problem Details는 Issue #72의 `timing-jeju-rest-contract/v1`을 상속하고, immutable command snapshot은 종료된 선행 Issue #108을 사용한다. 이 문서는 Controller나 migration을 구현하지 않는다.

## 공개 endpoint와 생명주기

Spring Boot만 아래 여섯 endpoint를 공개한다.

| method | path | 성공 | 필수 요청 header |
|---|---|---:|---|
| POST | `/api/v1/trips/{tripId}/generation-runs` | 202 | `Authorization`, `Idempotency-Key` |
| GET | `/api/v1/trips/{tripId}/generation-runs/{runId}` | 200 | `Authorization` |
| POST | `/api/v1/trips/{tripId}/generation-runs/{runId}/candidates/{candidateId}/apply` | 200 | `Authorization`, `Idempotency-Key`, strong `If-Match` |
| POST | `/api/v1/trips/{tripId}/schedule-revision-runs` | 202 | `Authorization`, `Idempotency-Key` |
| GET | `/api/v1/trips/{tripId}/schedule-revision-runs/{runId}` | 200 | `Authorization` |
| POST | `/api/v1/trips/{tripId}/schedule-revision-runs/{runId}/candidates/{candidateId}/apply` | 200 | `Authorization`, `Idempotency-Key`, strong `If-Match` |

접수는 `queued`와 concrete `pollUrl`을 반환하고 동일 URL을 `Location`에, `2`를 `Retry-After`에 기록한다. 조회는 `queued/running`에서만 `Retry-After: 2`를 반환한다. running은 `startedPreDispatch`에서 `startedAt`만 반환하고, #52 MCP call log가 생긴 `postDispatch`부터 `mcpInputHash`도 반환한다. 상태는 `queued/running/succeeded/failed/cancelled`뿐이며 terminal은 불변이다. 후보 만료는 run status가 아니라 후보의 `expiresAt`이다. terminal 결과는 `completedAt`부터 7일, 후보는 성공 완료부터 24시간 보존한다. 경계 시각부터 조회는 `410 ASYNC_RESULT_EXPIRED`, 적용은 `410 CANDIDATE_EXPIRED`이다.

GET의 query는 closed empty `NoQuery`지만 body는 empty object나 `null`도 허용하지 않는 `BodyForbidden` sentinel이다. generation/revision GET은 각각 #95/#105가 소유하며 저장 결과만 SELECT한다. 결과에는 `baseScheduleVersionId`, `factsAsOf`, `stale`, `resultSource`, 후보가 필수다. 모든 후보는 concrete `applyUrl`을 제공하고, revision 후보는 typed added/removed/moved/updated diff와 preserved field path 목록을 추가로 제공한다.

FastAPI MCP는 private 계산기다. Spring이 JWT 검증, owner 판정, command snapshot, DB, worker lifecycle과 결과 적용을 소유한다. FastAPI는 공개 API, JWT, DB, provider credential을 소유하지 않는다.

#53/#69 접수 transaction은 queued run, idempotency record와 #108 snapshot을 원자 저장하면 worker 또는 private MCP가 내려가 있어도 `202`다. create의 `503 ASYNC_INTAKE_UNAVAILABLE`은 atomic commit 전 intake persistence 또는 durable queue admission 내부 장애에만 사용한다.

## 입력, hash와 동시성

generation 입력은 `targetDayId`, `candidateCount(1..10)`, `refreshExternalFacts` 세 필드를 모두 non-null로 요구한다. revision 입력은 `targetDayId`, 최대 100개의 unique `affectedItemIds`, 1..32개의 stable uppercase `instructionCodes`를 요구한다. unknown/null/omitted 필드는 거부한다.

`commandInputHash`는 Issue #108 `compute_run_inputs`의 versioned immutable structured snapshot digest다. `mcpInputHash`는 worker가 snapshot과 normalized facts로 만든 실제 redacted MCP wire input digest다. 두 필드는 alias가 아니며 재시작은 HTTP body나 mutable trip state가 아니라 snapshot만 읽는다.

create/apply의 key scope는 `canonicalSub + method + normalized path + Idempotency-Key`, 완료 TTL은 24시간이다. 같은 key/body는 저장된 response를 replay하고 다른 body는 `409 IDEMPOTENCY_KEY_REUSED`다. 처리 중 loser도 `Retry-After: 1`과 409를 받는다. active run unique arbiter와 apply의 trip lock/expected-active CAS가 동시 writer를 직렬화한다.

두 apply endpoint는 machine contract의 `firstMatchPrecedence`를 위에서 아래로 평가하고 최초 한 결과만 반환한다. 인증 누락·token 오류, path/body/key/`If-Match` 형식 오류, trip→run→candidate owner·domain·parent lineage 404 은닉, 완료 멱등 replay, 멱등 충돌, 이미 적용됨, run/candidate 상태 부적합, 만료 순이다. 그 뒤에만 여행 root를 잠그고 request/`If-Match` expected version과 locked active version의 불일치를 `ACTIVE_SCHEDULE_VERSION_CONFLICT`로 결정한다. 둘이 같을 때 candidate base가 다르면 `CANDIDATE_STALE`, 마지막 candidate schedule lineage·봉인 불변식 위반은 `CANDIDATE_NOT_APPLICABLE`이다. quota·bounded internal access failure는 앞선 결정 가능한 match가 없을 때만 각각 429·503이며, 모두 통과해야 apply가 성공한다.

따라서 candidate base=`A`, request expected=`A`, locked active=`B`이면 candidate stale보다 먼저 `ACTIVE_SCHEDULE_VERSION_CONFLICT` 하나만 반환한다. 같은 요청에서 candidate가 이미 적용됐고 만료·active mismatch·stale도 동시에 참이면 `CANDIDATE_ALREADY_APPLIED` 하나만 반환한다. 동일 idempotency scope의 완료 replay는 현재 candidate 상태를 다시 판정하지 않고 원래 200을 그대로 replay하며, registry conflict는 candidate 상태보다 먼저 `IDEMPOTENCY_KEY_REUSED`로 끝난다.

## owner, DB와 오류

owner 근거는 검증된 Supabase JWT의 canonical `sub`뿐이다. cross-owner trip/run/candidate는 모두 404로 은닉한다. `user_metadata`, email, raw token과 provider payload는 권한 입력이 아니다.

generation은 `itinerary_generation_runs`/`itinerary_generation_candidates`와 discriminator `itinerary_generation`, revision은 `schedule_revision_runs`와 discriminator `schedule_revision`을 사용한다. `compute_run_inputs`는 generation/revision parent 중 정확히 하나만 참조한다. 실제 FK 문자열과 schema gap은 machine contract에 고정했다. generation candidate 상태/만료는 #79, revision candidate는 #104, revision MCP log parent는 #69의 migration 범위이며 이 Issue는 schema를 변경하지 않는다.

후속 구현 owner는 generation 접수 #53·조회 #95·worker/후보 #79·적용 #54, revision 접수 #69·조회 #105·worker/후보 #104·적용 #81이다. 공통 worker lifecycle은 #74, durable command snapshot은 #108을 따른다.

오류는 공통 8필드 `application/problem+json`만 사용한다. machine contract의 exact condition matrix는 400/401/404/409/410/422/429/503과 한국어 `detail`, Spring 생성 `traceId`를 고정한다. failed/cancelled 조회의 `failure`는 `code/detail/retryable`만 공개하며 raw exception, prompt, MCP payload, token과 PII를 금지한다.

필수 인증 없음은 공통 계약과 동일한 `AUTHENTICATION_REQUIRED`, token 형식·서명·만료 오류는 `INVALID_ACCESS_TOKEN`이다. machine contract는 24개 code 각각에 발생 조건, type, title, 한국어 detail, fieldErrors, 적용 endpoint와 8필드 example을 둔다. 6개 endpoint는 path/query/header/body를 각각 closed typed schema로 참조하며 generation/revision candidate·result, 5개 상태 presence, apply response를 별도 DTO로 고정한다.

각 endpoint는 자신이 반환할 status별 code matrix를 별도로 가지며, 모든 condition의 endpoint group은 위 여섯 method/path의 부분집합이다. validator는 endpoint matrix와 condition scope를 양방향 비교하므로 공통 code가 과다·과소 노출될 수 없다.

failed/cancelled는 DB provenance와 `startedAt`/`mcpInputHash` presence를 discriminator로 쓰는 closed 3-way oneOf다. `preStart`는 두 필드가 모두 omitted이고, `startedPreDispatch`는 `startedAt`만 non-null required이며, `postDispatch`는 두 필드가 모두 non-null required다. #52 MCP call log의 부재/존재가 dispatch 경계이고, call log가 존재할 때만 그 row가 검증된 실제 MCP wire `mcpInputHash`를 소유한다.

## 추적성과 readiness

- authoritative local evidence: 이 문서, `contract.json`, `scripts/validate_schedule_ai_contract.py`, `scripts/tests/test_schedule_ai_contract.py`, GitHub Issue #89.
- Notion/Figma: `docs`와 `fixtures`의 전체 추적 파일명·내용에서 notion/figma/export/schedule-ai/generation/revision 근거를 read-only 탐색했다. 여섯 endpoint의 canonical Notion page ID/URL/version readback export는 없었다. 일반 Figma fileKey와 기능 매핑은 있지만 endpoint별 node/action/loading/empty/error/API contractVersion tuple은 없었다. 따라서 version을 추정하지 않고 `not-linked`다.
- 외부 evidence blocker: Notion 6행의 page ID·canonical URL·method/path·`1.0.0` readback, Figma endpoint별 fileKey/node/action/loading/empty/error/contractVersion 연결이 필요하다.
- local: `ready`; Metadata/Example/Implementation: 모두 `not-ready`. 외부 링크, fixture와 Spring 구현 증거가 모두 존재하기 전에는 승격하지 않는다.

`python3 scripts/validate_schedule_ai_contract.py`는 duplicate key, 여섯 identity, 전체 canonical semantic digest, catalog alignment, schema owner 문자열과 local evidence 실재를 fail-closed로 검사한다.
