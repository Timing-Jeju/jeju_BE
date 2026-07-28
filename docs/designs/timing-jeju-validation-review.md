---
status: ACTIVE
---
# Validation Review: 타이밍제주

작성일: 2026-05-21  
역할: PM 세션 / 기술 검수자  
상태: 1차 검증 세션 통합 완료

## 목적

이 문서는 `docs/designs/timing-jeju-pm-validation-plan.md`의 1차 검증 작업 결과를 메인 세션에서 판정하고, MVP 포함 여부와 다음 구현 순서를 확정하기 위한 리뷰 문서다.

## 판정 기준

| 등급 | 의미 | PM 결정 |
|---|---|---|
| GO | 공식 문서와 최소 검증이 모두 맞다. | MVP에 포함한다. |
| GO_WITH_GUARDRAIL | 가능하지만 실패 조건이 명확하다. | fallback, cache, fixture, UI 고지를 붙여 포함한다. |
| DEFER | 가능성은 있으나 본선 MVP에 과하다. | Phase 2로 내린다. |
| NO_GO | 데이터/인증/비용/구현 리스크가 MVP를 깨뜨린다. | MVP에서 제거하고 대체안을 선택한다. |
| NEEDS_MORE_PROOF | 문서만 있고 실제 응답/샘플 검증이 부족하다. | 다음 검증 세션을 추가한다. |

## 1차 검증 결과 요약

| 작업 | 담당 세션 | PM 판정 | MVP 반영 | 필수 guardrail | 추가 검증 |
|---|---|---|---|---|---|
| TourAPI 키워드 검색 | Peirce | GO_WITH_GUARDRAIL | 포함 | seed + cache + 후보 선택 UI | 실제 service key로 대표 장소 4개 실호출 |
| 제주 버스 정류소 좌표/ID | Ramanujan | GO_WITH_GUARDRAIL | 포함 | "가까운 정류장 후보"로 표시 | 샘플 CSV/import로 WGS84 bbox 검증 |
| 제주 버스 시간표 XLSX import | Einstein | GO_WITH_GUARDRAIL | 포함 | "주요 시간표 지점 기준" 고지 | 101/201 XLSX 샘플 import POC |
| OpenAI structured output | Zeno | GO_WITH_GUARDRAIL | 포함 | 교통 사실 생성 금지 + fallback | schema/eval fixture 작성 |

## 현재 로컬 검증

`poc/timing-jeju-corridor-poc.mjs` 실행 결과:

```text
status: PASS_WITH_LIMITS
routeCoverageScore: 100
scheduleSafetyScore: 38
feasibilityScore: 81
missedPenaltyMin:
  제주공항 -> 함덕: 40
  함덕 -> 월정리: 42
  월정리 -> 성산: 48
```

PM 해석:

- 로컬 알고리즘은 성립한다.
- 단, DEMO route/timetable seed 기반이므로 실제 TourAPI와 제주 버스 데이터 import 검증 전에는 구현 착수 판정을 내리면 안 된다.

## 세션별 리뷰

### 01. TourAPI 키워드 검색

작업명: TourAPI 키워드 검색 API 요청/응답 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 중상. 공식 문서와 Swagger 명세 기반이지만, 현재 repo에는 service key가 없어 실호출은 아직 없다.  
MVP 반영: 포함. 장소 카드, 장소 후보 선택, 관광지 좌표 확보에 사용한다.  
필수 guardrail: 공항은 TourAPI 관광지가 아니라 고정 교통 거점 seed로 분리한다. 대표 장소는 seed + cache를 먼저 두고, TourAPI는 후보 보강과 동기화에 사용한다.  
추가 검증 필요: `TOUR_API_SERVICE_KEY` 확보 후 `함덕해수욕장`, `월정리해변`, `성산일출봉`, `제주국제공항` 실호출. `제주국제공항`은 TourAPI 후보가 약하면 seed만 사용한다.  
문서 반영 위치: `docs/designs/timing-jeju-eng-review.md`, `docs/designs/timing-jeju-tech-cost-validation.md`  
구현 backlog: `TourApiClient`, `TourPlaceMatcher`, `tour_places` cache table, candidate selection DTO, no-result/manual-place fallback.

