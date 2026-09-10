# 여행 CRUD API canonical 계약

이 문서는 Issue #85에서 확정한 여행 루트 CRUD의 사람이 읽는 기준이다. 기계 판독 기준은 같은 디렉터리의 `contract.json`이며, 구현자는 추가 결정을 하지 않아도 Issue #44와 #45를 구현할 수 있어야 한다.

## 범위와 소유권

- `GET /api/v1/trips`, `POST /api/v1/trips`, `GET /api/v1/trips/{tripId}` 구현 책임은 Issue #44다.
- `PATCH /api/v1/trips/{tripId}`, `DELETE /api/v1/trips/{tripId}` 구현 책임은 Issue #45다.
- 모든 조회와 변경의 소유자는 검증된 access token의 canonical sub다. 이메일, provider profile, `user_metadata`, 원본 JWT, `session_id`, public token은 소유권 판단에 사용하지 않는다.
- 다른 사용자의 `tripId`와 존재하지 않는 `tripId`는 모두 `404 TRIP_NOT_FOUND`로 응답해 존재 여부를 숨긴다.
- Issue #85에서는 Controller·Service·Repository를 구현하지 않는다. DB 스키마도 변경하지 않는다.

## 공통 HTTP 계약

| 작업 | 성공 | 필수 조건 | 오류 | 외부 호출 |
|---|---:|---|---|---|
| 목록 | 200 | Bearer 인증, cursor pagination | 400, 401, 저장소 장애 시 `503 TRIP_DATA_UNAVAILABLE` | 없음 |
| 생성 | 201 | Bearer 인증, `Idempotency-Key` | 400, 401, 409, 422, 503 | 없음 |
| 상세 | 200 | Bearer 인증, lowercase canonical UUID `tripId` | 잘못된 path는 `400 INVALID_REQUEST`; 401, 404; 저장소 장애는 `503 TRIP_DATA_UNAVAILABLE` | 없음 |
| 수정 | 200 | Bearer 인증, strong `If-Match` | 400, 401, 404, 409, 422 | 없음 |
| 삭제 | 204 | Bearer 인증, 응답 body 없음 | 400, 401, 404, 409 | 없음 |

오류는 공통 Problem Details 계약을 사용한다. 닫힌 request/response schema에 없는 필드는 거부하며, nullable로 명시하지 않은 필드에는 `null`을 허용하지 않는다.

## 생성 계약

- 필수: `title`, `startDate`, `endDate`.
- 기본값: `timezone=Asia/Seoul`, `userPace=normal`, `transportModes=[{mode: public_transit, priority: 1, primary: true}]`, `status=draft`.
- 여행 기간은 양 끝 날짜를 포함해 1일 이상 30일 이하다.
- 교통수단 우선순위는 1부터 빈틈없이 이어지고 mode는 중복되지 않으며 primary는 정확히 하나다.
- `trip_plans`, 기본 교통수단, 날짜별 `trip_days`는 한 트랜잭션으로 생성한다.
- `Idempotency-Key`는 공통 Issue #17 계약과 같은 canonical UUID다. 누락은 `400 IDEMPOTENCY_KEY_REQUIRED`, UUID 형식 오류는 `400 IDEMPOTENCY_KEY_INVALID`다.
- 키 범위는 canonical sub + method + path이며 보존 시간은 24시간이다. 같은 payload는 최초의 status, `Location`, `ETag`, body를 그대로 재생하고, 다른 payload 또는 처리 중·재사용 상태는 `409 IDEMPOTENCY_KEY_REUSED`다.
- 인증 프로필 준비 중 이메일 소유권 또는 provider subject 충돌은 원인을 노출하지 않는 `409 PROFILE_CONFLICT`로 응답한다.
- 인증 identity가 유효하지 않거나 프로필 저장소를 사용할 수 없으면 원천 메시지·PII·cause를 노출하지 않는 `503 TRIP_DATA_UNAVAILABLE`로 응답한다.
- 목록·상세 조회 중 저장소 접근 실패도 raw SQL, 연결 문자열과 cause를 노출하지 않는 동일한 `503 TRIP_DATA_UNAVAILABLE`로 응답한다.

## 목록과 점수 계약

