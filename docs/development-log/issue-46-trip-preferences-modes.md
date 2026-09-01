# Issue #46 여행 선호·교통수단 API 개발 기록

## State

- 기준 브랜치: `origin/develop`
- 기준 SHA: `ae3926ac6428c1d93cd57372fbafb8dd31d34544`
- 작업 브랜치: `feat/46-trip-preferences-modes`
- 구현 범위: `PUT /api/v1/trips/{tripId}/preferences`
- canonical 계약: `docs/contracts/domains/preferences-transport/contract.json` 1.0.0
- 제외 범위: 장소 희망·회피(#48), 항공·선박 이벤트(#47), FE 소스 변경, MCP 호출

## Evidence

### Red

운영 코드를 추가하기 전에 application service, controller, PostgreSQL store 테스트를 순서대로 추가했다.

```text
./gradlew --no-daemon test --tests com.timingjeju.api.application.trip.TripPreferencesServiceTest
```

`TripPreferencesService`, command/model/port가 없어 compile error 26건으로 실패했다.

```text
./gradlew --no-daemon test --tests com.timingjeju.api.domain.trip.controller.TripPreferencesControllerIntegrationTest
```

세 요청이 모두 route 부재로 404를 반환했다.

```text
./gradlew --no-daemon test --tests com.timingjeju.api.domain.trip.adapter.JdbcTripPreferencesStoreIntegrationTest
```

`TripPreferencesStore` bean 부재로 6개 테스트가 Spring context 단계에서 실패했다.

### Green

- 강한 `If-Match`를 검증하고 7개 필드를 전체 교체하는 application service를 추가했다.
- owner 조건의 `FOR UPDATE`와 현재 ETag 비교를 같은 transaction에서 수행한다.
- 선호와 1~3개 교통수단을 원자 교체하고, 실제 변경만 active schedule을 `superseded`로 전환한다.
- 동일 canonical PUT은 timestamp와 active pointer를 유지하며 `scheduleEffect=maintained`를 반환한다.
- 시작·종료 장소는 `tour_places`를 `FOR KEY SHARE`로 확인하고 없는 참조를 404로 닫는다.
- 종료 상태 여행, stale ETag, 중복 선호, 비연속 priority와 primary 위반을 계약 Problem으로 변환한다.
- 요청/응답은 unknown field를 허용하지 않으며 start/end place만 명시적 null을 허용한다.
- 인증 없는 변경 요청은 service 호출 전 401로 거부한다.
- Swagger에 closed schema, 강한 `If-Match`, 응답 ETag, 성공·오류 예시를 투영한다.

검증 결과:

```text
./gradlew --no-daemon clean check
BUILD SUCCESSFUL
test: 1586, failures 0, errors 0, skipped 8
integrationTest: 473, failures 0, errors 0, skipped 2

python3 scripts/validate_preferences_transport_contract.py
여행 선호·교통 이벤트 계약 검사 성공

python3 scripts/validate_rest_contracts.py
REST 계약 readiness 검사 성공

python3 -m unittest scripts.tests.test_preferences_transport_contract scripts.tests.test_contract_suite_integration
Ran 20 tests - OK
```

### 생성 OpenAPI mode 21 승격

첫 clean commit `b0d4a3d8b476f280c51c94f58d357461964bc65a`에 전체 품질 게이트를 실행하자 FE readiness가 fail-closed했다.

```text
PUT /api/v1/trips/{tripId}/preferences
- operationId가 stable suffix 규칙과 불일치
- 200 ETag가 response header inventory에 없음
- public inventory allowlist 밖 endpoint
```

먼저 mode 21 inventory, source provenance, canonical authority projection과 closed `allOf` 합성 테스트를 추가했다. 최초 실행은 새 operation 상수 부재 ImportError와 gate의 세 오류, 이어 실제 artifact에서 canonical composition 비교 3건 실패를 재현했다.

Green에서는 historical mode 16/20을 보존하면서 mode 21에 #46 endpoint와 #86 clean contract SHA를 추가했다. operationId를 `tripPreferencesUpdate`로 고정하고 request `If-Match`, response `ETag`, 503 runtime projection을 manifest와 Swagger에 연결했다. canonical `allOf + unevaluatedProperties=false`는 필드 손실 없이 동등한 flat closed schema로 비교한다.

```text
python3 -m unittest scripts.tests.test_openapi_frontend_readiness
Ran 23 tests - OK

python3 scripts/validate_openapi_frontend_readiness.py \
  services/spring-api/build/openapi/openapi.json --mode 21
OpenAPI frontend-readiness 검사 성공: 21 operations
```

## 보안·데이터 결정

- 사용자 JWT는 controller 보안 경계에서만 사용하며 persistence나 하위 외부 호출로 전달하지 않는다.
- 요청 원문은 저장하거나 로그로 남기지 않는다.
- 기존 `raw_answers`는 선호 upsert마다 빈 JSON 객체로 고정한다.
- cross-owner와 없는 여행은 같은 404로 응답해 소유권 정보를 노출하지 않는다.
- 변경 전 active schedule 전환, pointer 제거, trip 상태 변경을 하나의 transaction으로 묶고 강제 실패 rollback 테스트로 증명했다.
- 외부 데이터 소스, TMAP/TAGO 원문, geometry, 정밀 위치를 사용하거나 저장하지 않는다.

## Gaps

- canonical `preferences-transport` 도메인은 #47과 #48 구현이 남아 있으므로 전체 readiness는 승격하지 않는다.
- FE aggregate `planner-conditions`는 후속 Issue에서 이 service를 조합한다.
- 독립 Reviewer 승인과 PR 생성은 Developer 범위 밖이며 수행하지 않는다.

## Risks

- 전역 OpenAPI 정책이 인증 endpoint에 403과 500을 추가하므로 이 operation의 문서 응답은 canonical 도메인 6종과 전역 2종을 합친 8종이다.
- 활성 일정이 있는 조건 변경은 의도적으로 기존 일정을 무효화하므로 FE는 `regenerationRequired`를 확인해야 한다.
- 같은 장소명이 아닌 UUID를 사용해야 동명이인 장소를 안전하게 구분할 수 있다.
