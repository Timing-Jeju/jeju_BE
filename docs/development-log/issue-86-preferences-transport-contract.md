# Issue #86 여행 선호·교통 이벤트 API 계약 개발 기록

## 범위와 기준

- 최초 기준: `origin/develop` `59eb374103bd40b718cab3d7d5b7c57034fe1c4a`
- Reviewer 보완 기준: `origin/develop` `af912f5a93c40dee900914e4a6b5396c0f9631d0`
- 브랜치: `docs/86-c05-api-contract`
- 포함: preferences, place-preferences, transport-event PUT/DELETE 네 계약
- 제외 확인: Spring Controller/Service/Repository, schema/migration/Flyway, FastAPI 소스는 변경하지 않았다.

## Red → Green → Refactor

Red는 운영 계약 파일보다 `scripts/tests/test_preferences_transport_contract.py`와 통합 suite 기대값을 먼저 추가했다.

```text
python3 -m unittest scripts.tests.test_preferences_transport_contract scripts.tests.test_contract_suite_integration
```

결과는 `contract.json` 부재로 10 errors, catalog의 네 endpoint와 양쪽 gate 명령 부재로 3 failures였다. 실패 원인은 새 계약이 아직 없다는 의도와 일치했다.

Green에서는 canonical JSON/Markdown, 합성 fixture 3종, 전용 validator, catalog endpoint 4건과 shell/PowerShell gate를 추가했다.

```text
python3 scripts/validate_preferences_transport_contract.py
python3 scripts/validate_rest_contracts.py
python3 -m unittest scripts.tests.test_preferences_transport_contract scripts.tests.test_contract_suite_integration
```

전용·공통 validator와 13개 관련 테스트가 성공했다. 이어 #83~#85 validator와 전체 계약 test discover도 성공해 기존 계약을 보존했다.

Refactor에서는 legacy plural `/transport-events/{eventType}`를 Issue가 고정한 singular `/transport-event`로 정리하고, DELETE도 `204` 대신 일정 재생성 신호가 있는 `200`으로 통일했다. local 문서의 중복 JSON 대신 canonical 파일 링크와 의미 규칙을 남겼다. Figma는 실제 관찰한 node/action만 기록하고 contract version 및 loading/empty/error 증거가 없어 `not-linked/not-ready`를 유지했다.

## 결정된 경계

- 선호 두 PUT은 전체 교체이며 partial upsert가 아니다.
- transport mode/priority는 중복 금지, priority 1..N 연속, primary 정확히 한 건·priority 1이다.
- 같은 장소는 희망/회피 양쪽에 함께 올 수 없고 `targetDayNo`는 null 또는 여행 Day 범위다. priority tie는 placeId 오름차순이다.
- 교통 이벤트는 RFC 3339 `+09:00`, 제주 `Asia/Seoul`, arrival=startDate/departure=endDate다. terminal place/custom name은 XOR다.
- active 일정에 영향을 주는 실제 변경은 version을 superseded로 바꾸고 pointer를 비우며 trip을 draft로 돌려 `regenerationRequired=true`를 반환한다.
- schema의 terminal XOR 및 희망/회피 교차 중복 불일치는 #46/#47 migration/API에서 다루며 이 Issue는 migration을 변경하지 않는다.

## 외부 추적성

Notion 데이터소스에 기존 option을 보존하면서 `Contract Version=1.0.0`, `Spec Status=Implementation Ready` option을 추가했다. 기존 네 page ID의 title/method/path/version/status/body를 local 계약과 일치시켰고 SQL 재조회로 exact 4행을 확인했다.

Figma file `4mKep38zm17iupVSQVsSJW`, page `251:4347`에서 `329:5165`, `182:3248`, `653:11512`, `329:4975`의 기본 조건·항공/선박·관심 장소 action을 확인했다. loading/empty/error와 API contract version의 직접 연결은 관찰되지 않아 readiness는 승격하지 않았다.

## 보안과 데이터

fixture의 UUID, transport number, traceId는 모두 합성값이다. 실제 token, 이메일, 사용자 metadata, 외부 API key, provider payload는 문서·fixture·로그에 넣지 않았다.

## Reviewer 보완 Red → Green

Reviewer가 성공 응답 합성과 404 오류 추적성 두 건을 지적한 뒤 운영 계약보다 mutation 테스트를 먼저 추가했다. 최초 실행은 14개 관련 테스트에서 6 failures와 11 errors였으며 문자열 `allOf`, closed-world 부재, 공통·고유 required 삭제 미탐지, 추가·누락 response field 미탐지, `PLACE_NOT_FOUND`와 `TRANSPORT_EVENT_NOT_FOUND` fixture 부재를 각각 재현했다.

Green에서는 세 성공 응답을 실제 `$ref` `allOf`와 `unevaluatedProperties=false`로 닫고, validator가 composition·공통 required·child required 및 success fixture의 exact field 집합을 검증하도록 했다. endpoint error matrix는 canonical code 배열로 바꾸고 matrix → condition → 한국어 problem fixture를 양방향 exact 검증한다. 관련 17개 테스트가 성공했다.

Notion 네 행도 endpoint별 error code/status/type/detail/instance를 local canonical 계약과 맞춘 뒤 재조회했다. preferences, place-preferences, transport PUT에는 `PLACE_NOT_FOUND`, transport DELETE에는 `TRANSPORT_EVENT_NOT_FOUND`가 exact occurrence URI template과 함께 존재함을 확인했다.

## 성공 fixture 재귀 검증 보완

두 번째 Reviewer finding에 따라 성공 fixture의 최상위 key 집합만 비교하던 검증을 TDD로 보완했다. 먼저 scalar type, UUID/date-time/`+09:00` format, enum, nested required·unknown·nullable과 PUT/DELETE event·deleted 의미를 훼손하는 mutation을 추가했다. 최초 16개 관련 테스트에서 13 failures로 모두 미탐지됨을 확인했다.

Green에서는 local `$ref`와 `allOf`를 합성한 뒤 common·child·nested schema 전체를 재귀 검증하도록 validator를 확장했다. object closure, required, nullable, scalar type, enum, UUID/date-time/offset, 문자열·배열·정수 제약을 실제 fixture 값에 적용하고, PUT은 `event != null`/`deleted=false`, DELETE는 `event=null`/`deleted=true`를 endpoint 의미로 별도 고정했다. 전용 validator와 관련 통합 suite 19개가 성공했다. 외부 API 계약 값은 바뀌지 않아 canonical 문서와 Notion 네 행은 수정하지 않았다.
