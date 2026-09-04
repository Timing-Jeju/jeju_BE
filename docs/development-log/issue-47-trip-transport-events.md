# Issue #47 항공·선박 이벤트 API 개발 일지

## 2026-09-01

- #45 → #68 → #180 스택 위에 `feat/47-trip-transport-events` 브랜치를 준비했다.
- First RED: `./gradlew --no-daemon test --tests '*TransportEvent*'`
  - application transport-event 타입과 target migration이 없어 test compile 31건이 실패했다.
- Green:
  - strict 7-property PUT, body 없는 selector DELETE, strong If-Match와 owner root lock을 구현했다.
  - canonical no-op, eventType upsert, active schedule invalidation, terminal trip와 stale writer 정책을 구현했다.
  - `20260907000004_trip_transport_event_contract.sql`로 exact XOR, canonical text, boundary date, writer privilege를 강화했다.
  - unit/controller/PostgreSQL/concurrency/migration-upgrade/OpenAPI 테스트를 추가했다.
- Refactor:
  - #86 Pydantic/MCP와 분리된 REST canonical contract를 OpenAPI projection 원본으로 재사용했다.
  - OpenAPI inventory를 27개로 올리고 TypeScript client Gate에 두 transport operation을 추가했다.
  - PostgreSQL `timestamptz`와 같은 microsecond 정밀도로 `scheduledAt`을 canonicalize해 nanosecond 입력의 no-op 판정을 안정화했다.
  - 같은 revision의 PUT/DELETE 경합에서도 한 writer만 성공하는 PostgreSQL 통합 테스트를 추가했다.
  - Docker schema contract가 calendar child trigger의 trip-plan mutex 누락을 검출해 `lock_trip_plan_schedule_mutex` 호출을 복원했다.
  - Docker smoke가 `authenticated` direct SELECT grant를 검출해 transport-event를 Spring server-only 읽기·쓰기 경계로 정렬했다.
- 보안:
  - request-time provider/MCP 호출이 없고 JWT·편명·terminal name·note·원천 payload를 로그/Problem Details에 남기지 않는다.

