# Issue #68 복수 숙소 CRUD 구현·FE 인계

## State

- 기준 HEAD: `763c43f02360f778fa97462c0d54cef7d5f632a0` (#45 완료 브랜치)
- 기능 브랜치: `feat/68-trip-accommodations-crud`
- 공개 API: 숙소 POST/PATCH/DELETE 3개
- 생성 OpenAPI: 통합 27 operations, frontend-readiness `--mode 27`
- FE 소스 변경: 없음
- 외부 provider 호출, TMAP 원문·geometry, 사용자 원문 저장·로그 추가: 없음

Pydantic/MCP 일정 계약과 연결하기 전, BE의 숙소 identity와 날짜·시간을 canonical trip aggregate로 저장하는 단계다. 숙소명은 ASCII trim 후 NFC로 정규화하며 `placeId/customName`은 정확히 하나만 존재한다. `placeId`는 사용 가능한 canonical `tour_places`만 허용한다.

## Wire contract

| Method | Path | 성공 | 필수 동시성·재시도 header |
|---|---|---:|---|
| POST | `/api/v1/trips/{tripId}/accommodations` | 201 | `Idempotency-Key`, `If-Match` |
| PATCH | `/api/v1/trips/{tripId}/accommodations/{accommodationId}` | 200 | `If-Match` |
| DELETE | `/api/v1/trips/{tripId}/accommodations/{accommodationId}` | 204 | `If-Match` |

`If-Match` 형식은 정확히 `"trip-{lowercase canonical UUID}-r{positive revision}"`이다. missing/malformed header는 `400`, 다른 trip 또는 stale revision은 `409 TRIP_VERSION_CONFLICT`다. owner predicate를 root lock에 포함하므로 타 소유자는 `404`다.

POST body는 `placeId`, `customName`, `checkInDate`, `checkOutDate`, `checkInTime`, `checkOutTime` 여섯 필드가 모두 필요하다. 날짜는 strict `YYYY-MM-DD`, 시간은 strict `HH:mm`이며 서버 timezone은 `Asia/Seoul`이다. request에 sequence, time/cost/distance/risk 또는 provider payload를 받지 않는다.

PATCH는 위 여섯 필드 중 하나 이상을 받는다. 생략은 보존한다. 날짜와 시간은 null일 수 없고 identity를 바꿀 때만 새 identity 값과 기존 identity의 명시적 null을 함께 보낸다. canonical no-op은 200이지만 revision과 timestamp를 변경하지 않는다.

성공 POST/PATCH body는 다음 closed wrapper다.

```json
{
  "tripId": "68000000-0000-4000-8000-000000000068",
  "accommodationId": "68000000-0000-4000-8000-000000000069",
  "accommodation": {
    "accommodationId": "68000000-0000-4000-8000-000000000069",
    "placeId": null,
    "customName": "제주알호텔",
    "name": "제주알호텔",
    "checkInDate": "2026-09-10",
    "checkOutDate": "2026-09-12",
    "checkInTime": "15:00",
    "checkOutTime": "11:00",
    "sequenceNo": 1
  },
  "scheduleEffect": "none",
  "regenerationRequired": false,
  "activeScheduleVersionId": null,
  "tripStatus": "draft",
  "etag": "\"trip-68000000-0000-4000-8000-000000000068-r2\"",
  "createdAt": "2026-09-01T14:00:00+09:00",
  "updatedAt": "2026-09-01T14:00:00+09:00"
}
```

## Transaction and database rules

- #50fix canonical `TripAggregateMutationCoordinator`가 owner-scoped trip root `SELECT ... FOR UPDATE`, expected revision/terminal status 검사와 root CAS를 단독 소유한다.
- 숙소 구간은 trip 범위 안의 `[checkInDate, checkOutDate)`다. 내부 구간은 gap과 overlap 없이 연속이어야 한다.
- canonical order는 `checkInDate`, `checkOutDate`, `accommodationId` 오름차순이며 sequence를 같은 transaction에서 1..N으로 압축한다.
- create/patch 응답과 저장된 idempotency replay snapshot의 `sequenceNo`도 compact 이후 canonical index와 일치한다.
- 실제 POST/PATCH는 trip revision을 정확히 한 번 증가시킨다.
- 실제 변경에 active schedule이 있으면 version `superseded`, pointer/score null, trip `draft`를 원자 반영한다.
- active schedule이 있는 DELETE는 `ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE`로 차단한다.
- 같은 revision의 동시 PATCH 두 개 중 하나만 성공한다.
- POST 멱등 scope는 owner+trip+key이며 24시간 보존한다. 같은 canonical body는 첫 transaction 완료까지 기다린 뒤 원래 201 snapshot을 replay한다.
- 완료된 replay snapshot은 DB trigger로 변경할 수 없다.
- POST의 accommodation idempotency advisory lock과 marker `FOR UPDATE`는 replay-before-stale 직렬화를 위해 store에 유지한다.
- migration은 identity/time 불변조건을 어긴 legacy row를 추측 보정하거나 삭제하지 않고 `legacy accommodation contract conflict`로 fail-closed한다.

## FE handoff

1. trip 상세 또는 직전 mutation의 `ETag`를 화면 객체 밖 side metadata로 보존한다.
2. 화면의 숙소 선택을 canonical BE place UUID 또는 custom name으로 변환한다. 동명이인 장소를 이름만으로 병합하지 않는다.
3. KST `HH:mm`을 그대로 전송한다. 서버가 check-in/out 기본값을 임의 생성하지 않는다.
4. 409이면 최신 trip을 다시 읽고 사용자 intent를 재적용한다. 서버 응답을 로컬 mock route 시간과 병합하지 않는다.
5. `scheduleEffect=invalidated`면 기존 DayReview를 확정 상태로 표시하지 않고 이후 generation flow를 다시 시작한다.
6. FE 코드는 이 변경에 포함되지 않았다. 실제 adapter 연결은 별도 FE 변경에서 수행한다.

OpenAPI 생성과 TypeScript release artifact:

```bash
cd services/spring-api
./gradlew openApiDocs
cd ../..
./scripts/generate_frontend_api_client.sh
```

결과는 `services/spring-api/build/frontend-api-client`와 `services/spring-api/build/distributions/timing-jeju-frontend-api-client.tgz`다. 생성 client는 통합 27개 operation과 숙소 3개 typed SDK 함수를 포함하며 FE 저장소에 자동 복사하지 않는다.

## Evidence and gaps

- application unit: normalization, XOR, PATCH presence/null, delete delegation
- controller integration: 인증, strict path/header/body/query, response header/media, Problem mapping
- PostgreSQL integration: replay/hash conflict, gap/overlap/outside rollback, no-op, identity switch, delete edge/middle, active invalidation/delete, concurrent replay/stale writer
- migration upgrade integration: invalid legacy row 보존과 target migration fail-closed
- OpenAPI integration: closed schemas, examples, exact 27-operation inventory
- canonical fixtures/validator: accommodations v1과 #45 strong ETag 정렬

## Landing order

- 최종 통합은 **#50fix 먼저, #68 다음** 순서다.
- #50fix가 먼저 반영되면 #68에 exact 재통합한 coordinator 9개 파일은 중복이므로 landing diff에서 drop하고, `JdbcAccommodationStore`의 coordinator 주입과 accommodation 전용 plan/오류 번역만 유지한다.
- 반대 순서로 병합하거나 coordinator를 두 구현으로 유지하지 않는다.

#68은 독립 Reviewer 승인과 `develop` 병합 전이다. 이 문서의 Codegen READY는 wire artifact 검사를 뜻하며 배포 승인이나 FE 실제 호출 완료를 뜻하지 않는다.
