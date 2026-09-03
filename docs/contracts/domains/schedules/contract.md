# 불변 일정 조회·편집 REST 계약 v1.0.0

Issue #88의 canonical 상세 계약은 [`contract.json`](contract.json)이다. 여섯 endpoint는 Spring Boot가 소유하고 검증된 Supabase JWT의 canonical `sub`로 여행 owner를 판정한다. 다른 사용자의 여행·버전·항목과 다른 여행에 속한 식별자는 모두 `404`로 숨긴다. Controller/Service/Repository, FastAPI MCP, schema migration과 Flyway는 이 문서 Issue 범위가 아니다.

## endpoint와 동시성

| Method | Path | 성공 | 변경 규칙 |
| --- | --- | --- | --- |
| GET | `/api/v1/trips/{tripId}/schedule` | `200` | `versionId` 생략 시 active, 지정 시 같은 owner/trip의 불변 version 조회 |
| POST | `/api/v1/trips/{tripId}/schedule-items` | `201` | 항목 추가 후 새 `user_edit` version 활성화 |
| PATCH | `/api/v1/trips/{tripId}/schedule-items/{itemId}` | `200` | 항목 수정 후 새 version 활성화 |
| DELETE | `/api/v1/trips/{tripId}/schedule-items/{itemId}` | `200` | 항목 삭제 후 새 version 활성화 |
| PUT | `/api/v1/trips/{tripId}/schedule-order` | `200` | 정확한 permutation으로 정렬한 새 version 활성화 |
| POST | `/api/v1/trips/{tripId}/schedule-items/{itemId}/move` | `200` | 다른 Day로 이동한 새 version 활성화 |

GET은 read-only다. active가 없거나 명시한 version이 없거나 다른 여행/owner에 속하면 `404`이며, stale facts는 `feasibilityStale=true`인 `200` 응답으로 표현한다. 조회에는 동시성 `409`가 없고 active pointer, version, item, leg, progress를 변경하지 않는다. Day는 `dayNo`, item은 `sequenceNo`로 안정 정렬하고 모든 인접 item pair에 leg가 정확히 하나 있어야 한다.

다섯 mutation은 `Authorization`, UUID 형식의 `Idempotency-Key`, 강한 `If-Match`를 필수로 받는다. `Idempotency-Key` 누락은 `400 IDEMPOTENCY_KEY_REQUIRED`, 헤더가 있지만 canonical UUID가 아니면 `400 IDEMPOTENCY_KEY_INVALID`이며 일반 `INVALID_REQUEST`로 합치지 않는다. UUID 제약은 `api_idempotency_records.idempotency_key`의 UUID 저장형과 같다. `expectedActiveScheduleVersionId`는 POST/PATCH/PUT body와 DELETE query에 둔다. `If-Match`는 여행 aggregate ETag, expected ID는 active schedule pointer를 각각 보호한다. 전자는 `TRIP_VERSION_CONFLICT`, 후자는 `ACTIVE_SCHEDULE_VERSION_CONFLICT`이며 둘 다 `409`다. `COMPLETED`인 같은 scope/key/hash는 저장된 status·순서가 보존된 header·body를 operation 재실행 없이 replay한다. 다른 hash는 즉시 `409 IDEMPOTENCY_KEY_REUSED`이고 `Retry-After`를 보내지 않는다. 2분 lease 안의 `PROCESSING` 같은 hash 동시 loser도 기다리거나 replay하지 않고 즉시 같은 `409`와 `Retry-After: 1`을 반환한다.

서버는 active version을 복사하고 편집한 뒤 item/leg 완전성을 검증한다. `sourceType`은 DB 값인 `initial|user_edit|ai_generation|recovery|live_recalculation`만 허용하고, 이 수동 편집 endpoint가 만드는 값은 `user_edit`다. 검증에 성공한 새 `user_edit` version의 `draft→active`, 이전 active의 `active→superseded`, pointer 전환은 한 transaction이다. 실패하면 새 version, 부분 item/leg, pointer 변경이 남지 않는다. 이 상태 전이 외에는 기존 version identity/content와 자식 item/leg를 직접 수정하지 않는다.

## 항목, 순서와 Day 이동

itemType은 `place_visit|meal|accommodation|arrival|departure|free_time|custom`이다. 모든 type은 `plannedStartAt`과 `stayMinutes(1..1440)`가 필요하다. `place_visit`은 `placeId`, `accommodation`은 `accommodationId`, `arrival/departure`는 `transportEventId`, 나머지는 `title`이 추가로 필요하다. 시작과 계산된 종료는 모두 target `Asia/Seoul` Day 안에 있어야 한다.

