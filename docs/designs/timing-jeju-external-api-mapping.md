# 타이밍제주 외부 API 연동 명세 v1.2

## 1. 검증 기준

- 검증일: 2026-07-30
- 외부 API 호출 주체: Spring Boot
- FastAPI MCP 직접 호출: 금지
- API key 저장: 서버 secret manager/env only
- 원문 payload 저장: `external_api_snapshots.raw_payload`에 장애 분석·재처리에 필요한 최소 범위만 저장하고 공개 API로 노출하지 않음

## 2. 결론

| 기능 | 1차 Source | 보조 Source | 계산/저장 |
| --- | --- | --- | --- |
| 관광지/숙소/음식점 기본정보 | TourAPI `KorService2` | 관리자 큐레이션 | Spring -> place tables |
| 버스 정류장/노선/경유 순서 | TAGO | 제주 보조 데이터 | Spring -> transit tables |
| 실시간 버스 도착 | TAGO | 최근 snapshot fallback | Spring -> arrival snapshots |
| 대중교통 경로 후보 | TMAP 대중교통 API 권장 | TAGO graph fallback | Spring facts -> FastAPI 선택/검증 |
| 자동차/렌터카 경로 | TMAP 자동차 경로 API 권장 | 공급자 adapter 교체 가능 | Spring -> mobility snapshots |
| 택시 시간/예상 요금 | TMAP 자동차 경로 응답 권장 | 별도 요금 정책 | Spring -> mobility snapshots |
| 도보 경로 | TMAP 보행자 경로 API 권장 | PostGIS 직선거리 보수 추정 | Spring -> mobility snapshots |
| 날씨 실황/예보 | KMA 단기예보 | 마지막 유효 예보 | Spring -> weather tables |
| 위험도/추천/복구 | 외부 API 아님 | - | FastAPI MCP 계산, Spring 저장 |

TMAP은 현재 설계 기본값이다. 실제 발급 계정의 상품/쿼터/약관과 제주 응답 품질을 POC로 통과해야 확정한다. `mobility_route_snapshots.source_provider`를 두어 공급자를 교체할 수 있게 했다.

### 2.1 raw 수집에서 정규화까지

```text
TourAPI · TAGO · KMA · 경로 공급자 raw 응답
  -> data_import_runs 실행/버전/건수 기록
  -> external_api_snapshots 원문·redaction 메타데이터·hash 보존
  -> schema/parser 검증 및 파싱
  -> 장소·교통·날씨 정규화 read model
```

- `data_import_runs`는 `parser_version`, `schema_version`, `sync_mode`, `scope_key`, `request_fingerprint`, `idempotency_key`, parent/retry, 전후 checkpoint와 fetched/inserted/updated/skipped/rejected/deleted/staled 건수를 기록한다. 상태는 `running`, `succeeded`, `failed`, `partial`, `cancelled`를 구분한다.
- `external_api_snapshots`는 provider/service/operation/scope, 요청·payload SHA-256, page, parser version과 `received`/`parsed`/`rejected`/`ignored`/`tombstoned` 상태를 보존한다. API key, Authorization 헤더, 원문 요청 URL과 PII는 저장하지 않는다.
- 검증을 통과한 정규화 행만 `import_run_id`와 `source_snapshot_id`를 남긴다. 실행 FK는 batch를, `source_snapshot_id`는 해당 행을 만든 실제 원문을 가리키는 lineage다.
- `data_import_checkpoints`는 provider/service/operation/scope별 마지막 성공 지점만 보존한다. 실패·부분 실행의 cursor는 승격하지 않고 정규화 upsert가 끝난 서버 트랜잭션에서 마지막으로 갱신한다.
- `weather_observations`, `weather_forecasts`, `bus_arrival_snapshots`, `mobility_route_snapshots`의 기존 `raw_payload` 컬럼은 호환을 위해 유지하지만 공통 원문 기준은 `external_api_snapshots`이며 신규 적재는 `source_snapshot_id`를 연결한다.
- 수집 내부 테이블은 RLS를 켜고 `anon`·`authenticated` policy/grant를 두지 않는다. 운영 적재의 서버 전용 `service_role`을 브라우저나 FastAPI MCP에 전달하지 않는다.