- 기본 크기는 20, 최대 크기는 공통 `CursorPageRequest.MAX_SIZE`와 같은 50이다.
- 정렬은 RFC3339 문자열 사전순이 아니라 실제 instant 기준 `updatedAt DESC`이며, 같은 instant에서만 `tripId DESC`를 적용한다. cursor는 canonical sub, status, sort 문맥에 묶인 불투명 값이다.
- 다음 페이지가 있으면 `nextCursor`가 반드시 있고, 마지막 페이지에는 없어야 한다.
- `totalScore`는 항상 존재하되 값은 0..100 정수 또는 `null`이다.
- 점수가 `null`이면 `scoreProvenance`도 `null`이다. 점수가 있으면 활성 일정 버전의 최신 성공 `feasibility_run` 출처가 필요하다.
- 점수를 포함하는 모든 응답은 명시적 `responseTime`을 제공한다. freshness는 `observedAt <= calculatedAt <= expiresAt`이며 `stale == (responseTime >= expiresAt)`를 만족해야 한다.

## 수정 계약

PATCH는 저장된 단조 증가 revision으로 만든 strong `If-Match`가 필수다. 누락은 `400 IF_MATCH_REQUIRED`, 형식 오류는 `400 INVALID_IF_MATCH`, 오래된 revision은 `409 TRIP_VERSION_CONFLICT`다. 생략한 필드는 보존하고 명시적 `null`은 거부하며 배열은 전체 교체한다.

| 변경 필드 | 일정 효과 |
|---|---|
| `title` | 활성 일정과 상태 유지 |
| `userPace`, `transportModes` | 활성 일정을 무효화하고 draft로 전환, 재생성 필요 |
| `startDate`, `endDate`, `timezone` | 일정 버전이 하나라도 있으면 `409 TRIP_REGENERATION_REQUIRED`; 없으면 날짜와 `trip_days`를 원자적으로 재구성 |

`completed`, `cancelled`, `failed` 상태는 모든 PATCH를 거부한다.

## 삭제와 데이터 보존

- 최초 삭제는 body 없는 204, 반복 삭제와 cross-owner 요청은 404다.
- 실행 중 async run이 있거나 여행 상태가 live면 `409 TRIP_DELETE_CONFLICT`다.
- 여행 aggregate의 direct/transitive cascade 자식은 함께 삭제한다. 최신 실행·command-input 경계에는
  `schedule_revision_runs`와 `compute_run_inputs`도 포함된다.
- 관광·교통·날씨 정규화 데이터, provider snapshot, `data_import_runs` 같은 외부 수집 lineage, 사용자와 Auth identity는 보존한다.

## DB drift와 migration 경계

Issue #44는 `trip_plans.timezone`의 `Asia/Seoul` default/check를 추가했고, Spring API가 `service_role`로 여행 aggregate를 쓰는 유일한 경로를 확정했다. `anon`과 `authenticated`에는 `trip_plans`, `trip_transport_modes`, `trip_days`의 직접 table 권한이 없고 client INSERT/UPDATE/DELETE RLS policy 수도 0이다. POST는 Spring application에서 canonical JWT sub와 owner predicate를 결합하고 root·mode·day를 한 transaction으로 저장한다. write RLS policy는 요구하지 않으며, Issue #45의 PATCH/DELETE도 같은 Spring owner predicate와 transaction 경계를 사용한다. 남은 schema drift는 strong If-Match/ETag용 단조 증가 revision뿐이다. 운영 migration의 단일 기준은 `supabase/migrations`이며 Flyway를 도입하지 않는다.

## 외부 문서 추적성

- Notion의 다섯 endpoint 원본은 source spec v1.1이며 이 canonical 1.0.0 내용을 반영한 뒤에도 구현 증거가 없으므로 Draft/not-ready다.
- Figma에서 직접 확인한 근거는 `182:3248 홈 - 01. 여행 기본 조건`의 입력·저장 동작뿐이다. 목록·상세·삭제와 loading/empty/error 상태의 직접 근거는 없어 not-ready다.
- 로컬 catalog와 외부 문서의 metadata/example/implementation readiness를 추측으로 ready로 올리지 않는다.

## #239 날짜별 활동 시간 (계약 1.1.0)

