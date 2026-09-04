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

## 2026-09-04 독립 리뷰 보정

- Red:
  - `./gradlew --no-daemon unitTest --tests '*JdbcTransportEventStoreArchitectureTest'`는 no-active 응답 변환 경계가 없어 `responseScheduleEffect(String)` compile error 3건으로 실패했다.
  - mode29 literal inventory와 generator 테스트는 validator가 29-operation mode를 지원하지 않아 20개 endpoint를 allowlist 밖으로 판정하고 generator의 `--mode 27`을 검출해 실패했다.
- Green:
  - coordinator의 `none`은 canonical no-op `maintained`, 실제 변경의 `maintained`는 no-active `none`, `invalidated`는 그대로 응답하도록 repository 경계를 분리했다.
  - historical mode27을 보존하면서 transport-event PUT/DELETE만 추가한 active mode29를 validator, generator, verifier, 양 플랫폼 quality gate와 runtime manifest에 연결했다.
  - 독립 literal 29개 map으로 누락과 extra operation을 모두 거부하고 PUT/DELETE의 서로 다른 404 대표 code를 고정했다.
- 검증:
  - Java DB-free unit 7건, Python 관련 48건, OpenAPI frontend-readiness 29 operations와 preferences-transport validator가 통과했다.
  - 지시된 제한에 따라 실제 DB, Testcontainers, Docker, live Supabase와 full heavy gate는 실행하지 않았다.

### 재리뷰 MINOR 문서 정합성 보정

- Red: `python3 -m unittest scripts.tests.test_openapi_frontend_readiness.OpenApiFrontendReadinessTest.test_frontend_인계문서는_항공선박을_포함한_exact29로_표현한다`가 stale `#68 숙소 CRUD를 합친 27개 operation의 프론트엔드 인계본`을 검출해 1건 실패했다.
- Green: 프론트엔드 인계 문장을 #47 항공·선박 이벤트까지 포함한 exact 29개 operation으로 정렬하고 같은 독립 literal assertion이 통과했다.
- 검증 범위: 관련 Python 문서/contract 테스트와 git diff-check, pre-commit만 실행했으며 실제 DB, Testcontainers, Docker, live Supabase와 full heavy gate는 제외했다.
