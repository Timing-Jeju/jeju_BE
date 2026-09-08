# 날씨 예보 API 계약 2.0.0

Issue #94가 확정하는 Spring 공개 API `GET /api/v1/weather/forecast`의 canonical 계약입니다. machine 기준은 같은 디렉터리의 `contract.json`이며 공통 인증·Problem Details는 Issue #72의 `timing-jeju-rest-contract/v1`을 상속합니다. 위치 비수집 정책은 #220, selector 구현 소유자는 #222입니다.

## 소유권과 readiness

Spring만 공개 API·JWT·정규화 조회를 소유합니다. 공개 지역/장소를 명시 선택한
요청은 익명으로 사용할 수 있고, `tripItemId`는 로그인한 JWT `sub`의 계획만 조회합니다.
잘못된 token은 항상 401이며 다른 owner 항목은 존재를 드러내지 않는 404입니다.

[historical-v1.contract.json](historical-v1.contract.json)은 이전 1.0.0 계약과
Notion/Figma/구현 readback 이력을 보존합니다. 이 근거를 v2 승인으로 재사용하지 않습니다.
새 selector·소유권·오류 코드의 page/node readback과 #222 구현이 검증되기 전
metadata/example/implementation은 모두 `not-ready`, 외부 버전은 `not-linked`입니다.

## 요청과 시간 경계

`regionCode | placeId | tripItemId` 중 정확히 하나와 `dateTime`을 받습니다.
selector와 시각은 null이 될 수 없고 unknown query는 거부합니다.
GPS를 자동 변환한 지역/최근접 장소 ID도 보내지 않습니다.

| 필드 | 계약 |
| --- | --- |
| `regionCode` | 사용자가 명시 선택한 공개 지역 코드, 1~50자 |
| `placeId` | 사용자가 명시 선택한 canonical public place UUID |
| `tripItemId` | JWT owner의 기존 계획 항목 UUID |
| `dateTime` | RFC 3339 `+09:00`, Asia/Seoul 정시, seconds `00` |

예: `GET /api/v1/weather/forecast?regionCode=seongsan&dateTime=2026-08-03T14:00:00%2B09:00`

요청 접수 시각을 Asia/Seoul 정시로 내린 값부터 10일 뒤 같은 정시까지 지원합니다. 0~6시간은 `ultra_short`, 6시간 초과~10일은 `village`입니다. 과거나 10일 초과는 422 `WEATHER_FORECAST_HORIZON_NOT_SUPPORTED`입니다. 성공 `validAt`은 요청한 `dateTime`과 정확히 같습니다.

## KMA 격자·base·version

Issue #42의 공식 DFS 5 km Lambert conformal conic 변환을 재사용하고 각 투영축을 `floor(projectedCoordinate + 0.5)`로 반올림합니다. 유효 격자는 nx 1..149, ny 1..253이며 제주 지원 grid가 없으면 422 `WEATHER_LOCATION_NOT_SUPPORTED`입니다. 격자는 공개 지역 대표점·공개 장소·소유자 계획 anchor에서 내부 결정합니다. 사용자 위경도 및 GPS 파생 selector는 수신하지 않습니다.

- provider API version: `VilageFcstInfoService_2.0`
- provider guide version: `2607`
- 저장 enum은 canonical migration의 `ultra_short | short`를 유지합니다. 공개 응답은 `ultra_short | village`이며 Spring 구현 #67은 DB `ultra_short`를 API `ultra_short`로, DB `short`를 API `village`로 정확히 변환합니다. 공개 응답에 `short`를 노출하거나 #94에서 schema migration을 추가하지 않습니다.
- 초단기: 매시 `HH:30`, 15분 발표 지연 후 선택
- 동네예보: 02·05·08·11·14·17·20·23시, 10분 발표 지연 후 선택
- 항상 발표 지연이 지난 최신 eligible base만 먼저 선택합니다.

## 응답·category presence

응답은 추가 필드를 금지하는 closed object입니다. `provider`, `providerApiVersion`, base/valid 시각과 `observedAt`, `expiresAt`, `stale`, `fallbackUsed`를 항상 반환합니다.

`TMP/T1H`, `POP`, `PCP/RN1`, `PTY`, `SKY`, `REH`, `WSD`는 각각 temperature, 강수확률, 강수량, 강수형태, 하늘상태, 습도, 풍속으로 projection합니다. 일곱 category-derived key는 모두 required이면서 nullable입니다. 선택 operation이 제공하지 않는 category는 명시적 JSON `null`이고 key 생략은 금지합니다. raw KMA category와 원문 payload는 공개 응답에 포함하지 않습니다. 선택 operation의 필수 집계 category가 빠진 base는 사용할 수 없으며 fallback을 적용합니다.

`observedAt`은 선택 행의 versioned snapshot `fetched_at`, `expiresAt`은 operation TTL 경계입니다. `stale = response assembly time >= expiresAt`입니다. 최신 base가 없거나 불완전하면 직전 eligible base를 정확히 한 번만 시도합니다. 성공하면 `fallbackUsed=true`, `stale=true`; 직전 base도 실패하면 503 `WEATHER_FORECAST_UNAVAILABLE`입니다. request-time KMA 호출은 하지 않습니다.

## 오류·보안

endpoint status/code는 다음만 허용합니다.

| Status | Code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_WEATHER_SELECTOR` | selector 누락·복수·형식·unknown 필드·KST 형식·정시 위반 |
| 401 | `AUTHENTICATION_REQUIRED` | tripItemId 조회에 인증 없음 |
| 404 | `WEATHER_REFERENCE_NOT_FOUND` | 계획/장소 참조 없음 또는 다른 owner |
| 401 | `INVALID_ACCESS_TOKEN` | optional token을 보냈으나 invalid/expired |
| 422 | `WEATHER_LOCATION_NOT_SUPPORTED` | KMA/제주 지원 grid 밖 |
| 422 | `WEATHER_FORECAST_HORIZON_NOT_SUPPORTED` | 과거 또는 10일 초과 |
| 503 | `WEATHER_FORECAST_UNAVAILABLE` | 최신·직전 base 모두 소진 |

오류 body는 `type,title,status,detail,instance,code,traceId,fieldErrors` 정확 8필드이며 `title/detail`은 한국어입니다. raw token, 이메일, user_metadata, service key, 정밀 좌표, provider query/payload를 응답·로그에 남기지 않습니다.

## DB projection과 schema gap

`weather_grid_points → weather_forecasts → external_api_snapshots/data_import_runs` 계보를 read-only로 조회합니다. `weather_forecasts`에는 `expires_at`과 provider version 컬럼이 없으므로 Issue #67이 snapshot/base 정책에서 안전하게 파생하거나 명시적 migration을 소유해야 합니다. `forecast_type`의 저장 enum `short`는 공개 enum `village`로 projection하며 schema 변경 없이 읽습니다. 이 Issue는 Controller·DB schema·FastAPI를 변경하지 않습니다. `supabase/migrations`만 public schema의 기준입니다.

검증 fixture는 `fixtures/contracts/weather-forecast`에 있으며 RDB API 예시는 `docs/designs/timing-jeju-backend-rdb-api-spec.md`에 같은 v2 selector 계약으로 projection합니다.

```bash
python3 -m unittest scripts.tests.test_weather_forecast_contract
python3 scripts/validate_weather_forecast_contract.py
python3 scripts/validate_rest_contracts.py
```
