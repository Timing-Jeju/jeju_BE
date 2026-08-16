# Issue #94 날씨 예보 API 계약 개발 기록

## 범위와 기준

- 기준: `origin/develop` `85e4a76ffdfc0694b2b8c26e92142c18411ae061`
- 브랜치: `docs/94-c13-api-contract`
- 포함: `GET /api/v1/weather/forecast` canonical 계약, fixture, validator, REST catalog/template projection, RDB API 예시
- 제외: Controller/Service/Repository, DB schema/migration/Flyway, FastAPI source/dependency
- 참조: 공통 계약 Issue #72, develop에 병합된 #42 KMA 격자/base resolver, 미병합 #43 importer의 category/fallback 개념. #43 코드에는 의존하지 않는다.

## Red → Green → Refactor

운영 계약보다 `scripts/tests/test_weather_forecast_contract.py`와 통합 suite 기대값을 먼저 추가했다.

```text
python3 -m unittest scripts.tests.test_weather_forecast_contract scripts.tests.test_contract_suite_integration
```

최초 결과는 `failures=3, errors=1`이었다. `contract.json`이 없어 `FileNotFoundError`, catalog에 날씨 endpoint가 없고 shell/PowerShell gate에 validator가 없다는 의도한 이유로 실패했다. 증거는 Issue #94 댓글 `issuecomment-5306505090`에 시간순으로 기록했다.

Green에서는 contract JSON/Markdown, request/success/problem fixture, fail-closed validator, catalog endpoint와 gate 연결, RDB 예시를 추가했다. 전용 16개 테스트와 통합 테스트, 전용/common validator가 통과했다.

Refactor에서는 query schema를 실제 경계값으로 검사하고 category-derived key가 explicit null은 허용하지만 omitted은 거부되는지를 재귀 fixture 검사로 보강했다. 기존 전체 계약 테스트 189건도 성공했다.

## 확정 계약

- `lat/lng/dateTime`은 동시에 required/non-null이고 추가 query와 cursor는 금지한다.
- 좌표는 finite WGS84 범위(lat -90/90 exclusive, lng -180/180 inclusive)이며 KMA DFS 투영축을 `floor(value + 0.5)`로 반올림한다. nx 1..149, ny 1..253 및 제주 지원 grid 밖은 422다.
- `dateTime`은 Asia/Seoul `+09:00` RFC 3339 정시다. 현재 정시~6시간은 `ultra_short`, 그 초과~10일은 `village`, 밖은 422다.
- provider API는 `VilageFcstInfoService_2.0`, guide version은 `2607`; 초단기는 매시 30분/15분 지연, village는 3시간 base/10분 지연이다.
- category-derived 7개 필드는 required nullable이다. operation 미제공 값은 explicit null, key 생략과 raw category 공개는 금지한다.
- `observedAt`, `expiresAt`, `stale`, `fallbackUsed`를 항상 반환한다. 최신 base 실패 시 직전 eligible base 정확히 1회, 성공은 fallback/stale true, 소진은 503 `WEATHER_FORECAST_UNAVAILABLE`이다.
- 인증은 optional이고 invalid token만 401이다. 날씨 fact는 사용자 owner가 없으며 요청 정밀 좌표·token·provider key/query/payload를 저장·로그·응답에 남기지 않는다.
- Problem은 한국어 `type,title,status,detail,instance,code,traceId,fieldErrors` 정확 8필드다.

## 외부 추적성과 schema gap

PM의 직접 외부 조회로 Notion page `3a40a87c-7ce5-816b-a8f7-ed2027e94b8c`가 같은 GET path, contract version `v1.1`, Spec Status `Ready`, Auth `Optional`, 화면 `장소 상세 / 일정 날씨`, DB `weather_grid_points/weather_forecasts`를 가리키는 것을 확인했다. 다만 response는 `grid, forecastedAt, validAt, temp, POP, precipitation, wind, dataFreshness`만 가진 오래된 부분 계약이라 로컬 `1.0.0`과 불일치한다. 외부 write 권한 없이 맞췄다고 가장하지 않고 `drift-blocked`로 기록했으며 PM/user 권한으로 Notion 행을 정렬해야 한다.

Figma file `4mKep38zm17iupVSQVsSJW`에서는 section `622:10382` 근처 intent node `622:19945`의 “여행 당일에 날씨 정보를 보고 일정을 바꿀 수 있게”를 확인했다. 실제 response field와 loading/empty/error node 연결은 발견되지 않았으므로 `not-ready/not-linked`를 유지하고 디자인 owner 후속을 명시했다. metadata/example/implementation readiness는 모두 `not-ready`다.

`weather_forecasts`에는 `expires_at`과 provider version 컬럼이 없다. Issue #67이 versioned snapshot/base에서 파생하거나 명시적 migration으로 해결해야 한다. 이 Issue는 schema를 바꾸지 않는다.

Reviewer 보완에서 canonical migration의 `forecast_type` 저장 enum이 `ultra_short | short`, 공개 enum이 `ultra_short | village`인 drift를 확인했다. #94에서 migration을 만들지 않고 구현 #67이 DB `ultra_short` → API `ultra_short`, DB `short` → API `village`로 정확히 projection하도록 contract/schemaGap/RDB·DB 문서와 mutation test에 고정했다.

fixture validator는 request/success/problem top-level exact field 집합을 검사하고 request headers를 closed `CommonHeaders`로 재귀 검증한다. Red에서 Basic Authorization, `X-Internal-Secret`, 세 fixture의 unknown top-level이 통과하는 5 failures와 projection 누락 1 error를 재현했고, Green에서 모두 차단했다.

## 현재 검증

```text
python3 -m unittest scripts.tests.test_weather_forecast_contract scripts.tests.test_contract_suite_integration
# 16 tests, OK

python3 -m unittest discover -s scripts/tests -p 'test_*contract*.py'
# 189 tests, OK (Refactor 전 전체 계약 회귀; 이후 focused 16건 재통과)

python3 scripts/validate_weather_forecast_contract.py
# SUCCESS

python3 scripts/validate_rest_contracts.py
# SUCCESS

git diff --check
# SUCCESS (PowerShell CRLF 안내만 출력)
```

상위 조정자의 지시에 따라 Spring clean/full quality gate, Docker smoke와 push는 아직 실행하지 않았다. 따라서 현재 상태는 `READY_FOR_REVIEW`가 아니라 검증 대기다. 승인 상태 파일과 PR은 만들지 않았다.

## Self-review fail-closed 보완

첫 커밋 `0515fbc` 뒤 candidate 계약의 response `providerApiVersion const`, endpoint `dbOwner`, `schemaGap` 문구를 변조해도 `--skip-catalog-fixtures` validator가 exit 0인 우회를 발견했다. 운영 validator를 바꾸기 전에 세 mutation을 추가했고 focused 실행에서 정확히 3 failures를 확인했다.

Green에서는 CommonHeaders/WeatherGrid/WeatherForecastResponse, endpoint 전체, schemaGap과 external owner follow-up을 canonical exact 비교하도록 보강했다. request 필수 누락·UTC·response category 누락·raw category 추가·fallback/stale 불일치·validAt drift·Problem 9번째 필드 등 fixture mutation 7종도 fail-closed임을 고정했다. 관련 17 tests, 전체 계약 회귀 192 tests와 전용/common validator가 다시 성공했다.
