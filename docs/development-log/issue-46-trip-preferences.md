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

## 2026-09-05 실제 PostgreSQL QA와 owner 조회 RLS 보정

사용자가 disposable Docker/PostgreSQL QA를 승인한 뒤 두 actual PostgreSQL 통합 테스트를 실행했다. 최초 20개 중 JDBC 5개는 canonical schedule·compute run fixture drift였고, RLS 1개는 `authenticated`가 `auth` schema를 사용할 수 없어 기존 security-invoker `public.owns_trip_plan` 호출이 `42501`로 실패했다. 운영 계약을 바꾸지 않고 JDBC fixture만 현재 canonical 계약에 맞춘 결과 14개가 모두 통과했다.

owner 조회 문제는 먼저 후속 migration 부재를 고정하는 Red를 추가한 뒤 `supabase` CLI 2.116.0의 `migration new`로 migration을 생성했다. CLI의 당일 timestamp가 미래 시각으로 예약된 기존 `20260907000003`보다 앞섰으므로, append-only 단조 순서를 지키기 위해 생성 파일을 `20260907000004_trip_preferences_owner_read_helper.sql`로 이름만 정렬했다.

후속 migration은 비공개 `timing_jeju_private` schema에 `STABLE SECURITY DEFINER`, 빈 `search_path`, fully-qualified `auth.uid()`와 `public.trip_plans`만 사용하는 boolean owner helper를 둔다. `PUBLIC`·`anon`·`service_role`에는 schema·함수 접근을 주지 않고 `authenticated`에 schema `USAGE`와 해당 함수 `EXECUTE`만 허용했다. #46의 SELECT policy 두 개만 helper를 참조하도록 교체했고 기존 `public.owns_trip_plan`, 다른 policy·grant·RLS·data는 불변으로 검증했다.

Red는 migration 통합 테스트 6개 중 후속 artifact 부재 1건, delivery 계약은 Python 6개 중 4건 및 Java 5개 중 1건이었다. Green은 actual PostgreSQL migration 6/6, JDBC 14/14, Python 관련 계약 23/23, Java delivery 계약 5/5다. Docker init delivery는 compose 세 종류와 smoke의 기존 `040` 바로 뒤에 `041`만 추가했다. 각 actual PostgreSQL 실행 전후 Testcontainers/Ryuk 잔여는 0이었고 보호 baseline은 변경하지 않았다.

Astra staged review에서는 latest migration 전체를 적용하는 기존 trip ACL 통합 테스트 두 곳이 `trip_transport_modes`의 `authenticated SELECT`를 여전히 거부로 기대하는 상충을 발견했다. 운영 SQL을 변경하지 않고 두 ACL matrix에 `trip_preferences`를 포함했으며, `authenticated SELECT`만 두 owner-readable table에서 true가 되도록 exact 예외를 두었다. `anon` SELECT, authenticated write, `trip_plans`와 `auth` schema 직접 접근 및 나머지 table ACL 기대는 유지했다. 기존 기대의 Red는 actual PostgreSQL 4개 중 2건 실패였고, 보정 후 #46 migration/JDBC 테스트를 합친 네 클래스 24개가 한 실행에서 모두 통과했다.

## 남은 검증 제한

live Supabase·운영 DB 적용·배포는 승인 범위 밖이라 실행하지 않았다. root quality gate와 docker smoke는 #195 landing 및 OpenAPI inventory 정렬 전 실행 금지 상태이므로 이번 작업에서는 실행하지 않았으며, 이 두 gate 전에는 `READY_FOR_REVIEW`로 선언하지 않는다.
