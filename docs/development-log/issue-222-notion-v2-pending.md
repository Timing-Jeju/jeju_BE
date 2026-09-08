# Weather Notion v2 갱신 대기

- 대상: https://app.notion.com/p/3a40a87c7ce5816ba8f7ed2027e94b8c
- 2026-09-08 실제 page readback 확인. 현재 본문 계약은 1.0.0이다.
- v2 본문 변경 요청은 Notion 무료 블록 한도(entitlement_required)로 거부됐고 본문 side effects는 없다.
- 속성 Contract Version은 1.0.0으로 복원하고 Spec Status는 Draft로 유지했다. 외부 v2 연결/readiness는 완료되지 않았다.
- 데이터 소스에는 기존 옵션을 보존하여 2.0.0 선택지를 추가했다. 유료 업그레이드는 수행하지 않았다.
- 아래는 재개 시 최신 페이지를 다시 조회하고 충돌 확인 후 적용할 정확한 변경안이다.

```json
[
  {
    "old_str": "- 인증: Optional Supabase JWT. 헤더 생략은 anonymous, 전달한 token이 invalid/expired이면 401",
    "new_str": "- 인증: 공개 regionCode/placeId 조회는 Optional Supabase JWT. tripItemId 조회는 JWT 필수이며 소유 항목만 허용한다. 전달한 token이 invalid/expired이면 401"
  },
  {
    "old_str": "- 계약 버전: 1.0.0",
    "new_str": "- 계약 버전: 2.0.0 (Draft, 2026-09-08 위치 비수집 전환)"
  },
  {
    "old_str": "- 로컬 계약: Issue #94 `docs/contracts/domains/weather-forecast/contract.json`\n- 운영 구현: Issue #67",
    "new_str": "- 로컬 계약: Issue #220 `docs/contracts/domains/weather-forecast/contract.json`\n- 구현 전환: Issue #222. 전체 품질 게이트·독립 리뷰·병합 전이며 운영 배포를 의미하지 않는다.\n- 이전 v1 계약과 구현은 Issue #94/#67 및 `historical-v1.contract.json`에 보존한다."
  },
  {
    "old_str": "`lat`, `lng`, `dateTime`은 동시에 required/non-null이며 알 수 없는 query는 거부한다.\n- `lat`: finite number, `-90 < lat < 90`\n- `lng`: finite number, `-180 <= lng <= 180`\n- `dateTime`: RFC 3339, Asia/Seoul `+09:00`, 정시(seconds 00)",
    "new_str": "`regionCode`, `placeId`, `tripItemId` 중 정확히 하나와 필수 `dateTime`을 받는다. 중복·알 수 없는 query·빈 값·잘못된 형식은 400이다.\n- `regionCode`: 사용자가 명시 선택한 공개 지역, `^[a-z0-9][a-z0-9_-]{0,49}$`. 현재 지원 목록은 `jeju-si`, `seogwipo-si`, `seongsan`이다.\n- `placeId`: 사용자가 선택한 공개 장소 canonical UUID. 미존재·삭제 장소는 404이다.\n- `tripItemId`: 인증 사용자가 소유한 계획 항목 canonical UUID. 인증 누락 401, 미존재·타 소유자·공개 장소 참조 없음은 동일한 404이다.\n- `dateTime`: RFC 3339, Asia/Seoul `+09:00`, 정시(분·초 00, 소수 초 없음).\n- GPS, GPS에서 파생한 지역·nearest place·grid·hash를 전송하지 않는다. 좌표는 공개 장소 FK에서 Spring 내부 조회하며 facts.location fallback을 사용하지 않는다.\n- 공개 지역 grid는 KMA 공식 행정구역 격자표 2026-07-01의 검증된 projection으로 고정한다. DB에 해당 지원 grid가 없으면 422, 예보가 없으면 503이다."
  },
  {
    "old_str": "GET /api/v1/weather/forecast?lat=33.458111&lng=126.941516&dateTime=2026-08-24T14:00:00%2B09:00",
    "new_str": "GET /api/v1/weather/forecast?regionCode=seongsan&dateTime=2026-08-24T14:00:00%2B09:00"
  },
  {
    "old_str": "\"contractVersion\": \"1.0.0\"",
    "new_str": "\"contractVersion\": \"2.0.0\""
  },
  {
    "old_str": "\"regionName\": \"서귀포시 성산읍\"",
    "new_str": "\"regionName\": \"제주특별자치도 서귀포시 성산읍\""
  },
  {
    "old_str": "<td>`INVALID_WEATHER_FORECAST_QUERY`</td>\n<td>required/type/WGS84/KST/정시/unknown query 위반</td>",
    "new_str": "<td>`INVALID_WEATHER_SELECTOR`</td>\n<td>선택자 개수·형식, KST 정시, 중복·unknown query 위반</td>"
  },
  {
    "old_str": "<td>optional Authorization을 보냈지만 token invalid/expired</td>\n</tr>",
    "new_str": "<td>optional Authorization을 보냈지만 token invalid/expired</td>\n</tr>\n<tr>\n<td>401</td>\n<td>`AUTHENTICATION_REQUIRED`</td>\n<td>tripItemId 조회에 인증 누락</td>\n</tr>\n<tr>\n<td>404</td>\n<td>`WEATHER_REFERENCE_NOT_FOUND`</td>\n<td>공개 장소 또는 소유 계획 항목 참조를 사용할 수 없음</td>\n</tr>"
  },
  {
    "old_str": "별도 200 empty는 없으며 422/503 error 상태로 연결",
    "new_str": "별도 200 empty는 없으며 404/422/503 error 상태로 연결"
  },
  {
    "old_str": "Spring이 optional JWT와 입력을 검증하고 정규화 DB projection을 소유한다. 공개 요청 중 KMA/FastAPI를 호출하지 않는다. 이 Notion 행과 Figma trace는 계약 `1.0.0`의 metadata/example 기준이다. 실제 Controller/OpenAPI/contract test와 `Implementation Ready` 승격은 Issue #67에서 완료한다.",
    "new_str": "Spring이 공개 selector와 조건부 JWT/owner를 검증하고 정규화 DB projection을 소유한다. 공개 요청 중 KMA/FastAPI를 호출하지 않는다. 이 Notion 행은 계약 `2.0.0` 초안이다. Figma trace와 실제 Controller/OpenAPI/contract test의 readback 및 전체 품질 게이트·독립 리뷰가 모두 완료되기 전에는 `Implementation Ready`로 승격하지 않는다. 후속 검증은 Issue #222에 기록한다."
  }
]
```

