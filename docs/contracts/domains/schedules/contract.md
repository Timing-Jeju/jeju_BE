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

다섯 mutation은 `Authorization`, `Idempotency-Key`, 강한 `If-Match`를 필수로 받는다. `expectedActiveScheduleVersionId`는 POST/PATCH/PUT body와 DELETE query에 둔다. `If-Match`는 여행 aggregate ETag, expected ID는 active schedule pointer를 각각 보호한다. 전자는 `TRIP_VERSION_CONFLICT`, 후자는 `ACTIVE_SCHEDULE_VERSION_CONFLICT`이며 둘 다 `409`다. 같은 멱등성 scope/key/hash는 최초 status/body/ETag를 replay하고 다른 hash 또는 동시 loser는 `409`다.

서버는 active version을 복사하고 편집한 뒤 item/leg 완전성을 검증한다. 검증에 성공한 새 `user_edit` version을 봉인하고 이전 active를 supersede하며 pointer를 전환하는 전체 과정은 한 transaction이다. 실패하면 새 version, 부분 item/leg, pointer 변경이 남지 않는다. 기존 active/candidate/sealed/superseded version은 직접 수정하지 않는다.

## 항목, 순서와 Day 이동

itemType은 `place_visit|meal|accommodation|arrival|departure|free_time|custom`이다. 모든 type은 `plannedStartAt`과 `stayMinutes(1..1440)`가 필요하다. `place_visit`은 `placeId`, `accommodation`은 `accommodationId`, `arrival/departure`는 `transportEventId`, 나머지는 `title`이 추가로 필요하다. 시작과 계산된 종료는 모두 target `Asia/Seoul` Day 안에 있어야 한다.

완료 progress가 있는 item은 PATCH/DELETE/reorder/move할 수 없고 `422 SCHEDULE_ITEM_COMPLETED`다. reorder는 active의 모든 item ID를 전체 Day에 걸쳐 정확히 한 번씩 제출하는 permutation이며 누락·중복·외부/추가 ID는 `400`이다. 적용 후 각 Day를 `1..N`으로 재번호하고 모든 인접 leg를 재구성한다. move는 target Day의 여행 귀속과 `plannedStartAt`의 제주 현지 날짜를 확인하고 source/target Day를 모두 compact한 뒤 영향 구간 leg를 재구성한다.

수동 변경은 DB constraint와 동기 deterministic validator만 사용한다. 이 요청 경로에서 MCP/AI를 호출하거나 실패한 편집을 자동 보정하지 않는다. AI 보정은 #89의 별도 schedule revision run으로만 접수한다.

## 오류, 외부 증거와 schema gap

오류 fixture는 #72의 exact Problem Details field `type,title,status,detail,instance,code,traceId,fieldErrors`와 한국어 title/detail을 사용한다. `instance`에는 raw path/query, token, 이메일, `user_metadata`, provider payload를 반사하지 않는다.

이 개발 세션에서는 Notion의 해당 6개 endpoint 행과 Figma의 일정 화면 node/action/loading/empty/error/API contract version 연결을 직접 읽거나 수정했다는 증거를 확보하지 못했다. 따라서 둘 다 `contractVersion=not-linked`, `status=not-ready`이며 metadata/example/implementation readiness도 승격하지 않는다. 알려진 Figma file/page 식별자는 탐색 시작점일 뿐 연결 증거가 아니다.

문서 작업은 schema를 바꾸지 않는다. item type별 place/accommodation/transport-event FK 완전성은 #50에서 재검증하고 필요한 migration은 별도 구현 범위로 명시한다. 운영 public schema의 단일 기준은 `supabase/migrations`이며 Flyway를 도입하지 않는다.