PM 메모:

- `searchKeyword2`를 우선 검토한다.
- `areaCode/sigunguCode`는 삭제 예정 가능성이 있으므로 새 법정동 코드 파라미터(`lDongRegnCd`, `lDongSignguCd`)를 우선 확인한다.
- 짧은 키워드인 `함덕`, `월정리`, `성산`은 후보가 여러 개 나올 수 있으므로 자동 확정하지 않는다.

주요 출처:

- https://www.data.go.kr/data/15101578/openapi.do
- https://www.data.go.kr/tcs/dss/selectApiDataDetailView.do?publicDataPk=15101578

### 02. 제주 버스 정류소 좌표/ID

작업명: 제주 버스 정류소 데이터 좌표/ID 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 중상. 정류소 ID, 정류소명, 경도, 위도는 공식 데이터로 확보 가능하다. 다만 방향 정보는 데이터 소스별로 다르고 WGS84 명시는 실데이터 샘플로 추가 검증이 필요하다.  
MVP 반영: 포함. TourAPI 관광지 좌표 주변 정류장 후보 검색에 사용한다.  
필수 guardrail: 화면에서는 "확정 승차 정류장"이 아니라 "가까운 정류장 후보"로 표현한다. 실제 방향/노선 가능성은 노선/시간표 검증 결과와 결합한다.  
추가 검증 필요: 정류소 CSV 또는 자동변환 API 샘플을 가져와 SRID 4326으로 import하고, 제주 bbox 및 대표 관광지 주변 후보를 검증한다.  
문서 반영 위치: `docs/designs/timing-jeju-eng-review.md`, `docs/poc/timing-jeju-poc-report.md`  
구현 backlog: `TimetableImporter`와 별개로 `BusStopImporter`, `StopMatcher`, PostGIS `ST_DWithin`/`ST_Distance` integration test.

PM 메모:

- 기본 반경은 800m, 후보 없음이면 1,200m까지 확장한다.
- 직선거리 기반 도보 시간은 `거리 / 70m/min + 3~5분 안전버퍼`로 시작한다.
- 300m 초과 후보나 방향 불명확 후보는 지도 확인 배지를 붙인다.

주요 출처:

- https://www.data.go.kr/data/15074255/openapi.do
- https://www.data.go.kr/data/15010850/fileData.do
- https://infuser.odcloud.kr/oas/docs?namespace=15010850/v1

### 03. 제주 버스 시간표 XLSX import

작업명: 제주 버스 시간표 XLSX import 가능성 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 중. 공식 XLSX 다운로드 경로와 내부 JSON 흐름은 확인됐지만, XLSX가 사람용 표이므로 노선별 편차가 있다.  
MVP 반영: 포함. 출발 권장 시각, 놓침 위험, 다음 버스 대기시간 계산에 사용한다.  
필수 guardrail: "공식 시간표 주요 지점 기준"이라고 화면과 데이터 헬스에 명시한다. 모든 정류장의 exact schedule을 약속하지 않는다.  
추가 검증 필요: 101/201 노선 XLSX를 실제 다운로드해 Apache POI로 샘플 import하고, 월정리처럼 시간 컬럼이 없는 지점의 보정/제외 규칙을 확정한다.  
문서 반영 위치: `docs/designs/timing-jeju-eng-review.md`, `docs/designs/timing-jeju-tech-cost-validation.md`  
구현 backlog: `TimetableImporter`, `timetable_entries`, original XLSX hash 저장, 101/201 fixture 생성, `TimetableEngineTest`.

PM 메모:

