# 관심 장소 CRUD API 계약

Issue #84가 확정하는 Spring 공개 API 계약입니다. machine 기준은 같은 디렉터리의 `contract.json`이며 공통 인증·cursor·멱등성·Problem Details는 Issue #72의 `timing-jeju-rest-contract/v1`을 상속합니다. 구현 소유자는 Issue #34입니다.

## 소유 경계와 readiness

- 공개 API, 검증된 Supabase JWT, 소유권, DB와 응답은 Spring Boot가 소유합니다.
- 권한 principal은 canonical JWT `sub`만 사용합니다. `user_metadata`, 이메일, provider payload, raw token을 권한 판단·응답·로그에 사용하지 않습니다.
- FastAPI는 이 API를 공개하거나 DB에 접근하지 않습니다. 이 Issue에 Python source·dependency·MCP tool을 추가하지 않습니다.
- 로컬 canonical contract version은 `1.0.0`, 현재 Notion source spec은 `v1.1`입니다. 서로 다른 버전 체계이므로 같은 증거로 간주하지 않습니다.
- Notion 4개 행은 아직 `/api/v1/saved-places`와 `Spec Status=Ready`를 사용하고, Figma는 API contract version과 loading/empty/error 상태를 명시하지 않았습니다. 따라서 Notion·Figma는 `not-linked`, metadata/example/implementation은 모두 `not-ready`입니다.

## endpoint 요약

