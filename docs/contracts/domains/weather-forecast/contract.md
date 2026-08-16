# 날씨 예보 API 계약

Issue #94가 확정하는 Spring 공개 API `GET /api/v1/weather/forecast`의 canonical 계약입니다. machine 기준은 같은 디렉터리의 `contract.json`이며 공통 인증·Problem Details는 Issue #72의 `timing-jeju-rest-contract/v1`을 상속합니다. 구현 소유자는 Issue #67입니다.

## 소유권과 readiness

- Spring Boot만 공개 endpoint, optional Supabase JWT 검증, 정규화 DB 조회와 응답을 소유합니다. FastAPI는 endpoint·DB·JWT·KMA key를 소유하지 않습니다.
- 날씨는 사용자 소유 리소스가 아닙니다. Authorization 생략은 anonymous, 전달한 token이 invalid/expired이면 401입니다. 권한 판단이 필요한 미래 확장은 canonical JWT `sub`만 사용합니다.
- Notion page `3a40a87c-7ce5-816b-a8f7-ed2027e94b8c`는 같은 GET path, version `v1.1`, Spec Status `Ready`, Auth `Optional`, 화면 `장소 상세 / 일정 날씨`, DB `weather_grid_points/weather_forecasts`를 기록하지만 response가 `grid, forecastedAt, validAt, temp, POP, precipitation, wind, dataFreshness`만 가진 오래된 부분 계약이라 로컬 `1.0.0`과 일치하지 않습니다. 따라서 상태는 `drift-blocked`이며 PM/user 권한으로 Notion을 정렬하기 전 readiness를 승격하지 않습니다.
- Figma file `4mKep38zm17iupVSQVsSJW`에는 section node `622:10382` 근처 intent node `622:19945`의 “여행 당일에 날씨 정보를 보고 일정을 바꿀 수 있게”만 확인됐습니다. 실제 response field와 loading/empty/error node 연결은 없으므로 `not-ready/not-linked`를 유지합니다.

## 요청과 시간 경계

`lat`, `lng`, `dateTime` 세 query는 동시에 required/non-null입니다. 알 수 없는 query는 거부합니다.

| 필드 | 계약 |
| --- | --- |
| `lat` | finite number, -90 exclusive..90 exclusive |
| `lng` | finite number, -180..180 inclusive |
| `dateTime` | RFC 3339 `+09:00`, Asia/Seoul 정시, seconds `00` |

요청 접수 시각을 Asia/Seoul 정시로 내린 값부터 10일 뒤 같은 정시까지 지원합니다. 0~6시간은 `ultra_short`, 6시간 초과~10일은 `village`입니다. 과거나 10일 초과는 422 `WEATHER_FORECAST_HORIZON_NOT_SUPPORTED`입니다. 성공 `validAt`은 요청한 `dateTime`과 정확히 같습니다.

## KMA 격자·base·version

Issue #42의 공식 DFS 5 km Lambert conformal conic 변환을 재사용하고 각 투영축을 `floor(projectedCoordinate + 0.5)`로 반올림합니다. 유효 격자는 nx 1..149, ny 1..253이며 제주 지원 grid가 없으면 422 `WEATHER_LOCATION_NOT_SUPPORTED`입니다. 정밀 위경도 자체는 영구 저장하거나 로그·metric tag에 남기지 않습니다.

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
| 400 | `INVALID_WEATHER_FORECAST_QUERY` | 필수값·타입·WGS84 범위·KST 형식·정시 위반 |
| 401 | `INVALID_ACCESS_TOKEN` | optional token을 보냈으나 invalid/expired |
| 422 | `WEATHER_LOCATION_NOT_SUPPORTED` | KMA/제주 지원 grid 밖 |
| 422 | `WEATHER_FORECAST_HORIZON_NOT_SUPPORTED` | 과거 또는 10일 초과 |
| 503 | `WEATHER_FORECAST_UNAVAILABLE` | 최신·직전 base 모두 소진 |

오류 body는 `type,title,status,detail,instance,code,traceId,fieldErrors` 정확 8필드이며 `title/detail`은 한국어입니다. raw token, 이메일, user_metadata, service key, 정밀 좌표, provider query/payload를 응답·로그에 남기지 않습니다.

## DB projection과 schema gap

`weather_grid_points → weather_forecasts → external_api_snapshots/data_import_runs` 계보를 read-only로 조회합니다. `weather_forecasts`에는 `expires_at`과 provider version 컬럼이 없으므로 Issue #67이 snapshot/base 정책에서 안전하게 파생하거나 명시적 migration을 소유해야 합니다. `forecast_type`의 저장 enum `short`는 공개 enum `village`로 projection하며 schema 변경 없이 읽습니다. 이 Issue는 Controller·DB schema·FastAPI를 변경하지 않습니다. `supabase/migrations`만 public schema의 기준입니다.

검증 fixture는 `fixtures/contracts/weather-forecast`에 있으며 RDB API 예시는 `docs/designs/timing-jeju-backend-rdb-api-spec.md`에 같은 `contractVersion=1.0.0`으로 projection합니다.

```bash
python3 -m unittest scripts.tests.test_weather_forecast_contract
python3 scripts/validate_weather_forecast_contract.py
python3 scripts/validate_rest_contracts.py
```