- MVP는 101/201 중심 동쪽 코리도어로 시작한다.
- 월정리처럼 경로 문자열에는 있으나 시간표 컬럼이 없는 지점은 위험하다. 본선 데모에서는 이 제약을 숨기지 말고 "주요 지점 기준 + 보수 버퍼"로 처리한다.
- `getScheduleTableInfo` 같은 내부 JSON은 개발 검증에는 유용하지만 운영 의존도는 낮게 잡는다. 안정 경로는 XLSX 수동/배치 import다.

주요 출처:

- https://www.data.go.kr/data/3043887/fileData.do
- https://bus.jeju.go.kr/publicTrafficInformation/generalBusSchedule?viewtype=2
- https://bus.jeju.go.kr/data/schedule/downScheduleExcel?gscheduleId=405001

### 04. OpenAI structured output

작업명: OpenAI Responses API와 Structured Outputs 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 상. 공식 OpenAI 문서 기준으로 Responses API와 Structured Outputs 적용이 가능하다.  
MVP 반영: 포함. 자연어 일정 파싱과 계산 결과 설명에 사용한다.  
필수 guardrail: OpenAI는 버스 번호, 출발/도착 시각, 정류장 ID, TourAPI contentId, 좌표, 위험도 점수, route feasibility를 절대 생성하지 않는다.  
추가 검증 필요: JSON schema 초안과 20개 한국어 일정 prompt eval fixture를 작성하고, schema violation 및 forbidden field 테스트를 만든다.  
문서 반영 위치: `docs/designs/timing-jeju-eng-review.md`, `docs/designs/timing-jeju-tech-cost-validation.md`  
구현 backlog: `AiIntentParser`, `AiExplanationService`, `AiSchemaValidator`, deterministic Korean fallback templates, `OPENAI_MODEL` env var.

PM 메모:

- 프론트엔드는 OpenAI를 직접 호출하지 않는다.
- live countdown tick, 지도 이동, 위험도 계산, TourAPI 카드 로딩, 데이터 헬스 화면에서는 OpenAI 호출 금지다.
- OpenAI 장애 시 자연어 입력은 수동 폼으로, 설명은 템플릿으로 fallback한다.

주요 출처:

- https://platform.openai.com/docs/api-reference/responses
- https://platform.openai.com/docs/guides/structured-outputs

## 통합 결정

1차 검증 결과 기준으로 아래와 같이 결정한다.

1. TourAPI 장소 매칭은 MVP 핵심 경로에 넣는다. 단, 대표 장소 seed와 cache를 반드시 둔다.
2. 정류장 매칭은 실제 제주 정류소 데이터 import + PostGIS 반경 검색으로 구현한다. 화면 문구는 후보형으로 둔다.
3. 버스 시간표는 자동 API 의존이 아니라 XLSX 수동/배치 import로 시작한다.
4. OpenAI는 자연어 입력 파싱과 설명 레이어에 모두 넣는다. 단, 계산 사실 생성은 금지한다.
5. 프론트엔드 fixture API 계약은 바로 만들 수 있다. API 장애 fallback과 demo mode를 계약에 포함한다.
6. 백엔드 Spring Boot skeleton 구현에 착수해도 된다. 단, 첫 구현 순서는 API skeleton이 아니라 data fixture + DTO + deterministic engine이다.

## MVP Scope After Validation

### Include

- Expo 앱
- Spring Boot REST API
- TourAPI 장소 후보 매칭
- 대표 장소 seed fallback
- 제주 정류소 import와 PostGIS 후보 검색
- 제주 동쪽 코리도어 101/201 중심 시간표 import
- 주요 시간표 지점 기반 leave-by, wait, missed penalty 계산
- 일정 안전도/실현 가능성 점수
- OpenAI structured parse
- OpenAI explanation over computed facts only
- deterministic fallback
- debug/data-health 화면

### Exclude From MVP

