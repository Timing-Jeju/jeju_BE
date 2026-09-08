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
