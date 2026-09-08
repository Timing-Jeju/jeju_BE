# Issue 221 장소 목록 위치 입력 제거

## 범위

장소 목록의 query DTO/Controller/Service/JDBC/cursor와 사용자 distanceMeters를 제거한다.
공개 장소 좌표와 장소→정류장 distance, optional JWT/saved, canonical category는 유지한다.
DB migration이나 실제 DB 적용·배포는 포함하지 않는다.

## TDD

- RED: 입력 record의 lat/lng/radiusMeters 존재와 금지/unknown/중복 query HTTP 경계 실패.
- GREEN: 허용 query 6개만 수용하고 원문 값 없는 INVALID_QUERY_PARAMETER로 거부.
- GREEN: 공개 read model의 이름/ID tuple SQL 정렬·keyset만 유지하고 사용자 anchor
  ST_DWithin/ST_Distance/parameter와 distance 결과를 제거했다.
- GREEN: plc2 prefix를 decode 전에 검사하며 HMAC key scope를 별도로 파생한다.
  정상 서명된 v1 token에 prefix를 붙여도 실패하며 다른 scope와도 호환되지 않는다.
- GREEN: 원문 marker가 Problem 응답과 캡처 로그에 없음을 확인했다.
- 전체 unit/slice 통과. 실제 PostgreSQL repository 6개 시나리오 통과:
  검색·alias·wildcard literal, category/region/lifecycle, HTTP 공개좌표/체류시간,
  owner saved 격리, 거리와 무관한 목록 및 공개 GiST, 동일 이름 keyset 경계 삽입.
- Refactor: near sort/위치 fingerprint/거리 SQL 분기를 없애고 동일 tuple 경계를 유지.

## 선행 계약과 미완료

시작 base는 develop b0c490f다. #220의 전체 gate가 진행되는 동안 독립된 runtime 구현을
준비했으며 최종 PR과 전체 gate는 #220 병합 후 최신 develop에서 수행한다.
당시 canonical v2 문서와 readiness를 이 커밋의 테스트 통과만으로 활성화하지 않는다.
독립 bounded advisory에서 코드 finding은 없었으며 최종 리뷰 승인은 별도다.

## 공통 catalog 잔여 안내 정리

- RED: catalog가 여전히 lat/lng/radius와 nearby distance 정렬을 안내함을 검출.
- GREEN: 여섯 허용 조건과 이름/ID 정렬, 명시 지역 선택으로 runtime 설명을 정렬했다.
- 사용되지 않는 OpenAPI radius 예시·설명을 제거했다. Weather 좌표는 #222까지 현행 runtime 문서로 남는다.
- 공통 catalog contractVersion은 envelope 버전이며 domain v2/readiness와 별도로 유지한다.

## 최신 계약 통합 후 문서 회귀 보완

- develop f7fc751의 #220 v2 계약을 병합했다. 전체 unit/slice/PG16·17 integration은 성공했으나 OpenAPI readiness에서 v2 cursor pattern과 공통 v1 예제의 불일치가 발견돼 최초 전체 gate는 실패했다(`/tmp/jeju-221-final-quality.log`).
- 실제 Swagger cursor example이 plc2 접두사를 쓰는지 RED(`/tmp/jeju-221-cursor-example-red.log`) 후 Places 문서 인터페이스에 해당 도메인 예제만 지정했다. GREEN `/tmp/jeju-221-cursor-example-green.log`.
- `docs/FRONTEND_API_SPEC.md`의 위치 query/거리 정렬·응답·오류 예시를 v2의 여섯 입력, name/id keyset, 위치 입력 비수집으로 정정했다. 공개 장소 좌표와 장소–정류장 거리는 그대로 유지했다.
- OpenAPI 생성 성공 및 루트 실행 validator 37 operations PASS(`/tmp/jeju-221-openapi-validator-final.log`); 문서 회귀 Python 43건 PASS(`/tmp/jeju-221-doc-regressions.log`). validator 첫 수동 호출은 작업 디렉터리가 Spring 하위여서 authority 경로를 찾지 못했으며 루트에서 정상 확인했다.
- 새 SHA 전체 gate와 독립 최종 승인은 별도로 다시 확인한다. 문서 연결이 not-ready인 상태를 임의 승격하지 않았다.
