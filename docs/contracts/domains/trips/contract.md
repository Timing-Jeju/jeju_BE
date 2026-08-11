# 여행 CRUD API canonical 계약

이 문서는 Issue #85에서 확정한 여행 루트 CRUD의 사람이 읽는 기준이다. 기계 판독 기준은 같은 디렉터리의 `contract.json`이며, 구현자는 추가 결정을 하지 않아도 Issue #44와 #45를 구현할 수 있어야 한다.

## 범위와 소유권

- `GET /api/v1/trips`, `POST /api/v1/trips`, `GET /api/v1/trips/{tripId}` 구현 책임은 Issue #44다.
- `PATCH /api/v1/trips/{tripId}`, `DELETE /api/v1/trips/{tripId}` 구현 책임은 Issue #45다.
- 모든 조회와 변경의 소유자는 검증된 access token의 canonical sub다. 이메일, provider profile, `user_metadata`, 원본 JWT, `session_id`, public token은 소유권 판단에 사용하지 않는다.
- 다른 사용자의 `tripId`와 존재하지 않는 `tripId`는 모두 `404 TRIP_NOT_FOUND`로 응답해 존재 여부를 숨긴다.
- Issue #85에서는 Controller·Service·Repository를 구현하지 않는다. DB 스키마도 변경하지 않는다.

## 공통 HTTP 계약

| 작업 | 성공 | 필수 조건 | 외부 호출 |
|---|---:|---|---|
| 목록 | 200 | Bearer 인증, cursor pagination | 없음 |
| 생성 | 201 | Bearer 인증, `Idempotency-Key` | 없음 |
| 상세 | 200 | Bearer 인증, UUID `tripId` | 없음 |
| 수정 | 200 | Bearer 인증, strong `If-Match` | 없음 |
| 삭제 | 204 | Bearer 인증, 응답 body 없음 | 없음 |

오류는 공통 Problem Details 계약을 사용한다. 닫힌 request/response schema에 없는 필드는 거부하며, nullable로 명시하지 않은 필드에는 `null`을 허용하지 않는다.

## 생성 계약

- 필수: `title`, `startDate`, `endDate`.
- 기본값: `timezone=Asia/Seoul`, `userPace=normal`, `transportModes=[{mode: public_transit, priority: 1, primary: true}]`, `status=draft`.
- 여행 기간은 양 끝 날짜를 포함해 1일 이상 30일 이하다.
- 교통수단 우선순위는 1부터 빈틈없이 이어지고 mode는 중복되지 않으며 primary는 정확히 하나다.
- `trip_plans`, 기본 교통수단, 날짜별 `trip_days`는 한 트랜잭션으로 생성한다.
- `Idempotency-Key`는 공통 Issue #17 계약과 같은 canonical UUID다. 누락은 `400 IDEMPOTENCY_KEY_REQUIRED`, UUID 형식 오류는 `400 IDEMPOTENCY_KEY_INVALID`다.
- 키 범위는 canonical sub + method + path이며 보존 시간은 24시간이다. 같은 payload는 최초의 status, `Location`, `ETag`, body를 그대로 재생하고, 다른 payload 또는 처리 중·재사용 상태는 `409 IDEMPOTENCY_KEY_REUSED`다.

## 목록과 점수 계약

- 기본 크기는 20, 최대 크기는 100이다.
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
- 여행 aggregate와 위치·실행 이력은 외래 키 cascade로 함께 삭제한다.
- 관광·교통·날씨 정규화 데이터, provider snapshot, `data_import_runs` 같은 외부 수집 lineage, 사용자와 Auth identity는 보존한다.

## DB drift와 migration 경계

현재 스키마에는 `trip_plans.timezone`, strong ETag용 단조 증가 revision, owner write RLS가 없다. timezone과 생성 write RLS는 Issue #44, revision과 수정·삭제 write 검증은 Issue #45가 담당한다. 운영 migration의 단일 기준은 `supabase/migrations`이며 Flyway를 도입하지 않는다.

## 외부 문서 추적성

- Notion의 다섯 endpoint 원본은 source spec v1.1이며 이 canonical 1.0.0 내용을 반영한 뒤에도 구현 증거가 없으므로 Draft/not-ready다.
- Figma에서 직접 확인한 근거는 `182:3248 홈 - 01. 여행 기본 조건`의 입력·저장 동작뿐이다. 목록·상세·삭제와 loading/empty/error 상태의 직접 근거는 없어 not-ready다.
- 로컬 catalog와 외부 문서의 metadata/example/implementation readiness를 추측으로 ready로 올리지 않는다.