완료 progress가 있는 item은 PATCH/DELETE/reorder/move할 수 없고 `422 SCHEDULE_ITEM_COMPLETED`다. reorder는 active의 모든 item ID를 전체 Day에 걸쳐 정확히 한 번씩 제출하는 permutation이며 누락·중복·외부/추가 ID는 `400`이다. 적용 후 각 Day를 `1..N`으로 재번호하고 모든 인접 leg를 재구성한다. move는 target Day의 여행 귀속과 `plannedStartAt`의 제주 현지 날짜를 확인하고 source/target Day를 모두 compact한 뒤 영향 구간 leg를 재구성한다.

수동 변경은 DB constraint와 동기 deterministic validator만 사용한다. 이 요청 경로에서 MCP/AI를 호출하거나 실패한 편집을 자동 보정하지 않는다. AI 보정은 #89의 별도 schedule revision run으로만 접수한다.

## 인접 leg 결정 규칙

추가·삭제·재정렬·Day 이동은 먼저 복사되는 모든 item에 새 UUID를 발급하고 command 범위의 일대일 `oldItemIdToNewItemId` map을 만든다. 새 version의 leg endpoint는 반드시 이 map의 새 item ID만 참조하며, 이전 version의 전역 `trip_items.id`나 leg row를 재사용하지 않는다. 영향을 받은 인접 pair는 `기존 active leg 의미 값 재사용 → 저장된 route snapshot → 보수적 도보 fallback → 422` 순서로 결정한다. old pair를 map으로 옮긴 뒤에도 정렬 인접성과 item type, 정규화 장소/좌표, 시간창, 교통수단 의미가 모두 같은 경우에만 route 속성·구성시간을 새 leg row로 복사한다. 저장 snapshot은 같은 정규화 출발지·도착지·교통수단의 transaction 시작 시점 미만료 자료만 쓰며 `expiresAt DESC, observedAt DESC, snapshotId ASC` 순으로 하나를 고른다. 요청 중 외부 API·MCP는 호출하지 않는다.

snapshot이 없고 두 item에 정규화 좌표가 있으면 PostGIS geography 거리를 올림하고 `ceil(distanceMeters / 50)`(최소 1분)의 도보 구간을 만든다. 이때 `transportMode=walk`, wait/ride/transfer/buffer는 0, 출발은 앞 item의 `plannedEndAt`, 도착은 출발+구성요소 합계다. 도착이 다음 item 시작을 넘거나 좌표가 없으면 안정적으로 `422 SCHEDULE_LEG_INCOMPLETE`를 반환한다. 모든 Day가 정확히 `N-1`개의 연속 leg를 갖고 DB sealing validator를 통과한 후에만 같은 transaction에서 seal/CAS 활성화하며, 실패 시 draft와 부분 row를 rollback하고 기존 active pointer를 유지한다.

## 오류, 외부 증거와 schema gap

오류 fixture는 #72의 exact Problem Details field `type,title,status,detail,instance,code,traceId,fieldErrors`와 한국어 title/detail을 사용한다. 모든 code는 발생 조건과 HTTP status, endpoint matrix, fixture가 양방향으로 닫혀 있다. 특히 참조 accommodation/transport event가 없거나 다른 owner 또는 다른 trip에 속하면 각각 `ACCOMMODATION_NOT_FOUND`, `TRANSPORT_EVENT_NOT_FOUND`의 `404`로 은닉한다. `instance`에는 raw path/query, token, 이메일, `user_metadata`, provider payload를 반사하지 않는다.

이 개발 세션에서는 Notion의 해당 6개 endpoint 행과 Figma의 일정 화면 node/action/loading/empty/error/API contract version 연결을 직접 읽거나 수정했다는 증거를 확보하지 못했다. 따라서 둘 다 `contractVersion=not-linked`, `status=not-ready`이며 metadata/example/implementation readiness도 승격하지 않는다. 알려진 Figma file/page 식별자는 탐색 시작점일 뿐 연결 증거가 아니다.

#50은 `20260907000000_schedule_item_create_contract.sql`에서 `trip_items.accommodation_id`와 `transport_event_id`를 추가하고 `(id, trip_plan_id)` 복합 FK로 다른 여행의 참조를 DB에서도 차단했다. leg derivation source/algorithm version/snapshot ID는 새 전용 column을 만들지 않고 `facts`의 `legDerivation` marker와 기존 `mobility_route_snapshot_id`를 사용한다. `stay_minutes`의 기존 null/0 허용 범위는 sealed version validator와 API의 `1..1440` 검증으로 닫으며, 운영 public schema의 단일 기준은 `supabase/migrations`이고 Flyway는 도입하지 않는다.
