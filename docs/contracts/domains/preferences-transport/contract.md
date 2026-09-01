# 여행 선호·교통 이벤트 REST 계약 v1.0.0

Issue #86의 canonical 상세 계약은 [`contract.json`](contract.json)이다. 모든 endpoint는 Spring Boot가 소유하고 검증된 Supabase JWT의 canonical `sub`로 여행 owner를 판정한다. 타 사용자 여행은 `404 TRIP_NOT_FOUND`로 숨기며 token, 이메일, `user_metadata`, provider payload는 계약·응답·로그에 사용하지 않는다. 공개 FastAPI API와 Flyway는 이 문서 범위가 아니다. #47은 transport-event 소유 범위의 운영 Supabase migration을 추가한다.

## endpoint와 쓰기 의미

| Method | Path | 구현 owner | 입력 | 성공 |
| --- | --- | --- | --- | --- |
| PUT | `/api/v1/trips/{tripId}/preferences` | #46 | 7개 필드 전체, 강한 `If-Match` | `200 PreferencesResponse` |
| PUT | `/api/v1/trips/{tripId}/place-preferences` | #48 | `items` 전체, 강한 `If-Match` | `200 PlacePreferencesResponse` |
| PUT | `/api/v1/trips/{tripId}/transport-event` | #47 | event 전체, 강한 `If-Match` | `200 TransportEventMutationResponse` |
| DELETE | `/api/v1/trips/{tripId}/transport-event?eventType=arrival|departure` | #47 | body 없음, 강한 `If-Match` | `200 TransportEventMutationResponse` |

`implementationIssues`는 `[46,47,48]`이며 각 endpoint의 `dbOwner`는 위 구현 owner 하나만 참조한다. DELETE의 `eventType` query는 별도 구현 endpoint로 세지 않고 #47의 transport-event 삭제 계약에 포함한다. [`ownership.json`](../../../fixtures/contracts/preferences-transport/ownership.json)은 이 endpoint→Issue projection과 readiness의 canonical JSON SHA-256을 보존한다. validator는 owner 누락·복수 표기·미등록 Issue·endpoint 불일치와 digest drift를 fail-closed로 거부한다. 이 정렬은 구현 책임 메타데이터만 변경하며 request/response schema, status, Problem Details와 data lineage는 바꾸지 않는다.

두 선호 PUT은 부분 upsert가 아니라 전체 교체다. `preferences`의 배열은 빈 배열로 지울 수 있지만 누락과 `null`은 거부한다. `startPlaceId`와 `endPlaceId`만 명시적 `null`을 허용한다. 교통수단은 `public_transit/rental_car/taxi` 중 1~3개이며 mode와 priority가 중복되지 않고 priority가 `1..N`으로 연속이어야 한다. primary는 정확히 한 건이고 priority 1이다.

장소 선호는 `must_visit/avoid`만 허용하고 같은 place가 어느 type으로든 두 번 나타나면 `422`다. `targetDayNo`는 property 자체는 필수지만 전체 여행에 적용할 때 `null`, Day를 지정할 때 `1..tripDayCount`다. priority tie는 `priority DESC, placeId ASC`로 결정한다.

교통 이벤트는 `eventType=arrival|departure`, `transportType=flight|ferry`다. `scheduledAt`은 RFC 3339 `+09:00`을 명시하고 제주 `Asia/Seoul`로 해석한다. arrival은 여행 `startDate`, departure는 `endDate`에 있어야 한다. `terminalPlaceId`와 `customTerminalName`은 정확히 하나만 존재해야 한다. PUT은 `(tripId,eventType)`을 upsert하고 DELETE는 query의 eventType 한 건만 제거한다.

## 일정 상태와 동시성

모든 변경은 현재 여행의 강한 ETag를 `If-Match`로 받는다. stale writer는 `409 TRIP_VERSION_CONFLICT`다. canonical 값이 같으면 no-op이며 active 일정은 유지된다. 값이 바뀌고 active 일정이 없으면 `scheduleEffect=none`, `regenerationRequired=false`다. active 일정이 있으면 같은 transaction에서 active version을 `superseded`로 바꾸고 `activeScheduleVersionId`를 비우며 여행 상태를 `draft`로 돌린다. 이때 `scheduleEffect=invalidated`, `regenerationRequired=true`다. DELETE도 body 없는 `204`가 아니라 이 신호를 담은 `200`을 반환한다.

세 성공 응답은 공통 `MutationResponse`를 문자열로 암시하지 않는다. 실제 `allOf`의 `{"$ref":"MutationResponse"}`와 endpoint 고유 child schema를 합성하고 최상위 `unevaluatedProperties=false`로 닫는다. 따라서 공통 필드와 endpoint 고유 필드는 모두 필수이며, fixture에 계약 밖 필드가 추가되거나 어느 필드든 누락되면 거부한다.

## 오류·외부 추적성

오류는 #72의 `application/problem+json`과 정확한 `type,title,status,detail,instance,code,traceId,fieldErrors`를 상속한다. endpoint별 matrix는 설명 문자열이 아니라 canonical error code를 직접 참조하며, 각 code는 condition과 한국어 problem fixture에 양방향으로 정확히 한 번 연결된다. 세 PUT에서 non-null 장소 참조가 없으면 `404 PLACE_NOT_FOUND`, DELETE selector에 이벤트가 없으면 `404 TRANSPORT_EVENT_NOT_FOUND`다. 두 오류의 canonical occurrence URI도 `urn:timing-jeju:problem:{traceId}`로 고정한다. request-time 외부 API 또는 MCP 호출은 없다.

Notion의 네 행은 page ID를 유지하면서 singular `/transport-event`, contract version `1.0.0`, `Implementation Ready`로 맞춘다. Figma에서는 `329:5165`, `182:3248`, `653:11512`, `329:4975`의 action/state를 실제 관찰했다. 하지만 Figma 자체에 API contract version과 loading/empty/error response 연결이 없으므로 `figma=not-linked`, aggregate catalog readiness는 과장하지 않고 모두 `not-ready`다. #47의 두 transport-event endpoint에는 Controller, PostgreSQL, migration, OpenAPI 테스트가 추가됐지만 #46/#48 구현과 외부 화면 상태 연결까지 완료됐다는 뜻은 아니다.

## 발견한 schema 후속 범위

#47의 `20260907000000_trip_transport_event_contract.sql`은 terminal exact XOR, 문자열 canonical 경계, arrival/startDate·departure/endDate를 강화한다. 충돌하는 legacy row는 삭제하거나 추측 보정하지 않고 `legacy transport event contract conflict`로 적용을 중단한다. active 일정 무효화는 Spring의 owner root lock, event mutation, schedule supersede, trip revision CAS 한 transaction으로 검증한다. 남은 schema 후속 범위는 #46 preferences와 #48 place-preferences이며 운영 migration 기준은 계속 `supabase/migrations`이고 Flyway는 도입하지 않는다.
