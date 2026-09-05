# 복수 숙소 CRUD REST 계약 v1.0.0

Issue #87의 canonical 상세 계약은 [`contract.json`](contract.json)이다. 세 endpoint는 Spring Boot가 소유하고 검증된 Supabase JWT의 canonical `sub`로 여행 owner를 판정한다. 다른 사용자의 여행·숙소와 다른 여행의 `accommodationId`는 모두 `404`로 숨긴다. 공개 FastAPI API, Controller/Service/Repository 구현, schema migration과 Flyway는 이 범위가 아니다.

## endpoint와 입력

| Method | Path | 입력 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/v1/trips/{tripId}/accommodations` | 6개 body field, `Idempotency-Key`, 강한 `If-Match` | `201 AccommodationMutationResponse` |
| PATCH | `/api/v1/trips/{tripId}/accommodations/{accommodationId}` | 최소 1개 body field, 강한 `If-Match` | `200 AccommodationMutationResponse` |
| DELETE | `/api/v1/trips/{tripId}/accommodations/{accommodationId}` | body 없음, 강한 `If-Match` | `204` body 없음 |

POST는 `placeId/customName` 두 property를 모두 보내되 정확히 하나만 non-null이어야 한다. PATCH에서 누락은 기존 값 유지이고, `null`은 반대 identity를 같은 요청에서 non-null로 설정할 때 losing identity를 지우는 용도로만 허용한다. 적용 결과는 항상 XOR이다. `checkInTime/checkOutTime`은 `Asia/Seoul` local wall-clock `HH:mm`이며 offset 없는 다른 timezone 의미로 해석하지 않는다.

POST의 `Idempotency-Key`는 1~128자의 printable ASCII(`U+0020`~`U+007E`)이며 scope는 canonical sub + method + path + tripId, TTL은 24시간이다. 같은 payload replay는 최초 `201` status/body/ETag를 재사용한다. 같은 key의 다른 payload는 `409 IDEMPOTENCY_KEY_REUSED`, 동일 key 동시 요청은 최초 transaction 종료까지 기다린 뒤 replay한다. 모든 변경은 여행 aggregate의 강한 ETag를 `If-Match`로 검사하고 stale writer는 `409 TRIP_VERSION_CONFLICT`다.

## 기간, coverage와 복수 순서

숙소 구간은 `[checkInDate, checkOutDate)`이고 `checkInDate < checkOutDate`, 양 끝은 여행의 `[startDate, endDate]` 안에 있어야 한다. 저장된 둘 이상의 숙소는 앞 숙소 `checkOutDate ==` 다음 숙소 `checkInDate`여야 하므로 내부 gap과 overlap을 허용하지 않는다. 첫·마지막 숙소 바깥의 여행 시작·종료 edge는 draft 입력 중 비어 있을 수 있고, 일정 생성 시 필요한 전체 숙박 coverage는 후속 생성 계약이 재검증한다. 이 경계로 첫 숙소를 저장하고 인접 숙소를 순차 추가할 수 있다.

client는 `sequenceNo`를 입력하지 않는다. 서버는 `checkInDate ASC, checkOutDate ASC, accommodationId ASC`로 정렬하고 같은 transaction에서 `1..N`으로 재번호한다. 결정적으로 발견한 범위 밖·날짜 역전·내부 gap·overlap은 `422 ACCOMMODATION_DATE_GAP_OR_OVERLAP`이다. 사전 검증 뒤 동시 write가 exclusion/sequence 경계에서 충돌하면 raw DB 오류 대신 `409 ACCOMMODATION_CONCURRENT_CONFLICT`다.

DELETE는 중간 숙소 제거로 내부 gap이 생기면 `422`다. edge 숙소는 active 일정이 없을 때 삭제하고 남은 행을 재번호할 수 있다. active 일정이 있으면 숙소 item의 의미를 보존하기 위해 `422 ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE`로 삭제를 거부한다. POST/PATCH의 실제 값 변경은 active version을 `superseded`로 바꾸고 pointer를 지우며 trip을 `draft`로 되돌리는 작업을 같은 transaction에서 수행하고 `scheduleEffect=invalidated`, `regenerationRequired=true`를 반환한다. canonical no-op PATCH는 active 일정과 ETag를 바꾸지 않는다.

## 오류와 응답

오류는 #72의 `application/problem+json` exact field `type,title,status,detail,instance,code,traceId,fieldErrors`를 상속한다. `instance`는 `urn:timing-jeju:problem:{traceId}`이며 raw path/query, token, 이메일, `user_metadata`, provider payload를 반사하지 않는다. endpoint matrix는 canonical error condition과 한국어 fixture에 양방향으로 연결된다.

성공 응답과 nested accommodation은 `additionalProperties=false`, 모든 response field required로 닫는다. 날짜, 시간, UUID, `+09:00` date-time, enum, nullable을 재귀 fixture 검사한다. DELETE만 `204` body 없음이다. request-time 외부 API/MCP 호출은 없다.

## 외부 추적성과 schema gap

Notion 세 행은 기존 page ID를 유지하고 계약 `1.0.0`, `Implementation Ready`와 endpoint별 body/header/status/error 계약으로 맞춘다. Figma `329:5165`, `182:3248`, `653:11512`에서 숙소 입력 field와 검색·지도 선택 action은 직접 관찰했지만 숙소 삭제 UI/action은 관찰되지 않았다. 따라서 DELETE endpoint의 Figma node는 `not-observed`, action은 `not-linked`로 기록한다. 복수 CRUD의 loading/empty/error 및 API contract version 연결도 관찰되지 않아 Figma는 `not-linked`, catalog readiness 세 단계는 모두 `not-ready`다.

현재 DB CHECK는 `place_id/custom_name` 둘 다 non-null을 허용해 XOR가 아니고, exclusion은 overlap만 막는다. XOR migration과 gap/sequence/active 삭제 application transaction은 #68에서 구현한다. 운영 public schema의 단일 기준은 `supabase/migrations`이고 Flyway를 도입하지 않는다.
