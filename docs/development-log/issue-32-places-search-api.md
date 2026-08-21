# Issue #32 관광지 검색·필터·커서 목록 API 개발 기록

## 범위와 기준

- 기준: `origin/develop` `ca7cb8109d003c35160795f3caae95e24321e2fd`
- 브랜치: `feat/32-places-search-api`
- 기준 문서: Issue #32 본문과 PM 결정 comment `5351367418`, Issue #83 places JSON/Markdown 계약
- 포함: `GET /api/v1/places`, 선택 인증과 개인화, 검색·필터·PostGIS 반경, HMAC keyset cursor, 대표 이미지·운영 요약·freshness, #65 추천 체류시간 provenance
- 제외: 장소 상세 API, request-time 외부 provider 호출, schema/migration, raw payload와 내부 lineage 공개

## Red → Green → Refactor

운영 코드를 만들기 전에 request와 service 테스트를 추가했다. 최초 focused 실행은
`PlacesListQuery`, `PlaceQueryValidationException`, 목록 service 타입이 존재하지 않아
`compileTestJava`의 missing symbol 10건으로 실패했다.

Green에서 닫힌 DTO와 Problem Details, exact GET optional auth, 단일 JDBC projection,
literal wildcard escaping, PostGIS `ST_DWithin`, size+1 keyset 조회, filter fingerprint에 묶인
HMAC cursor, current-user 저장 장소 격리와 #65 batch resolver를 구현했다. 이후 controller,
security slice, OpenAPI, cursor tamper/context, contract validator를 focused로 통과시켰다.

실제 PostgreSQL 첫 실행은 테스트 별칭이 lineage 없는 external alias로 판정되어 places 5개가
의도대로 Red였다. fixture를 내부 허용 `alias_type=user_query`로 맞춘 뒤 6개 중 5개가
Green이었고, 남은 1건은 geography `ST_Project`와 `ST_DWithin`의 sub-millimeter spheroid
반올림 차이였다. production의 inclusive `ST_DWithin`은 유지하고 radius 1,000m에 대해
999.999m inside와 1,000.1m outside 쌍으로 경계를 명시했다.

self-review에서는 PM 결정과 production이 제주 bounding box를 사용하지만 canonical #83
contract/validator가 전지구 범위를 유지한 불일치를 발견했다. validator 기대값을 먼저
`lat 33..34`, `lng 126..127`로 바꿔 `lat/lng/radiusMeters 범위와 조합 계약이 다릅니다` Red를
확인한 뒤 JSON/Markdown 계약을 같은 범위로 동기화했다. generated OpenAPI에도 bbox가
누락된 Red를 추가로 확인하고 optional parameter를 유지한 `DecimalMin/DecimalMax` 제약으로
계약과 맞췄다.

## 검색·보안·데이터 경계

- `query`는 trim 후 1~100자이며 `%`, `_`, `\`는 bind parameter 안에서 literal escape한다.
- category/region은 exact code filter이고, 이름과 active alias만 검색한다.
- normalized active 장소만 반환하며 `source_deleted_at` 행은 제외하고 stale은 숨기지 않고 투영한다.
- 대표 이미지는 active 이미지의 `display_order, id`, 운영 요약은 active detail에서 결정한다.
- 익명 응답은 `saved=false`, `memo=null`, `tags=[]`; `savedOnly=true` 익명과 invalid bearer는 401이다.
- optional auth는 exact `GET /api/v1/places`에만 적용하고 나머지 `/api/v1/**` 보호는 유지한다.
- cursor는 query/category/region/좌표/radius/size/savedOnly/sort profile fingerprint와 HMAC에 묶인다.
- 동일 거리·동일 이름·UUID tie 및 목록 경계 삽입에서도 keyset이 중복 없이 진행한다.
- 추천 체류시간은 request-time provider 호출 없이 한 snapshot의 place override→category default→unavailable 결과와 source/version/effectiveAt/updatedAt을 반환한다.

## 현재 검증 증거

- focused unit/controller/security/OpenAPI: 23개 및 후속 cursor/security/OpenAPI/config 보강 테스트 성공
- places contract validator와 `scripts.tests.test_places_contract`: 28개 성공
- actual PostgreSQL 동일 invocation: `JdbcPlaceSearchRepositoryIntegrationTest` 5개 + `JdbcStayPolicyRepositoryIntegrationTest` batch 1개, 총 6개 성공
- PostGIS 실제 DB에서 반경 안/밖, 동일 tie keyset, GiST plan 증거 확인
- Hikari shutdown 완료, Testcontainers/Postgres/Ryuk container/network/volume 잔여 0
- 기존 live demo 3개와 FaithLog backend/Postgres/Redis 보존 확인
- normal hook의 secret scan, production/test pairing, Spotless, unitTest 성공

첫 Spring `clean check`는 929 tests 중 동작 실패 없이 ArchUnit package naming 1건만 Red였고
7개가 환경 조건으로 skip됐다. 예외를 `..places.exception..`, repository port의 position/row
값 객체를 `..places.model..`로 이동해 역할 package를 정렬했으며 focused Architecture와 관련
places unit/security/controller 21개를 Green으로 확인했다.

## 남은 완료 조건

- shared slot에서 Spring `clean check`, repository quality gate, Docker smoke를 직렬 실행한다.
- 최종 HEAD 검증 결과를 Issue #32 TDD evidence comment와 Obsidian
  `04_Projects/timing-jeju` 한국어 개발 일지에 반영한다.
- Developer는 PR과 승인 상태 파일을 만들지 않고 독립 Reviewer에게 넘긴다.

## Issue #32 evidence comment 초안

```text
Issue #32 TDD 증거

- Red 1: production 작성 전 PlacesListQuery/Service 테스트가 missing symbol 10건으로 실패
- Green 1: focused unit/controller/security/OpenAPI 및 places contract 28 tests 성공
- Red 2(actual PostgreSQL): lineage 없는 alias fixture로 places 5 tests 실패
- Green 2(actual PostgreSQL): fixture lineage 보정 후 places 5 + StayPolicy batch 1 = 6/6 성공
- Refactor: 동일 거리·동일 이름 UUID tie/keyset, spheroid epsilon inside/outside, 제주 bbox contract 동기화
- 정리: Hikari shutdown 완료, Testcontainers container/network/volume 0, live demo/FaithLog 보존
- 최종 quality/Docker: shared slot 실행 결과를 여기에 추가
```