이번 이슈는 위 DB 기반과 계약만 추가한다. 실제 Spring importer, 주기 실행, retry/checkpoint 갱신과 retention job은 아직 구현하지 않았으며 별도 GitHub Issue에서 진행한다.

## 3. TourAPI

### 3.1 공식 서비스

- 공공데이터포털: [한국관광공사 국문 관광정보 서비스 GW](https://www.data.go.kr/data/15101578/openapi.do)
- 최신 서비스 base 안내: `http://apis.data.go.kr/B551011/KorService2`
- 형식: REST, JSON/XML
- 개발계정 기본 트래픽은 공공데이터포털 승인 조건을 따른다.

### 3.2 사용할 operation

한국관광공사의 [2026-01-02 공식 변경 공지](https://www.data.go.kr/bbs/ntc/selectNotice.do?originId=NOTICE_0000000004459)에 따라 2026-01-12 이후 지역·위치·키워드·행사·숙박·동기화 응답은 기존 `areaCode`, `sigunguCode`, `cat1`~`cat3` 대신 법정동·신분류 필드를 사용한다. 신규 importer는 아래 최신 operation과 필드를 기준으로 구현하고 기존 컬럼은 과거 snapshot 재처리 호환에만 사용한다.

| Operation | 용도 | DB |
| --- | --- | --- |
| `ldongCode2` | 법정동 시도·시군구 코드 동기화 | `external_reference_codes` |
| `lclsSystmCode2` | 관광 분류체계 코드 동기화 | `external_reference_codes` |
| `areaBasedList2` | 제주 지역 관광정보 batch 수집 | `external_api_snapshots` -> `tour_places`, `tour_place_sources` |
| `locationBasedList2` | 지도 중심 주변 장소 탐색 보강 | `external_api_snapshots` -> `tour_places`, `tour_place_sources` |
| `searchKeyword2` | 검색어 후보 탐색 | `tour_places`, `tour_place_sources`, `place_aliases` |
| `searchStay2` | 숙박 후보 | `tour_places`, `tour_place_sources` |
| `detailCommon2` | 공통 상세/개요 | `tour_places`, `place_details` |
| `detailIntro2` | 유형별 이용정보 | `place_details` |
| `detailInfo2` | 반복/부가 정보 | `place_detail_items` |
| `detailImage2` | 이미지 목록/저작권 코드 | `place_images` |
| `areaBasedSyncList2` | 변경분 동기화 | `data_import_runs`, `data_import_checkpoints`, snapshot 후 place upsert |

정확한 operation suffix와 필수 파라미터는 발급받은 최신 Swagger/활용 매뉴얼로 integration test에서 다시 고정한다.

### 3.3 필드 매핑

| TourAPI | DB | API | 비고 |
| --- | --- | --- | --- |
| `contentid` | `tour_place_sources.external_id`, `tour_places.content_id` | `contentId` | provider/service 범위 원천 식별자; `content_id`는 호환 read model |
| `contenttypeid` | `tour_place_sources.content_type_id`, `tour_places.content_type_id` | 내부 category로 변환 | 원천 유형 코드 |
| `lDongRegnCd`, `lDongSignguCd` | `tour_place_sources.l_dong_regn_cd`, `l_dong_signgu_cd`, `external_reference_codes` | 내부 지역 label | 2026-01-12 이후 법정동 코드 기준 |
| `lclsSystm1`~`lclsSystm3` | `tour_place_sources.lcls_systm1`~`lcls_systm3`, `external_reference_codes` | 내부 category | 2026-01-12 이후 관광 분류체계 기준 |
| legacy `areacode`, `sigungucode`, `cat1`~`cat3` | `tour_place_sources.area_code`, `sigungu_code`, `category_code_1`~`3` | 신규 API에 사용하지 않음 | 2026-01-12 이전 snapshot 재처리 호환만 유지 |
| `title` | `name`, `normalized_name` | `name` | normalized 값은 앱 생성 |
| `addr1`, `addr2` | `address`, `address_detail` | `address` | 원천 |
| `mapx`, `mapy` | `location` | `lng`, `lat` | WGS84 확인 후 PostGIS |
| `firstimage`, `firstimage2` | `tour_places.image_url`, `thumbnail_url` | image URL | 대표 이미지 read model |
| `overview` | `tour_places.overview` | `overview` | HTML 정제 필요 |
| 유형별 이용시간/휴무/주차 | `place_details.*_text` | `operations` | content type별 필드가 다름 |
| 반복 상세의 `serialnum` 등 | `place_detail_items.source_item_key`, `attributes` | 유형별 반복 항목 | item type/key별 멱등 upsert |
| 이미지 ID·명·저작권 | `place_images.source_image_id`, `image_name`, copyright/license 컬럼 | 이미지 목록 | ID가 없으면 provider/service+URL로 중복 방지 |
| `modifiedtime` | `tour_place_sources.source_modified_at` | 직접 노출 안 함 | 증분 동기화 및 lifecycle 판단 |

`detailIntro2`의 운영시간 원문 text는 `place_details`에 보존하고, 파싱·검수된 반복 영업시간은 `place_operating_hours`에 요일별 `interval_no`로 여러 건 저장한다. 유효기간은 `valid_from`/`valid_to`, 자정을 넘는 구간은 `spans_next_day`로 표현하며 같은 유효기간에 겹치는 영업 구간은 DB에서 거부한다. `detailInfo2`의 반복 부가 항목과 `detailImage2`의 여러 이미지는 각각 `place_detail_items`, `place_images`로 분리하고 모두 `source_snapshot_id` lineage를 갖는다.

### 3.4 TourAPI에서 직접 오지 않는 값

| 값 | 실제 Source |
| --- | --- |
| `recommendedStayMinutes` | 관리자 큐레이션 또는 FastAPI/통계 계산 |
| `regionLabel` | 지역코드 앱 매핑 |
| `saved`, `memo`, `tags`, `targetDay` | 사용자 입력 |
| 정규화된 주간 영업시간 | `detailIntro` text 파싱 + 관리자 검수 |
| 일정 가능성/점수/상태 | FastAPI MCP |
| 장소 간 이동시간 | 길찾기 공급자 |

음식점 content type은 활용하되 카페/신규 소규모 매장은 누락될 수 있다. `source_provider = admin_upload` 확장점을 유지한다.

### 3.5 캐시/동기화

아래 항목은 향후 Spring importer가 구현할 운영 정책이다.

- 제주 전체 기본 목록: 1일 1회 증분 동기화.
- 상세/이미지: 조회 시 lazy fetch 후 24시간 캐시.
- 삭제/변경: 동기화 목록 결과를 기반으로 stale 처리 후 검증 삭제.
- 검색 요청 중 TourAPI timeout 시 DB cache를 반환하고 `dataFreshness.stale`을 표시한다.

## 4. TAGO 버스

### 4.1 공식 서비스

- [버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do)
- [버스노선정보](https://www.data.go.kr/data/15098529/openapi.do)
- [버스도착정보](https://www.data.go.kr/data/15098530/openapi.do)
- 기존 TAGO API가 아니라 공공데이터포털의 신규 대체 서비스 URL을 사용한다.

### 4.2 정류소

```http
GET http://apis.data.go.kr/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList
  ?serviceKey=<encoded-key>
  &_type=json
  &gpsLati=33.458111
  &gpsLong=126.941516
```

공식 좌표 기반 operation은 반경 500m의 정류소를 반환한다. 서비스가 반환한 결과 외에 앱이 더 넓은 범위를 원하면 DB에 동기화한 정류소를 PostGIS로 검색한다.

| TAGO | DB |
| --- | --- |
| `nodeid` | `bus_stops.node_id` |
| `nodenm` | `bus_stops.node_name` |
| `nodeno` | `bus_stops.node_no` |
| `gpslati`, `gpslong` | `bus_stops.location` |
| 도시코드 | `bus_stops.city_code`, `external_reference_codes` |

정류장 natural key는 `nodeid` 단독이 아니라 `(source_provider, source_service, city_code, node_id)`다. `external_stop_id`를 받는 operation도 같은 provider/service/city 범위에서만 unique이며, 정규화 행은 원문 `external_api_snapshots`를 `source_snapshot_id`로 가리킨다.

### 4.3 노선과 경유 정류장

```http
GET http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteInfoIem
  ?serviceKey=<encoded-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &routeId=<route-id>
```

| TAGO | DB |
| --- | --- |
| `routeid` | `bus_routes.external_route_id` |
| `routeno` | `bus_routes.route_no` |
| `routetp` | `bus_routes.route_type` |
| 도시코드 | `bus_routes.city_code`, `external_reference_codes` |
| `startnodenm`, `endnodenm` | `direction_name`/route summary 확장 |
| 첫차/막차 | route summary 또는 별도 service window 확장 |
| 평일/토/일 배차간격 | route summary, 위험도 facts |
| 노선별 경유 정류장 목록 | `route_stops` |

도시코드는 하드코딩하지 않고 각 TAGO 서비스의 `도시코드 목록 조회` 결과를 `external_reference_codes`에 provider/service별로 적재한 뒤 환경 초기화 시 검증한다. 노선 natural key도 `(source_provider, source_service, city_code, external_route_id)` 범위다. `route_stops`는 노선·방향별 반복 경유 순서를 보존하고 snapshot lineage를 남긴다.

### 4.4 실시간 도착

```http
GET http://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList
  ?serviceKey=<encoded-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &nodeId=<node-id>
```

| TAGO | DB |
| --- | --- |
| `nodeid`, `nodenm` | stop reference/cache |
| `routeid`, `routeno`, `routetp` | route reference/cache |
| `arrprevstationcnt` | `remaining_stops` |
| `vehicletp` | `vehicle_type` |
| `arrtime` seconds | `estimated_arrival_seconds` |
| 수집 시각 | `observed_at` |
| 앱 TTL | `expires_at` |

도착 API의 동시 호출 제한과 쿼터를 고려해 같은 정류소 요청을 합치고 20~30초 single-flight cache를 적용한다.

### 4.5 TAGO가 보장하지 않는 값

| 값 | 처리 |
| --- | --- |
| 장소 A -> B 완성 대중교통 경로 | TMAP 대중교통 또는 FastAPI graph 탐색 |
| 정확한 모든 정류장 출발 시간표 | 제주 보조 데이터/관리자 적재가 있을 때만 `timetable_entries` 사용 |
| 도보 경로/시간 | TMAP 보행자 또는 보수 추정 |
| 환승 가능성/안전 버퍼 | FastAPI MCP 계산 |
| 버스를 놓쳤을 때 다음 일정 | FastAPI MCP 복구 계산 |

`timetable_entries`는 TAGO에서 항상 채워지는 테이블이 아니다. 첫차/막차/배차간격만 있는 노선은 별도 confidence와 source를 남긴다. 확보한 반복 시간표는 provider가 제공하거나 adapter가 안정적으로 만든 `source_record_key`와 `valid_from` 조합으로 멱등 upsert하고, `(route_id, direction_key, stop_id)` FK로 실제 경유 정류장에 속한 시간만 허용한다. 각 시간표 행도 `source_snapshot_id`로 원문을 추적한다.

## 5. TMAP 경로 API 설계 기본값

- 공식 포털: [SK open API](https://openapi.sk.com/)
- 사용할 상품 후보: TMAP 자동차, 보행자, 대중교통 경로.
- 역할: 경로 polyline, 거리, 예상 시간, 구간별 이동 정보, 가능한 경우 요금.
- TAGO 역할과 중복되지 않는다. TMAP은 경로 후보, TAGO는 정류장 기준 최신 도착을 담당한다.

### 5.1 정규화

모든 공급자 응답은 아래 형태로 변환해 `mobility_route_snapshots`에 저장한다.

```json
{
  "requestHash": "sha256:...",
  "transportMode": "public_transit",
  "origin": {
    "lat": 33.5066,
    "lng": 126.493
  },
  "destination": {
    "lat": 33.458111,
    "lng": 126.941516
  },
  "departureAt": "2026-08-03T09:20:00+09:00",
  "distanceMeters": 47000,
  "durationMinutes": 105,
  "estimatedFare": 3000,
  "sourceProvider": "tmap",
  "sourceOperation": "transit_route",
  "routeSummary": {
    "walkMinutes": 8,
    "waitMinutes": 12,
    "rideMinutes": 80,
    "transferMinutes": 5
  },
  "observedAt": "2026-08-03T09:19:50+09:00",
  "expiresAt": "2026-08-03T09:24:50+09:00"
}
```

DB 행은 위 정규화 값에 더해 `import_run_id`와 `source_snapshot_id`를 저장한다. 같은 `request_hash`라도 공급자·operation·관측 시각이 다르면 별도 snapshot이며, unique 범위는 `(source_provider, source_operation, request_hash, observed_at)`이다.

### 5.2 POC 통과 조건

- 제주공항 -> 성산일출봉 대중교통 경로가 반환된다.
- 성산일출봉 -> 섭지코지 도보/대중교통 경로가 반환된다.
- 렌터카 시간/거리와 택시 예상요금 필드 가용성을 확인한다.
- 과거/미래 출발시각 지원 범위를 확인한다.
- 응답 polyline 표시 및 저장/재사용 약관을 확인한다.
- 개발/운영 쿼터와 공모전 트래픽 예상치를 비교한다.

POC 실패 시 adapter만 ODsay(대중교통) + 자동차/도보 공급자로 교체한다. DB와 FastAPI 계약은 변경하지 않는다.

## 6. 기상청 단기예보

### 6.1 공식 서비스

- [기상청 단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do)
- Base: `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0`
- 제공: 초단기실황, 초단기예보, 단기예보, 예보버전.
- 좌표: 위경도가 아니라 기상청 `nx`, `ny` 격자.

### 6.2 operation

| Operation | 목적 | DB |
| --- | --- | --- |
| `getUltraSrtNcst` | 현재 실황 | `weather_observations` |
| `getUltraSrtFcst` | 수시간 이내 예보 | `weather_forecasts` |
| `getVilageFcst` | 여행 일정 단기예보 | `weather_forecasts` |
| `getFcstVersion` | 예보 버전 감사 | `external_api_snapshots`, import metadata |

Example:

```http
GET http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst
  ?ServiceKey=<encoded-key>
  &dataType=JSON
  &pageNo=1
  &numOfRows=1000
  &base_date=20260803
  &base_time=0500
  &nx=60
  &ny=37
```

### 6.3 category 매핑

| KMA category | DB |
| --- | --- |
| `TMP`, `T1H` | `temperature_c` |
| `POP` | `precipitation_probability_percent` |
| `PCP`, `RN1` | `precipitation_amount_mm` |
| `PTY` | `precipitation_type` |
| `SKY` | `sky_code` |
| `REH` | `humidity_percent` |
| `WSD` | `wind_speed_mps` |
| `TMN`, `TMX` | min/max temperature |

강수량은 `강수없음`, `1mm 미만` 같은 문자열일 수 있으므로 parser 버전과 원문 category를 보존한다.

### 6.4 저장 여부

날씨는 저장한다.

- 외부 API 반복 호출과 쿼터를 줄인다.
- 어떤 예보로 위험도를 계산했는지 재현한다.
- 예보 갱신 전후 계산 결과를 구분한다.
- UI 조회와 일정 계산이 같은 facts를 사용한다.

예보 자체는 `weather_forecasts`, 일정별 영향은 FastAPI 계산 후 `trip_weather_impacts`에 분리 저장한다.

KMA 원문은 먼저 `external_api_snapshots`에 저장하고, category 파싱을 통과한 `weather_observations`와 `weather_forecasts`가 해당 `source_snapshot_id`를 가리킨다. parser version을 snapshot에 남기므로 category 문자열 해석 규칙이 바뀌어도 같은 원문을 재처리할 수 있다.

## 7. Source of Truth 표

| API 응답 필드 | Source 유형 | 원천/계산 주체 |
| --- | --- | --- |
| 장소명, 주소, 좌표, 이미지, 개요 | `external` | TourAPI |
| 이용시간/휴무/주차 text | `external` | TourAPI detailIntro |
| 추천 체류시간 | `curated/computed` | 관리자/FastAPI 정책 |
| 관심 여부, 메모, 태그 | `user_input` | 사용자 |
| 정류장 ID/명/좌표 | `external` | TAGO |
| 노선 번호/유형/경유 순서 | `external` | TAGO |
| 버스 도착예정 초/남은 정류장 | `external_snapshot` | TAGO |
| 정확한 대기시간 | `computed` | FastAPI + arrival facts |
| 자동차/도보/대중교통 이동시간 | `external_snapshot` | TMAP adapter |
| 위험도/점수/leave-by | `computed` | FastAPI MCP |
| 날씨 실황/예보 | `external_snapshot` | KMA |
| 날씨 일정 영향 | `computed` | FastAPI MCP |
| 복구안/변경 diff | `computed` | FastAPI MCP |
| 자연어 설명 | `ai_generated` 또는 template | FastAPI/Spring fallback |

## 8. TTL/Fallback

| 데이터 | 정상 TTL | 허용 fallback | fallback 표시 |
| --- | --- | --- | --- |
| TourAPI 목록/상세 | 24시간 | 최근 7일 | `stale=true` |
| 정류장/노선/경유 순서 | 24시간 | 최근 7일 | `stale=true` |
| 실시간 버스 도착 | 20~30초 | 최대 2분 | `STALE_TRANSIT_DATA` |
| 자동차/택시 경로 | 5분 | 최대 30분 | confidence 하향 |
| 도보 경로 | 24시간 | PostGIS 보수 추정 | `ESTIMATED_WALK_TIME` |
| 대중교통 경로 | 5분 | 최근 경로 + TAGO 보정 | `STALE_ROUTE_PLAN` |
| 초단기실황 | 10분 | 최근 30분 | `STALE_WEATHER_DATA` |
| 단기예보 | 같은 발표 base | 이전 발표 1회 | `STALE_WEATHER_DATA` |

`danger` 판단에 필수인 facts가 fallback 한도를 넘으면 계산하지 않고 `EXTERNAL_FACTS_UNAVAILABLE`을 반환한다.

## 9. Adapter 계약

Spring package boundary 예시:

```text
external.tour.TourApiClient
external.transit.TagoStopClient
external.transit.TagoRouteClient
external.transit.TagoArrivalClient
external.mobility.MobilityRouteProvider
external.weather.KmaForecastClient
```

`MobilityRouteProvider` 구현체만 `TmapMobilityRouteProvider`, `OdsayTransitProvider` 등으로 교체한다. Controller나 FastAPI 계약이 공급자 응답 DTO를 직접 참조하면 안 된다.

## 10. 후속 Spring importer Integration Test 체크리스트

- TourAPI `KorService2` 실제 키로 `ldongCode2`, `lclsSystmCode2`, 제주 keyword/location/detail/image를 호출한다.
- `areaBasedList2`, `locationBasedList2`, `searchKeyword2`, `searchStay2`, `areaBasedSyncList2`에서 `lDongRegnCd`, `lDongSignguCd`, `lclsSystm1`~`3`가 원문 snapshot과 장소 source에 손실 없이 보존되는지 확인한다.
- TourAPI 이미지가 없는 장소와 HTML overview를 처리한다.
- TAGO 도시코드 목록에서 제주 코드와 지원 여부를 런타임 확인한다.
- TAGO 제주 정류장, 노선, 경유 정류장, 도착 API의 ID가 서로 join된다.
- TAGO 빈 도착 결과와 error code 97/쿼터 초과를 처리한다.
- TMAP 세 모드 POC와 약관/쿼터를 확인한다.
- KMA 위경도 -> `nx`, `ny` 변환 golden test를 만든다.
- KMA base time 이전 발표 fallback과 category 문자열 parser를 검증한다.
- 모든 adapter가 timeout, retry, circuit breaker, metric을 가진다.
- raw snapshot 저장과 parser 검증이 성공한 뒤에만 정규화 upsert가 일어나고 모든 행의 `source_snapshot_id`가 같은 원문을 가리키는지 확인한다.
- 부분 실패·재시도·동시 실행에서도 provider/service/operation/scope checkpoint와 provider 범위 natural key가 중복되거나 앞당겨지지 않는지 확인한다.
