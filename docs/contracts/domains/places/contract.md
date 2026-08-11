# 관광지 검색·상세 API 계약 v1.1

이 문서는 Issue #83이 소유하는 `GET /api/v1/places`와 `GET /api/v1/places/{placeId}`의 구현 전 기준입니다. machine-readable 기준은 같은 디렉터리의 [`contract.json`](contract.json)이며 공통 envelope·Authorization·cursor·Problem Details는 `timing-jeju-rest-contract/v1`(contract version `1.0.0`)을 상속합니다. 기존 Notion 명세와 이 문서의 source spec revision은 `v1.1`입니다. 두 버전은 역할이 다르므로 서로 치환하지 않습니다.

Controller·Service·Repository·OpenAPI 구현 소유자는 #66이고 이 문서는 schema나 Flyway를 추가하지 않습니다. Spring이 공개 API와 DB 조회를 소유하며 요청 시 TourAPI·TAGO·FastAPI를 호출하지 않습니다.

## 추적성

| 출처 | 식별자 | 연결 |
| --- | --- | --- |
| Notion DB | `40914d1e-551f-4cfc-9604-0190ecda7b6c` | [Timing Jeju Spring REST API 명세 v1.1](https://app.notion.com/p/40914d1e551f4cfc96040190ecda7b6c?pvs=204) |
| Notion 목록 | `3a40a87c-7ce5-8107-98bf-fdb79281852b` | [`GET /api/v1/places`](https://app.notion.com/p/3a40a87c7ce5810798bffdb79281852b?pvs=204) |
| Notion 상세 | `3a40a87c-7ce5-81c4-9339-cb8d061b602d` | [`GET /api/v1/places/{placeId}`](https://app.notion.com/p/3a40a87c7ce581c49339cb8d061b602d?pvs=204) |
| Figma | file `4mKep38zm17iupVSQVsSJW`, node `251-4347` | [관광데이터 공모전](https://www.figma.com/design/4mKep38zm17iupVSQVsSJW/%EA%B4%80%EA%B4%91%EB%8D%B0%EC%9D%B4%ED%84%B0-%EA%B3%B5%EB%AA%A8%EC%A0%84?node-id=251-4347&p=f&t=DDs4My39PfbNrfZT-0) |

Figma의 지도 검색·카테고리·내 근처 화면은 목록 API를 소비합니다. 장소 카드 loading은 skeleton, 결과 없음은 “조건에 맞는 장소가 없습니다”, 오류는 재시도 가능한 한국어 Problem Details 안내로 처리합니다. 상세·이미지·이용정보·주변 정류장 화면은 상세 API를 소비합니다. 정류장이 없어도 장소 상세을 유지하고 `nearbyStops: []`만 표시합니다.

Notion은 이 작업에서 읽기만 했습니다. 두 행의 기존 `v1.1`, Optional, Spring Boot, Sync metadata를 로컬에 추적했으며 외부 페이지를 변경하지 않았습니다.

## 공통 인증과 개인화 shape

두 endpoint는 Optional 인증입니다.

- Authorization이 없으면 익명 요청으로 처리합니다. 목록 카드는 `saved=false`, `memo=null`, `tags=[]`, 상세은 `saved={value:false,memo:null,tags:[]}`를 반환합니다.
- 유효한 JWT가 있으면 canonical `sub`로만 `saved_places`를 조회해 같은 shape에 값을 채웁니다.
- 잘못된 token은 `401 INVALID_ACCESS_TOKEN`입니다.
- raw token, 이메일, provider 원문, service role 또는 외부 API key를 응답·로그·metric tag에 남기지 않습니다.

## `GET /api/v1/places`

### query와 범위

| 필드 | 필수 | null | 생략 | 계약 |
| --- | --- | --- | --- | --- |
| `query` | 아니오 | 불가 | 전체 이름·별칭 | trim 후 1~100자 |
| `category` | 아니오 | 불가 | 모든 카테고리 | `^[a-z][a-z0-9_]{0,49}$` |
| `regionCode` | 아니오 | 불가 | 모든 제주 지역 | `^[a-z0-9][a-z0-9_-]{0,49}$` |
| `lat` | 아니오 | 불가 | 거리 정렬 미사용 | -90~90, `lng`와 함께 입력 |
| `lng` | 아니오 | 불가 | 거리 정렬 미사용 | -180~180, `lat`와 함께 입력 |
| `radiusMeters` | 아니오 | 불가 | 좌표가 있으면 10000 | 100~50000, 좌표와 함께 입력 |
| `cursor` | 아니오 | 불가 | 첫 page | 최대 2048자의 opaque 값 |
| `size` | 아니오 | 불가 | 20 | 1~100 |

좌표가 있으면 `distanceMeters ASC NULLS LAST, normalizedName ASC, placeId ASC`, 없으면 `normalizedName ASC, placeId ASC`로 정렬합니다. 고유 tie-breaker는 항상 `placeId ASC`입니다. cursor에는 query/category/regionCode/lat/lng/radiusMeters/size/sort profile fingerprint가 귀속됩니다. cursor 발급 뒤 하나라도 바뀌면 재사용하지 않고 `400 CURSOR_CONTEXT_MISMATCH`를 반환합니다.

### 목록 shape

목록 카드는 `placeId`, 이름·category·region, location, `thumbnailUrl`, `recommendedStayMinutes`, `operationsSummary`, 개인화 `saved/memo/tags`를 포함합니다. `recommendedStayMinutes`, 대표 이미지와 운영 요약은 상세과 같은 read snapshot을 사용합니다. 값이 없으면 `recommendedStayMinutes`, `thumbnailUrl`, `operationsSummary`, `memo`는 null이며 임의 기본값을 만들지 않습니다.

## `GET /api/v1/places/{placeId}`

`placeId`는 canonical UUID이며 path field는 required/non-null입니다. 장소가 없으면 `404 PLACE_NOT_FOUND`입니다. 상세은 장소 공통 필드와 `overview`, `contact`, `operations`, `images`, `nearbyStops`, 개인화 `saved` 객체를 추가합니다.

- `recommendedStayMinutes`: TourAPI 원천이 아니라 Timing Jeju curated 값입니다.
- `thumbnailUrl`: `images`의 가장 앞선 display order thumbnail과 같거나 둘 다 null입니다.
- `operationsSummary`: 같은 snapshot의 `operations`에서 파생하며 source가 없으면 null입니다.
- `images`, `operations`는 TourAPI 정규화 read model만 사용합니다.

## 필드 소유권과 freshness

| 그룹 | owner | provider | observedAt | expiresAt | stale |
| --- | --- | --- | --- | --- | --- |
| 장소 핵심 | TourAPI 정규화 | `tour_places.source_provider` | `source_modified_at` 또는 `updated_at` | 수집 freshness 투영 | `tour_places.stale` |
| 체류시간 | Timing Jeju curated | `TIMING_JEJU` | `tour_places.updated_at` | null | false |
| 이미지 | TourAPI 정규화 | `place_images.source_provider` | `created_at` | 부모 장소 freshness 투영 | 부모 장소 stale 투영 |
| 운영정보 | TourAPI 정규화 | `place_details.source_provider` | `source_updated_at` 또는 `fetched_at` | 부모 장소 freshness 투영 | 부모 장소 stale 투영 |
| saved/memo/tags | 사용자 입력 | `TIMING_JEJU` | `saved_places.updated_at` | null | false |
| 주변 정류장 | TAGO 정류장 + 앱 link | `place_stop_links.source_provider` | `observed_at` | `expires_at` | `expires_at <= now()` |

공개 응답은 정규화 값만 반환합니다. 원천 response payload를 노출하지 않습니다.

## `nearbyStops` additive extension

`nearbyStops`는 #66 contract version부터 항상 존재하는 null 아닌 배열입니다. 항목은 다음 필드를 정확히 가집니다.

```json
{
  "stopId": "30000000-0000-0000-0000-000000000002",
  "stopName": "성산일출봉입구",
  "distanceMeters": 280,
  "walkMinutes": 4,
  "linkMethod": "spatial_radius",
  "provider": "TAGO",
  "observedAt": "2026-08-03T09:00:00+09:00",
  "expiresAt": "2026-08-04T09:00:00+09:00",
  "stale": false
}
```

`walkMinutes`만 null일 수 있습니다. 음수 거리·도보시간, 빈 provider, 시각 누락, `expiresAt < observedAt`, 같은 `stopId` 중복은 구현 validation에서 거부합니다.

eligible 조건은 아래를 모두 만족해야 합니다.

1. `place_stop_links.enabled=true`
2. link `tombstoned_at IS NULL`
3. 연결된 `bus_stops.tombstoned_at IS NULL`
4. 연결된 `bus_stops.source_deleted_at IS NULL`
5. configured 거리 상한 이내

eligible 행은 `expiresAt > now()`이면 fresh, `expiresAt <= now()`이면 stale입니다. stale-only도 `stale=true`로 포함합니다. disabled·tombstoned·out-of-radius와 tombstoned/source-deleted stop은 limit 전에 제외합니다. eligible fresh/stale이 하나도 없을 때만 상세 `200`과 `nearbyStops: []`를 반환합니다.

정렬은 `stale ASC`, `distanceMeters ASC`, `walkMinutes ASC NULLS LAST`, `stopId ASC`이며 stopId당 한 번, 전체 최대 5개입니다. 별도 freshness reason 필드는 만들지 않습니다. 기존 consumer가 알 수 없는 additive field를 무시할 수 있어야 합니다.

#37은 `place_stop_links.enabled/source_provider/observed_at/expires_at/tombstoned_at`, lifecycle check, partial index와 batch writer를 소유합니다. #66은 이를 read-only로 투영하고 Controller·Repository·OpenAPI·통합 테스트를 소유합니다. #66 evidence가 없으므로 현재 기본 계약은 Metadata/Example Ready이고 extension Implementation Ready는 아닙니다.

## 오류 matrix

| status | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_QUERY_PARAMETER` | 형식·타입·길이 오류 |
| 400 | `INVALID_GEO_FILTER` | lat/lng pair 또는 radius 범위 오류 |
| 400 | `CURSOR_CONTEXT_MISMATCH` | cursor 발급 필터와 현재 필터 불일치 |
| 400 | `INVALID_CURSOR` | 위변조·만료·decode 실패 cursor |
| 401 | `INVALID_ACCESS_TOKEN` | Optional endpoint에 잘못된 token 제공 |
| 404 | `PLACE_NOT_FOUND` | 상세 대상 UUID가 없음 |
| 422 | `PLACE_QUERY_CONSTRAINT_VIOLATION` | 형식은 맞지만 도메인 검색 제약 위반 |
| 429 | `UPSTREAM_RATE_LIMITED` | 저장된 fallback도 없고 후속 수집 quota가 소진됨 |
| 503 | `PLACE_DATA_UNAVAILABLE` | 정규화 read model을 안전하게 제공할 수 없음 |

eligible 주변 정류장이 없는 상태는 오류가 아닙니다. request-time 외부 호출을 하지 않으므로 단순 stale link 존재만으로 429/503을 반환하지 않습니다.

한국어 Problem Details 예시:

```json
{
  "type": "https://api.timing-jeju.com/problems/invalid-geo-filter",
  "title": "요청 위치 조건이 올바르지 않습니다",
  "status": 400,
  "detail": "위도와 경도는 함께 입력하고 반경은 100m 이상 50000m 이하로 입력해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INVALID_GEO_FILTER",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": [
    {
      "field": "radiusMeters",
      "reason": "범위는 100 이상 50000 이하여야 합니다"
    }
  ]
}
```

## 검증 명령

```bash
python3 -m unittest scripts.tests.test_places_contract
python3 scripts/validate_places_contract.py
python3 scripts/validate_rest_contracts.py
```
