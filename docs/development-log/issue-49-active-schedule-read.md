# Issue #49 활성 일정 버전 조회 API 개발 기록

## State

- 기준 브랜치: `origin/develop` `ae3926ac6428c1d93cd57372fbafb8dd31d34544`
- 작업 브랜치: `feat/49-active-schedule-read`
- 기준 계약: `docs/contracts/domains/schedules/contract.json` v1.0.0,
  provenance `a5f53adcf43a63672de76d2a0ec4579257cb664a`
- 구현 API: `GET /api/v1/trips/{tripId}/schedule?versionId=<optional UUID>`
- active 또는 같은 owner/trip의 명시 버전을 read-only repeatable-read snapshot으로 조회한다.
- Day, item, leg, progress와 feasibility freshness를 closed response로 투영한다.
- 이 Issue 범위에서 migration, 일정 변경, active pointer 변경, MCP/FastAPI 및 외부 API 호출은 추가하지 않았다.

## Evidence

### Red → Green → Refactor

- Application Red: `ScheduleStore`, `ScheduleSnapshot`, `ScheduleQueryService` 부재로 19개 심볼 컴파일이
  실패했다. owner/trip/version selector와 순수 application snapshot을 추가해 Green으로 전환했다.
- HTTP Red: endpoint가 없어 MockMvc 요청이 정상 일정 조회에 도달하지 못했다. canonical UUID와 query shape를
  엄격하게 검증하는 Controller, 한국어 8-field Problem Details, DTO mapping을 구현했다.
- PostgreSQL Red: JDBC store가 없어 active/explicit version, owner 은닉, freshness와 불완전 row fail-closed
  시나리오를 실행할 수 없었다. 실제 PostgreSQL fixture 기반 repository와 HTTP 통합 테스트를 Green으로 만들었다.
- OpenAPI Red: 생성 문서의 tag가 canonical `일정`이 아니고 schedule operation inventory가 없었다.
  `tripScheduleRead`, 성공/오류 예시, runtime manifest와 mode 21 validator를 추가했다.
- Refactor 중 ArchUnit이 repository 내부 클래스 이름과 enum switch synthetic class를 감지했다.
  JDBC 구현을 `domain.schedule.adapter`로 옮기고 application service 분기를 명시적으로 바꿔 architecture test를
  통과시켰다.

### 조회·보안·무결성

- owner 조건을 root query에 먼저 적용하고 wrong-trip/cross-owner version을 동일한
  `SCHEDULE_VERSION_NOT_FOUND`로 은닉한다.
- 찾은 일정은 항상 네 번의 set-based query(root/version/freshness, days, items/progress, legs)로 읽어
  Day/item 수에 따른 N+1을 차단한다.
- repository 경계는 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`이며 HTTP 전후 row
  fingerprint가 동일함을 실제 PostgreSQL로 검증했다.
- Day/item/leg를 canonical sequence와 UUID tie-breaker로 정렬하고 비정상 중복 번호도 모든 행을
  결정적으로 투영한다. 각 Day의 leg가 정렬된 인접 item pair `max(N-1, 0)`개를 정확히 연결하지 않으면
  원천 정보를 노출하지 않는 공통 `INTERNAL_SERVER_ERROR`로 fail-closed 한다.
- 최신 성공 feasibility의 `observedAt <= calculatedAt <= expiresAt`과 응답 시각 `< expiresAt`을 모두 만족할
  때만 `feasibilityStale=false`이다. malformed 또는 근거 없음/만료는 오류가 아닌 stale projection이다.
- JWT 원문, 사용자 metadata, provider URL/query/raw payload, geometry를 조회·응답·로그에 포함하지 않는다.

### 검증 결과

- focused application, Controller, JDBC, HTTP PostgreSQL, OpenAPI, Architecture 테스트: Green
- Compose v2.27.1 격리 환경의 `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`:
  596건 Green, skip 2
- `python3 scripts/validate_openapi_frontend_readiness.py --operations 21`: 21 operations Green
- `./gradlew --no-daemon clean check` 결과 보고서:
  - unit/slice 1,584건, failure 0, error 0, skip 8
  - integration 470건, failure 0, error 0, skip 2
  - JaCoCo, OpenAPI, Spotless 포함 Green
- 종료 상태 재확인 `./gradlew --no-daemon check`: `BUILD SUCCESSFUL`
- `git diff --check`: 오류 없음

### 독립 Reviewer CHANGES_REQUESTED 보완

- HTTP/OpenAPI RED: schedule 전용 `503 TRIP_DATA_UNAVAILABLE`가 canonical #88의 GET 오류 계약을
  벗어났고, content length `-1`·Transfer-Encoding 없음·실제 stream body 조건이 service 호출까지
  우회했다. 저장·무결성 실패를 cause 없는 공통 `500 INTERNAL_SERVER_ERROR`로 변경하고 실제 request
  stream 첫 byte를 fail-closed 검사했다. 생성 OpenAPI와 runtime manifest에서도 schedule 503을 제거했다.
- PostgreSQL RED: mock query 횟수와 annotation 존재만으로는 실제 N+1·snapshot을 증명하지 못했다.
  실제 DataSource의 PreparedStatement 실행을 계측해 active/candidate 크기와 무관하게 root, days,
  items/progress, legs 네 query임을 확인했다. root A 조회 직후 별도 transaction이 active pointer를 B로
  바꾸고 A child를 수정·commit해도 응답이 전부 기존 A snapshot인 2-session 회귀 테스트를 추가했다.
- 정렬 RED: 중복 `dayNo`와 `sequenceNo` fixture가 무결성 오류로 닫혔다. 번호는 양수만 검증하고
  `(dayNo, dayId)`, `(sequenceNo, itemId|legId)`로 모든 행을 정렬하되 leg 수와 from/to adjacency는
  그대로 엄격히 검증하도록 분리했다.

## Gaps

- Developer 역할에서는 PR을 만들거나 Reviewer 승인 상태를 변경하지 않는다.
- 최신 `develop...HEAD` 독립 Reviewer findings 0 승인과 공식 PR 생성·병합은 아직 남아 있다.
- 후속 #50/#51은 #49가 `develop`에 병합된 뒤 동일 read model을 기준으로 시작해야 한다.
- live execution event, FE `DayReview` projection, 생성/수정/재검사 run은 각각 후속 Issue 범위다.

## Risks

- 큰 일정의 응답 크기는 query 횟수와 별개로 증가한다. canonical 최대 여행 기간과 item 정책을 후속 성능
  검증에서 함께 관찰해야 한다.
- DB에 불완전 draft 또는 잘못 연결된 leg가 있으면 가짜 기본값을 반환하지 않고 공통 500으로 닫힌다.
  응답과 로그에는 SQL, credential, 원인 예외를 노출하지 않으며 내부 무결성 지표와 데이터 복구 절차가 필요하다.
- `feasibilityStale=true`는 조회 실패가 아니므로 FE가 확정 일정과 재검사 필요 상태를 구분해 표시해야 한다.
