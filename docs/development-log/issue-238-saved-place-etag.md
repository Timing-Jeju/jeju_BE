# #238 찜 목록 ETag 복원 준비 — 2026-09-11

FE의고정OpenAPI는조회ETag와DELETE CAS를전제로하지만현재develop93bcb75에는없다. #239 전체gate대기중별도 fix/238-saved-place-response-etag 작업공간을만들어Docker없는코드/계약/단위검증만진행했다. #239→#246→#238→#248 순서로DB/최종gate/병합을진행한다. 아직commit/push/PR은없다.

- `/tmp/jeju238-etag-unit-red.log`:공개body.etag누락 assertion RED. 기존 SavedPlaceEtag 재사용으로필드추가후 `/tmp/jeju238-etag-unit-green.log` PASS.
- `/tmp/jeju238-etag-contract-red.log`:requiredetag/과거POSTunion/header-body일치누락 RED. `/tmp/jeju238-etag-contract-green.log`:관련28개PASS.
- SavedPlaceResponse의required/nullable/기존길이·수치제약을문서화했고POST만문서전용SavedPlaceCreateResponse/LegacyV1 union이다. 새DTO직렬화외의repository/service/receipt/write 의미는변경하지않았다.
- 실제HTTP/PostgreSQL 테스트에목록새세션→PATCH→stale409→새목록재시도및과거receipt body보존/TTL불변/최신목록etag시나리오를추가했다. 아직DB실행전이다. PG17은#246테스트이미지설정통합뒤검증한다. 기존repository동시PATCH/owner은닉/cursor/TTL/ACL회귀도검증해야한다.

전체unit/slice/architecture와Python을실행중이다. OpenAPI/client,실제PG16/17,전체qualitygate/Docker,독립Reviewer승인과PR/CI/병합이남았다. liveSupabase/운영배포/native검증은없다.

전체Python876개(기존skip3), unit1,372개(기존skip9), slice57개, architecture48개가실패/오류0으로통과했다. `/tmp/jeju238-unit-slice-all.log`, `/tmp/jeju238-python-all.log`에근거가있다. 독립Reviewer사전source검토finding0이며공식승인/recorder는아니다.

클라이언트첫검사 `/tmp/jeju238-client.log`는GET/PATCH예제의etag누락으로RED였다. 기존rowversion에맞는예제를추가하고 `/tmp/jeju238-openapi-example-green.log`의OpenAPI검사/export는PASS했다. 재생성 `/tmp/jeju238-client-fixed.log`는37operations를만들었지만기반93b의스크립트가33을요구하여최종실패했다. 실제현재37개를명시한공식artifact검증기단독실행 `/tmp/jeju238-client-current37-check.log`는PASS다. 정규생성스크립트전체PASS나releasearchive생성으로간주하지않으며 #239의38operations수정통합후정규명령을다시실행한다.

#246의동일PostGIS16/17테스트설정을재사용하고HTTP PG17상속클래스를추가했다. 두버전각6개시나리오로목록페이지의실제rowetag와복원/PATCH/stale/replay를검증할예정이다. DB는아직실행하지않았고compile만확인한다. #239/#246병합을반영한뒤DB와전체gate를진행한다.

## 실제 DB 집중 검증

/tmp/jeju238-pg-etag.log: PG16/17 HTTP 클래스와 기존 저장소 회귀 총 27개 PASS, 실패/skip 0. 목록 ETag→PATCH 충돌→재조회, 과거 원본 POST receipt와 TTL, cursor 항목별 ETag를 확인했다. 최종 선행 브랜치 통합과 38-operation 생성, 전체 gate/원격 병합은 아직 남아 있다.

## 선행 로컬 통합

작업을 stash 09bfd732c3384f0059e8c13b3dc26b2c5aeee809에 보존한 뒤 #246 0aeffc0로 fast-forward하고 복원했다. FRONTEND_API_SPEC의 append 충돌은 #246과 #238 내용을 모두 보존했다. stash는 유지한다. 통합 후 Python 882개(3 skip), Trip/SavedPlaces 기본 OpenAPI 검사 및 export PASS. /tmp/jeju238-integrated-client.log에서 공식 38-operation client와 archive 생성도 PASS했다. 앞선 33/37 불일치는 이 통합으로 해소됐으며 최종 전체 gate/원격 PR/병합은 아직 남아 있다.


## FE 통합의 ETag 문서 검증
실제 runtime DTO의 etag pattern이 canonical opaque strong 형식과 일치하도록 annotation을 보완했다. FE의 과거 고정 sp-hash 표현은 opaque 계약으로 검증한다. DB/ETag 생성 알고리즘은 변경하지 않는다. Spring OpenAPI 회귀 RED→GREEN 및 export PASS (40초): /tmp/jeju238-etag-pattern-red.log, /tmp/jeju238-etag-pattern-green.log.