`PUT /api/v1/trips/{tripId}/day-activity-windows`는 모든 현재 Day를 정확히 한 번 포함하는 전체 교체다. Bearer 인증, strong `If-Match`, printable ASCII 1~128자의 `Idempotency-Key`가 필요하다. body는 `{"days":[{"dayId":"…","startTime":"09:00","endTime":"18:00"}]}` 형태이며 미지 필드·중복 JSON key·누락·null·타입 오류를 해시 생성 전에 400으로 거부한다. 시각은 Asia/Seoul의 정확한 `HH:mm`, 00:00~23:59, 시작 < 종료다. 잘못된 시각·중복/누락/다른 여행 Day는 `422 TRIP_CONSTRAINT_VIOLATION`이다. 소유하지 않은 여행은 404이며 다른 Day 소유자 정보는 공개하지 않는다.

성공은 GET과 같은 `TripDetail`과 ETag다. 각 Day의 `activityStartTime`·`activityEndTime`은 required nullable 문자열이다. 미입력은 null 쌍이며 서버 기본 시각을 만들지 않는다. 시간 일부 null이나 초·소수초를 포함한 legacy 값은 마이그레이션 사전 검사에서 기존 값을 변경하지 않고 적용을 중단한다. 이미 저장된 초 단위 값을 조회 응답에서 조용히 절삭하지 않는다.

전체 Day·aggregate revision·멱등 응답 snapshot을 동일 트랜잭션으로 저장한다. 같은 키와 body는 원본 응답을 replay하고 다른 body는 `409 IDEMPOTENCY_KEY_REUSED`다. stale revision은 `409 TRIP_VERSION_CONFLICT`, completed/cancelled/failed는 `409 TRIP_TERMINAL_STATE_CONFLICT`다. 값이 동일한 no-op은 revision을 증가시키지 않는다. active/candidate 일정이 참조하는 Day의 시간 변경은 `409 TRIP_REGENERATION_REQUIRED`이며 해당 일정을 수정하거나 다시 계산하지 않는다.

날짜 PATCH는 날짜가 겹치는 Day의 ID·활동 시간을 보존하고 새 Day를 null 쌍으로 만든다. BE의 기존 1~30일 계약을 유지한다. FE의 1차 입력 범위 1~5일은 UI 제한이며 기존 6~30일 여행을 삭제하거나 읽기 불가로 만들지 않는다. MCP 생성 입력·worker 연결은 #89 후속 범위다. Figma/Notion의 이 저장 동작은 실제 연결 확인 전 `not-linked`다.

forward migration은 `20260918000021_day_activity_window_pair.sql`, manifest/Docker 슬롯은 `059`다. 소스 QA는 disposable PostgreSQL에서 실행하며 live DB 적용·운영 배포 완료를 의미하지 않는다.

상세 GET의 신규 read-only transaction은 REPEATABLE_READ를 사용한다. root 조회와 Day 조회 사이에 동시 writer가 완료되더라도 이전 revision과 새 시간을 섞지 않는다. writer 안의 aggregate 반환은 기존 owner root lock과 동일 트랜잭션을 유지한다.

### #239 생성 재시도의 과거 응답 계약

`POST /api/v1/trips`의 201은 `TripCreateResponse = TripDetail | TripDetailLegacyV1`이다. 새 생성은 최신 required Day 활동 시간 쌍을 포함한다. 배포 전 완료 receipt가 아직 24시간 TTL 안에 있으면 `Idempotency-Replayed: true`와 함께 당시 status·Location·ETag·body bytes를 그대로 반환하며, 이때만 활동 시간 필드가 없는 닫힌 `TripDayLegacyV1` shape를 허용한다. 최신 GET/PATCH/Day PUT의 필수 필드는 약화하지 않는다. 클라이언트는 과거 생성 replay에서 누락된 활동 시간을 기본값으로 만들지 않고 Location의 GET으로 최신 여행을 복원한다.

TTL은 기존 완료 시각으로부터 계산하며 배포나 재시도로 연장하지 않는다. 만료 경계에서는 기존 registry 규칙을 그대로 따른다. receipt 삭제·namespace 교체·body 재작성·최신 GET 응답으로 치환하는 데이터 변경은 없다. 이 호환 계약 때문에 DB migration을 추가하지 않는다.