- 제주 전역 임의 대중교통 라우팅
- 실시간 버스 도착 정보 의존
- 모든 정류장 exact schedule 보장
- 네이티브 지도 SDK 필수 의존
- OpenAI가 경로 가능성/시간표/위험도 생성

## Next Verification Tasks

| 우선순위 | 작업 | 목적 | 산출물 |
|---:|---|---|---|
| 1 | 실제 API key 확보 여부 확인 | TourAPI/공공데이터 실호출 가능성 결정 | `.env.example` 초안, 필요한 key 목록 |
| 2 | API DTO 계약 초안 작성 | 프론트/백엔드 병렬 구현 준비 | `trip-plan-contract.md` |
| 3 | DB 스키마 초안 작성 | import/fixture 구조 고정 | `schema-v0.md` 또는 Flyway draft |
| 4 | 101/201 시간표 import POC | 시간표 제약을 코드로 확인 | Apache POI import spike |
| 5 | OpenAI schema/eval fixture | AI guardrail을 테스트로 고정 | JSON schema + 20 prompt fixtures |
| 6 | Expo fixture 화면 flow | API 이전에도 데모 가능하게 함 | fixture JSON + screen route list |

## 2차 검증 결과 요약

| 작업 | 담당 세션 | PM 판정 | MVP 반영 | 필수 guardrail | 추가 검증 |
|---|---|---|---|---|---|
| Expo 지도 전략 | Sagan | GO_WITH_GUARDRAIL | 포함 | 네이티브 지도 SDK 제외 | 딥링크 URL scheme smoke test |
| 모바일-서버 API DTO | Dewey | GO_WITH_GUARDRAIL | 포함 | `computed`/`ai` 분리 | OpenAPI/shared type 작성 |
| PostgreSQL/PostGIS 스키마 | Herschel | GO_WITH_GUARDRAIL | 포함 | fixture와 운영 데이터 동일 테이블 | Flyway draft 작성 |

### 05. Expo 지도 전략

작업명: Expo 앱 지도 표시와 경로 안내 UI 구현 전략 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 상. Expo에서 딥링크와 WebView는 현실적이고, 네이티브 지도 SDK는 본선 MVP에 과하다.  
MVP 반영: 포함. 단, 앱 내부 풀 지도는 만들지 않고 카드/타임라인 중심으로 간다.  
필수 guardrail: 네이티브 Kakao/Naver SDK는 MVP에서 제외한다. WebView 지도는 preview 전용이며, 지도 결과를 리스크 계산 근거로 쓰지 않는다.  
추가 검증 필요: Kakao/Naver 딥링크 URL scheme을 Expo `Linking.openURL`로 smoke test한다. Kakao WebView preview는 도메인 등록 가능성이 확인된 뒤 Phase 1.5로 둔다.  
구현 backlog: `MapContext` DTO, `OpenMapButton`, Kakao/Naver fallback URL, WebView preview optional route.

PM 메모:

- 본선 데모는 지도보다 `leave-by`, 놓침 리스크, 복구 옵션 계산이 핵심이다.
- 결과 화면은 장소 카드, 정류장 후보, 버스 탑승 시각, 놓쳤을 때 지연분을 우선한다.
- 각 leg에 `카카오맵에서 보기`, `네이버지도에서 보기`, `도보 경로 열기`, `대중교통 길찾기 열기` 버튼을 둔다.
- WebView는 마커와 정류장 후보 시각화만 허용한다.

### 06. 모바일-서버 API DTO

