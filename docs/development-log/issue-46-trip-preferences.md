# Issue #46 여행 선호·교통수단 API 개발 기록

## 범위와 기준

- 재통합 기준: `origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56`
- 참고 구현: `609db7ef5732a5471c8ebd3746e28915c5c088ce`
- 브랜치: `fix/46-trip-preferences-reintegrate`
- 포함: 여행 선호·교통수단 전체 교체, owner-only 저장, endpoint 전용 Problem과 OpenAPI named examples
- migration은 append-only `20260907000003_trip_preferences_replace_contract.sql`, Docker init slot은 `040`으로 고정했다.

## 최초 Red → Green → Refactor

운영 코드보다 선호 정책, call flow, HTTP codec, MockMvc, OpenAPI, migration 정적·통합 테스트를 먼저 추가했다. 최초 관련 테스트는 `compileTestJava`에서 `ReplaceTripPreferencesCommand`, `TripPreferencePolicy`, `TripPreferencesMutation`, request codec 부재로 실패했다.

Green에서는 1~3개 mode, 연속 priority, primary 정확히 한 건·priority 1, 허용 enum·중복·장소·revision·terminal 상태와 원자 교체 경계를 구현했다. 실제 DB와 Testcontainers를 제외한 관련 Java 테스트 45개 및 Python 계약 테스트 23개가 통과했다.

Refactor에서는 최신 develop의 Trip·Schedule 계약을 보존하면서 preferences endpoint에만 적용되는 Problem detail을 분리하고 error matrix의 status/code 집합을 exact named examples로 공개했다. 신규 public 함수 네 개는 `PUBLIC`, `anon`, `authenticated`의 EXECUTE를 회수하고, `service_role`에는 check constraint helper만 최소 권한으로 허용했다.

## Reviewer MAJOR: top-level JSON null

Reviewer 지적에 따라 운영 코드보다 다음 두 회귀 테스트를 먼저 추가했다.

- `TripPreferencesHttpCodecTest.top_level_JSON_null은_INVALID_REQUEST다`
- `TripPreferencesControllerIntegrationTest.malformed_duplicate_unknown_missing_null_trailing과_maxPlus1은_auth와service전에400이다`

동일 관련 테스트 21개를 실행한 Red 결과는 2건 실패였다. codec은 역직렬화 결과 `null`에 `toCommand()`를 호출해 `NullPointerException`을 냈고, MockMvc는 `400 INVALID_REQUEST` 대신 500을 반환했다.

최소 수정으로 `ObjectReader.readValue(body)` 결과가 `null`이면 `TripException.invalidRequest()`를 던지게 했다. 같은 21개 테스트가 모두 통과했고 malformed body는 현재 사용자 조회와 service/store 호출 전에 차단된다.

## 검증 제한

재통합 및 Reviewer 보완에서는 요청에 따라 실제 PostgreSQL, Testcontainers, Docker, live Supabase와 전체 heavy quality gate를 실행하지 않았다. 해당 검증 전에는 `READY_FOR_REVIEW`로 선언하지 않는다.