| Method | Path | 성공 | 인증 | 핵심 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/me/saved-places` | 200 | Required | owner 목록, tag/sort/cursor/size |
| POST | `/api/v1/me/saved-places` | 201 또는 중복 동일 상태 200 | Required | `Idempotency-Key` 필수 |
| PATCH | `/api/v1/me/saved-places/{placeId}` | 200 | Required | `If-Match` 필수, 부분 변경 |
| DELETE | `/api/v1/me/saved-places/{placeId}` | 204 | Required | 반복·타 사용자 모두 404 |

`placeId`는 소문자 canonical UUID입니다. 목록 외 endpoint는 query를 받지 않습니다. DELETE는 request·response body가 없습니다.

## 목록 cursor·정렬·tag

`tag`, `sort`, `cursor`, `size`는 optional이지만 입력하면 null일 수 없습니다.

- `tag`: trim+Unicode NFC 후 1~50자이며 배열 원소 exact membership으로 필터합니다.
- `sort`: `saved_at_desc`(기본), `priority_desc`, `target_day_asc`만 허용합니다.
- `cursor`: 1~2048자의 opaque 값입니다. 내부 구현·offset을 노출하지 않습니다.
- `size`: 기본 20, 1~100입니다.

정렬은 다음과 같고 모든 profile의 마지막 tie-breaker는 `place_id ASC`입니다.

1. `saved_at_desc`: `saved_at DESC, place_id ASC`
2. `priority_desc`: `priority DESC, saved_at DESC, place_id ASC`
3. `target_day_asc`: `target_day ASC NULLS LAST, saved_at DESC, place_id ASC`

cursor scope는 canonical `sub`, 정규화한 `tag`, `sort`, `size`입니다. 발급 뒤 하나라도 바뀌면 `400 CURSOR_CONTEXT_MISMATCH`, decode·서명·형식이 잘못되면 `400 INVALID_CURSOR`입니다. `hasNext=true`일 때만 `nextCursor`가 non-null이고 `hasNext=false`이면 반드시 null입니다.

## 저장 생성과 멱등성

POST body에서 `placeId`만 required/non-null입니다.

| 필드 | 생략 | null | 값 |
| --- | --- | --- | --- |
| `memo` | null 저장 | null 저장 | trim 후 1~2000자 |
| `tags` | `[]` 저장 | `[]` 저장 | 전체 배열 canonical set 저장 |
| `priority` | 0 저장 | 0 저장 | 0~5 |
| `targetDay` | null 저장 | null 저장 | 1~365 |

태그는 각 원소를 trim+NFC하고 빈 값과 중복을 거부한 뒤 Unicode code point 오름차순으로 저장·응답합니다. 최대 20개, 원소당 최대 50자입니다.

`Idempotency-Key`는 `[A-Za-z0-9._:-]{1,128}`이고 scope는 `canonicalSub + POST + canonical path`, terminal TTL은 24시간입니다.

- 최초 생성: 201, `Idempotency-Replayed: false`, `Location`, strong `ETag`.
- 같은 key+canonical payload: 최초의 status/header/body를 정확히 replay합니다. 이 계약에서는 201과 `Idempotency-Replayed: true`입니다.
- 같은 key+다른 payload: `409 IDEMPOTENCY_PAYLOAD_CONFLICT`.
- 다른 key지만 같은 owner/place와 현재 payload까지 동일: 200 current resource, replay=true.
- 다른 key와 다른 payload: `409 SAVED_PLACE_ALREADY_EXISTS`; 현재 ETag를 읽고 PATCH해야 합니다.
- 동시 같은 command는 한 요청만 실행하고 나머지는 대기 후 replay합니다. 서로 다른 key가 같은 owner/place를 만들면 DB unique race 뒤 위 규칙으로 결정합니다.

canonical payload는 `placeId + normalized memo + canonical tags + effective priority + effective targetDay`입니다.

## PATCH presence·null·replace·동시성

PATCH body는 `memo`, `tags`, `priority`, `targetDay` 중 최소 하나가 있어야 합니다.

| 필드 | omitted | null | non-null |
| --- | --- | --- | --- |
| `memo` | 유지 | clear | 전체 replace |
| `tags` | 유지 | `[]`로 reset | merge가 아닌 전체 array replace |
| `priority` | 유지 | 0으로 reset | 전체 replace |
| `targetDay` | 유지 | clear | 전체 replace |

`If-Match` strong ETag가 필수입니다. ETag는 `placeId`와 `updatedAt` 기반의 opaque 값이며 현재 owner row와 다르면 `409 SAVED_PLACE_VERSION_CONFLICT`입니다. 성공은 새 ETag와 200 body를 반환합니다. `savedAt <= updatedAt`을 항상 만족합니다.

## DELETE와 소유 리소스 은닉

첫 owner DELETE는 body 없는 204입니다. 같은 요청을 반복하면 404입니다. 대상이 없거나 다른 canonical `sub` 소유여도 동일한 `404 SAVED_PLACE_NOT_FOUND`로 응답하여 존재 여부를 은닉합니다. owner 판단 전에 사용자 입력이나 이메일을 조회하지 않습니다.

## 성공 응답

`SavedPlace`는 아래 11개 필드를 항상 포함하는 closed object입니다.

- required/non-null: `placeId`, `name`, `category`, `tags`, `priority`, `savedAt`, `updatedAt`
- required/nullable: `regionLabel`, `thumbnailUrl`, `memo`, `targetDay`
- `thumbnailUrl`은 값이 있으면 absolute HTTPS URI입니다.
- 시각은 timezone을 포함한 RFC 3339 date-time입니다.

목록은 `items`와 `page={size,hasNext,nextCursor}`를 반환합니다. 알 수 없는 property, JSON duplicate key, `NaN`/`Infinity`, boolean을 integer로 사용한 값, 잘못된 URI/date-time/UUID는 계약 검사에서 거부합니다.

## 오류 matrix

| endpoint | status | code | 조건 |
| --- | --- | --- | --- |
| 목록 | 400 | `INVALID_QUERY_PARAMETER` | query 타입·enum·범위·정규화 오류 |
| 목록 | 400 | `INVALID_CURSOR` | cursor decode·서명·형식 오류 |
| 목록 | 400 | `CURSOR_CONTEXT_MISMATCH` | owner/filter/sort/size scope 변경 |
| 전체 | 401 | `AUTHENTICATION_REQUIRED` | Authorization 없음 |
| 전체 | 401 | `INVALID_ACCESS_TOKEN` | token이 유효하지 않음 |
| 생성 | 404 | `PLACE_NOT_FOUND` | 장소가 없거나 저장할 수 없음 |
| 변경·삭제 | 404 | `SAVED_PLACE_NOT_FOUND` | 없음·반복 삭제·타 사용자 소유 |
| 생성 | 409 | `IDEMPOTENCY_PAYLOAD_CONFLICT` | 같은 key에 다른 payload |
| 생성 | 409 | `SAVED_PLACE_ALREADY_EXISTS` | 다른 key로 다른 현재 값 중복 저장 |
| 변경 | 409 | `SAVED_PLACE_VERSION_CONFLICT` | If-Match 불일치 |
| 생성·변경 | 422 | `SAVED_PLACE_CONSTRAINT_VIOLATION` | 정규화 후 도메인 제약 위반 |

오류는 `application/problem+json`의 `type,title,status,detail,instance,code,traceId,fieldErrors`만 반환합니다. 사용자에게 보이는 `title/detail`은 한국어이며 token, 이메일, 원본 provider payload를 포함하지 않습니다.

## DB 계약과 발견된 schema drift

현재 `saved_places`에는 `(user_id, place_id)` partial unique index와 `tags text[]` GIN index가 있습니다. owner scope는 canonical JWT `sub = saved_places.user_id`입니다. session 소유 행은 이번 `/me` API 대상이 아닙니다.

문서 검토에서 다음 drift를 확인했습니다.

1. owner SELECT RLS만 있고 INSERT/UPDATE/DELETE DML RLS 정책이 없습니다.
2. `priority`는 NOT NULL/default 0이지만 API의 `priority 0~5` 상한 CHECK가 없습니다.
3. `target_day`는 양수만 검사하고 API의 365 상한은 없습니다.
4. `updated_at` 자동 갱신과 ETag용 atomic compare-update 구현을 Issue #34에서 검증해야 합니다.

이 Issue는 schema migration을 만들지 않습니다. 위 항목은 후속 구현 Issue #34의 명시적 migration·repository·통합 테스트 범위입니다. `supabase/migrations`가 단일 기준이며 Flyway는 마지막 안정화 전까지 도입하지 않습니다.

## Figma 추적성

- 시작 node: `251:4347`
- `329:4937`: 장소 상세 / 관심 등록. 메모, 태그, 관심 등록 action을 확인했습니다.
- `329:4975`: 관심 장소 보관함. 목록, filter, Day 일정 입력 선택 action을 확인했습니다.
- API contract version과 loading/empty/error frame은 확인되지 않았습니다. 확인 전 `not-linked/not-ready`입니다.

## Notion 정렬 patch

다음 4개 페이지는 로컬 계약 병합 전 `Spec Status=Draft`로 낮추고 legacy `/api/v1/saved-places`를 `/api/v1/me/saved-places`로 정정해야 합니다.

- GET: `3a40a87c-7ce5-816e-8462-c2fcbf087f2e`
- POST: `3a40a87c-7ce5-8190-af66-ecffaebb8fad`
- PATCH: `3a40a87c-7ce5-8167-a0d6-e296da1170de`
- DELETE: `3a40a87c-7ce5-81c2-a950-f026488e013c`

각 페이지의 최신 섹션에는 이 문서의 endpoint별 query/header/body/presence/status/problem/owner/concurrency 계약, `canonical contractVersion=1.0.0`, `sourceSpecVersion=v1.1`, 구현 owner `#34`를 반영해야 합니다. 적용·재조회·Figma 상태 근거가 모두 끝나기 전 catalog version은 `not-linked`입니다.

## 검증

```bash
python3 -m unittest scripts.tests.test_saved_places_contract
python3 scripts/validate_saved_places_contract.py
python3 scripts/validate_rest_contracts.py
```
