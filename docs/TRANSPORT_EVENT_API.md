# Issue #47 항공·선박 이벤트 API·FE 인계

## State

- 기능 브랜치: `feat/47-trip-transport-events`
- 선행 스택: #45 → #68 → #180
- 공개 API: transport-event PUT/DELETE 2개
- 생성 OpenAPI: 27 operations, frontend-readiness `--mode 27`
- FE 소스 변경: 없음
- 외부 provider·FastAPI MCP 호출: 없음

## Wire contract

| Method | Path | 성공 | 필수 header |
| --- | --- | ---: | --- |
| PUT | `/api/v1/trips/{tripId}/transport-event` | 200 | `If-Match` |
| DELETE | `/api/v1/trips/{tripId}/transport-event?eventType=arrival\|departure` | 200 | `If-Match` |

PUT은 `eventType`, `transportType`, `terminalPlaceId`, `customTerminalName`, `scheduledAt`, `transportNumber`, `note` 일곱 property를 모두 요구한다. terminal ID와 custom name은 정확히 하나만 non-null이다. `scheduledAt`은 RFC 3339 `+09:00`이며 arrival은 여행 시작일, departure는 종료일이어야 한다. DELETE는 body를 허용하지 않고 `eventType` query를 정확히 한 번 받는다.

성공 body는 common mutation field와 `eventType`, `deleted`, nullable `event`를 가진 closed response다. 새 ETag는 response header로만 전달하며 body에 중복하지 않는다. canonical no-op PUT은 기존 ETag, revision, trip/event timestamp와 active schedule을 보존하고 `scheduleEffect=maintained`를 반환한다.

```json
{
  "tripId": "47000000-0000-4000-8000-000000000047",
  "scheduleEffect": "none",
  "regenerationRequired": false,
  "activeScheduleVersionId": null,
  "tripStatus": "draft",
  "updatedAt": "2026-09-01T09:00:00+09:00",
  "eventType": "arrival",
  "deleted": false,
  "event": {
    "eventType": "arrival",
    "transportType": "flight",
    "terminalPlaceId": "47000000-0000-4000-8000-000000000048",
    "customTerminalName": null,
    "scheduledAt": "2026-09-01T09:00:00+09:00",
    "transportNumber": "KE1001",
    "note": null
  }
}
```

## Transaction and database rules

- owner predicate가 포함된 trip root를 `FOR UPDATE`로 잠그고 strong ETag revision을 검사한다.
- `(trip_plan_id,event_type)` unique 행을 생성 또는 완전 교체한다.
- 실제 변경은 revision을 한 번 증가시킨다. active schedule이 있으면 version을 `superseded`, pointer/score를 null, trip을 `draft`로 같은 transaction에서 바꾼다.
- 같은 revision의 동시 writer 중 한 요청만 성공하고 다른 요청은 `409 TRIP_VERSION_CONFLICT`다.
- completed/cancelled/failed 여행은 `409 TRIP_TERMINAL_STATE_CONFLICT`다.
- migration은 terminal exact XOR와 canonical 문자열·boundary date를 강화하며 legacy 충돌을 삭제·추측 보정하지 않는다.
- `anon`과 `authenticated`에 direct write를 허용하지 않고 server writer만 INSERT/UPDATE/DELETE한다.

## FE handoff

1. trip 상세 또는 직전 mutation의 strong ETag를 화면 객체 밖 side metadata로 보존한다.
2. 비행기는 canonical 제주국제공항 place UUID를 사용할 수 있다. 선박은 정확한 항만 place UUID 또는 custom terminal name을 요구한다.
3. FE arrival/departure time을 일정 시작 가능 시각·terminal 도착 마감 시각으로 그대로 전송하며 임의 체크인 buffer를 빼지 않는다.
4. `scheduleEffect=invalidated`면 기존 active DayReview를 확정 상태로 유지하지 않는다.
5. 409는 최신 trip을 재조회한 뒤 사용자 intent를 다시 적용한다.
6. transport number, terminal name, note를 URL·로그·Problem Details에 복사하지 않는다.

OpenAPI와 TypeScript release artifact는 다음으로 생성한다.

```bash
cd services/spring-api
./gradlew openApiDocs
cd ../..
./scripts/generate_frontend_api_client.sh
```

결과는 `services/spring-api/build/frontend-api-client`와 `services/spring-api/build/distributions/timing-jeju-frontend-api-client.tgz`이며 FE 저장소에 자동 복사하지 않는다.
