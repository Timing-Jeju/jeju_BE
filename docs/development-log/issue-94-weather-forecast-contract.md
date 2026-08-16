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

현재 세션에서 Notion/Figma live evidence를 읽을 수 없으므로 contractVersion은 `not-linked`, metadata/example/implementation은 `not-ready`로 유지했다. PM/디자인 owner가 Notion 행과 Figma 소비 node·loading/empty/error를 재조회해 연결해야 한다.

`weather_forecasts`에는 `expires_at`과 provider version 컬럼이 없다. Issue #67이 versioned snapshot/base에서 파생하거나 명시적 migration으로 해결해야 한다. 이 Issue는 schema를 바꾸지 않는다.

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
