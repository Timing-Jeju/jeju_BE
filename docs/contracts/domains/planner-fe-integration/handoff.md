# FE 기준 planner 연동 handoff v1

기준 FE는 `Timing-Jeju/jeju_FE` `main@3c280a1cc05c73150e6f0bb1174443906de04f91`이다. 이번 변경은 FE 파일을 수정하지 않았고, source checksum과 projection 구조는 `contract.json`에 고정했다. FE SHA 또는 checksum 하나라도 바뀌면 compatibility review를 다시 수행한다.

## 화면 동작과 API

| FE 동작 | BE API | MCP orchestration | 구현 상태 |
| --- | --- | --- | --- |
| 여행 조건 저장 | `PUT /api/v1/trips/{tripId}/planner-conditions` | 없음 | 후속 aggregate issue |
| 장소 검색·찜 | `GET /api/v1/places`, `GET /api/v1/me/saved-places` | 미해결 selector만 Search | 기존 API + adapter 후속 |
| Day 생성·polling | `POST/GET /generation-runs` | Recommend 1 + Evaluate 3 + 제한 Inspect | #53/#79/#95 |
| 순서·추가·삭제·체류시간·대안 | `POST /schedule-revision-runs` | 변경 leg만 Preview + full Evaluate | #69/#104/#105 |
| 빈시간 채우기 | `POST /spare-time-runs` | 좁은 window Recommend + Evaluate | #57/#98/#99 |
| 구간 상세 | `GET /schedule-versions/{versionId}/legs/{legId}` | 저장 결과만 조회 | #56 |
| 재검사 | `POST /feasibility-runs` | Evaluate 1 | #55/#96/#97 |
| 홈·라이브 | `GET /schedule?projection=fe-review-v1`, execution/live runs | Revalidate 1 | #49/#59/#60/#102/#103 |

planned endpoint는 해당 구현 issue가 완료되기 전에는 OpenAPI에 게시하지 않는다. 비동기 POST는 `202 + Location + Retry-After`, GET polling을 사용한다.

## FE mock 교체점

- `buildDayReview` → generation polling의 rank-1 `selectedCandidate.review`
- `rechainLegs` → schedule revision run
- `recheckReview` → feasibility run
- `buildFillSuggestions` → spare-time run result
- `buildAlternatives` → 저장된 leg alternatives
- `activeReview` → active schedule `fe-review-v1` projection
- 라이브 화면의 임의 current leg → execution event와 마지막 성공 live snapshot
- 장소명 hash 기반 시간·거리·비용·버스 번호 → evidence 기반 projection

FE는 server 식별자를 현재 타입에 보관하지 못한다. `candidateId`, `scheduleVersionId`, `rank`, `strategy`, `evaluationStatus`는 `DayReview` 밖 wrapper에 두고 adapter side metadata로 유지한다. `review` 자체에는 FE 타입에 없는 필드를 추가하지 않는다.

## projection 규칙

- 시간은 KST `H:MM`, 거리는 1000m 미만 `800m`, 이상은 `1.2km`다.
- 성공 leg의 시간·거리·비용은 evidence가 모두 있어야 한다. 숫자를 만들거나 빈 값을 0으로 바꾸면 안 된다.
- Evaluate slack이 null일 때만 `0 + warning + 여유시간 근거 확인 불가` sentinel을 사용한다.
- MCP risk `low → positive`, `medium/unknown → cautionary`, `high/critical → warning`으로 투영한다.
- bus color는 노선번호 hash에서 `green|blue|sky|red`를 고르는 UI 장식이며 evidence가 아니다. FE 타입은 기존 `grey`도 구조적으로 허용하지만 planner projection은 생성하지 않는다.
- 대안은 FE가 정확히 구분하는 bus와 taxi만 반환한다. walk 대안을 `bus=null`로 반환하지 않는다.
- `FillSuggestion.transportLabel`은 `도보|버스`만 반환한다.

## 장소·경계·사용자 의도

- AI place ID는 `tourapi.place:<contentId>`이며 BE UUID crosswalk가 확인된 장소만 planner에 전달한다.
- TourAPI content type 39에서 `A05020900`은 `카페`, 나머지는 `식당`, 그 외는 `관광지`로 투영한다.
- 사용자가 지정한 `stayMinutes`는 constraint이며 축소하지 않는다. policy 근거도 사용자 값도 없으면 일정을 만들기 전에 입력을 요구한다.
- FE arrival/departure time은 일정 시작 가능 시각/terminal 도착 마감 시각이다. 임의 체크인 buffer를 빼지 않는다.
- 비행기는 제주국제공항으로 해석한다. 선박은 정확한 terminal place가 없으면 `TERMINAL_LOCATION_REQUIRED`다.
- 첫/중간/마지막/당일 경계는 MCP `activity_window`와 `day_boundary`로 명시한다.

## 알려진 FE 제한

- 현재 2초 고정 loading은 실제 비동기 run에 충분하지 않을 수 있어 polling adapter가 필요하다.
- FE가 장소명을 key로 사용하므로 동명이인 구분용 BE UUID를 side metadata로 보존해야 한다.
- FE 선박 선택의 공항 fallback을 BE에서 복제하지 않는다.
- Naver driving geometry는 FE 표시 전용이며 BE/AI로 보내거나 저장하지 않는다.
- 위치기반서비스 Gate #168 전에는 지도 SDK 위치를 BE/AI로 전송하지 않는다.
- `핫플/트렌디`, `로컬/현지`는 승인 근거가 생기기 전까지 `STYLE_NOT_GROUNDED`다.

OpenAPI TypeScript client는 planned endpoint 구현 후 생성된 OpenAPI JSON에서 만든다. 현재 handoff는 아직 존재하지 않는 API client를 완성품으로 주장하지 않는다.