작업명: 타이밍제주 모바일 앱-서버 API DTO 초안 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 상. 현재 화면과 엔진 경계에 맞는 REST JSON 계약으로 병렬 구현이 가능하다.  
MVP 반영: 포함. 구현 전 shared contract로 고정한다.  
필수 guardrail: TourAPI 장소 후보와 버스 정류장 후보 DTO를 분리한다. 서버 계산값은 `computed`, AI 문장은 `ai`에만 둔다. fixture/demo mode도 동일 엔진 결과를 반환한다.  
추가 검증 필요: `OpenAPI` 또는 shared TypeScript type으로 계약을 파일화한다.  
구현 backlog: `POST /api/trips/parse`, `POST /api/trips/plan`, `GET /api/trips/{tripId}`, `POST /api/trips/{tripId}/live-state`, `POST /api/trips/{tripId}/recover`, `GET /api/debug/data-health`.

PM 메모:

- 공통 meta에는 `requestId`, `mode`, `fixtureScenarioId`, `dataVersion`, `generatedAt`, `warnings`를 둔다.
- `mode`는 `live | fixture | auto_fallback`으로 제한한다.
- 점수 필드는 서버 소유다. `routeCoverageScore=100`, `scheduleSafetyScore=38`, `feasibilityScore=81`을 POC 기준으로 재현해야 한다.
- 공통 error envelope는 `code`, `message`, `userAction`, `retryable`, `details`, `meta`를 가진다.

### 07. PostgreSQL/PostGIS 스키마

작업명: 타이밍제주 MVP DB 스키마 검증  
결론: GO_WITH_GUARDRAIL  
신뢰도: 상. MVP에 필요한 테이블과 PostGIS 사용 경계가 명확하다.  
MVP 반영: 포함. PostgreSQL + PostGIS는 필수다.  
필수 guardrail: 동쪽 코리도어 fixture와 운영 import 데이터가 같은 테이블과 같은 엔진 경로를 타야 한다. 별도 demo table은 금지한다.  
추가 검증 필요: Flyway draft와 PostGIS nearest-stop integration test를 만든다.  
구현 backlog: `data_import_runs`, `tour_places`, `bus_stops`, `bus_routes`, `route_stops`, `timetable_entries`, `trip_plans`, `trip_legs`, `risk_events`.

PM 메모:

- 위치 컬럼은 `geometry(Point, 4326)`을 기본으로 저장하고, 미터 단위 반경 검색은 `location::geography`를 사용한다.
- `data_import_runs`를 먼저 만든 뒤, 모든 import/fixture row가 `import_run_id`를 갖게 한다.
- 사용자 계정은 MVP에서 제외한다. `trip_plans`는 익명 `session_id`와 선택적 `share_token`으로 충분하다.
- confidence는 `high | medium | low`, stale 여부는 DB/API 데이터로 노출한다. AI 설명문에만 묻어두면 안 된다.

## 2차 통합 결정

1. 지도는 MVP 핵심 기술이 아니다. 네이티브 지도 SDK는 Phase 2로 내린다.
2. API 계약은 구현 전 고정한다. 특히 `computed`와 `ai` 분리는 신뢰 경계다.
3. DB는 9개 테이블로 시작한다. 테이블 수를 줄이기보다 데이터 출처/버전/신뢰도를 보존하는 것이 더 중요하다.
4. fixture mode는 별도 fake path가 아니라 동일 테이블, 동일 엔진, 다른 data scope로 처리한다.
5. 앱 구현은 API 계약과 fixture JSON이 나온 뒤 시작한다.

## PM Go/No-Go

판정: **GO_WITH_GUARDRAIL**

구현에 착수해도 된다. 단, 첫 sprint의 목표는 예쁜 앱 화면이 아니라 아래 3개 증거를 만드는 것이다.

1. 대표 장소가 seed/TourAPI/cache 중 하나로 항상 매칭된다.
2. 대표 장소 주변 정류장 후보가 PostGIS로 계산된다.
3. 101/201 중심 시간표 fixture/import로 leave-by와 missed penalty가 재현된다.

이 3개가 되면 프론트엔드 화면과 OpenAI 설명을 얹어도 된다. 반대로 이 3개가 안 되면 앱 UI가 완성돼도 본선 기술 질문에서 버티기 어렵다.
