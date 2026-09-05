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

## 2026-09-05 #195 통합 뒤 25-operation OpenAPI readiness 보정

#195 포트 격리 commit `392fa4602fcc9c13b3110adb6a6209620030b8ee`를 일반 merge한 `ff3b900ad036b4609d4b5610794d38724e529b33`에서 전체 quality gate를 처음 실행했다. OpenAPI readiness 전 단계는 hooks 36/36, git-hooks 7/7, scripts 705/705(skip 3), unit 1,139개(skip 6), slice 43개, integration 541개(skip 4), openApiDocs 11개가 모두 통과했다. 생성 artifact의 #46 endpoint만 readiness 11건이 실패해 이후 단계와 내장 Docker는 실행되지 않았다.

운영 HTTP·DB 동작은 바꾸지 않고 다음 delivery drift를 TDD로 보정했다.

- canonical operation `update`와 validator suffix 규칙에 맞춰 operationId를 `tripPreferencesUpdate`로 고정했다.
- request/success 예시의 장소·여행 ID를 RFC 4122 version 4/variant 8 UUID로 교체했다.
- `200`의 strong `ETag`를 exact response header projection에 추가했다.
- named Problem examples에 함께 직렬화되던 `example: null`을 제거했다.
- historical mode24는 그대로 두고 #46 PUT만 추가한 mode25 exact inventory, `c6862499d71519d9efc7bfcf72855703d1e94f0a` source provenance, canonical authority와 runtime manifest를 추가했다.
- canonical closed `allOf` response를 runtime의 flat closed object와 비교하도록 portable validator를 보완하고, `MutationHeaders.If-Match`를 문서 설명·runtime과 동일한 trip revision strong ETag pattern으로 정렬했다.

최초 Red는 Python delivery 묶음 20개 중 4건과 Spring focused OpenAPI 4개 중 3건이었다. 후속 canonical projection Red 2건도 별도 고정했다. 보정 뒤 Python 관련 59개, Spring focused OpenAPI 4개가 통과했고 새로 생성한 단일 artifact는 `--mode 25`에서 25 operations 성공, historical `--mode 24`에서는 #46 PUT을 allowlist 밖으로 정확히 거부했다.

## 남은 검증 제한

live Supabase·운영 DB 적용·배포는 승인 범위 밖이라 실행하지 않았다. OpenAPI 보정 staged source의 독립 검토 전에는 전체 quality gate와 내장 Docker를 재실행하지 않으며 `READY_FOR_REVIEW`로 선언하지 않는다.
