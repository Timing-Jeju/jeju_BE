# 2026-09-11 Issue #248 관심 장소 삭제 CAS

## 구현
- #238 뒤에 병합할 격리 worktree fix/248-saved-place-delete-cas를 develop 93bcb75에서 준비했다.
- DELETE는 단일 strong If-Match를 필수로 받고 누락·weak·wildcard·다중 값·중복 헤더를 DB 접근 전에 400으로 거부한다.
- 소유자 saved_places 행을 FOR UPDATE로 잠그고 기존 ETag 함수와 비교한 뒤 version 조건으로 삭제한다. stale 409, 없음·재삭제·타 소유자 404, 성공 204 및 빈 body다.
- DB migration, 원본 idempotency receipt, 보존 기간은 변경하지 않는다.
- canonical 1.2.0, DELETE header schema, 409 문제 응답, 실제 OpenAPI, fixture와 validator를 함께 변경했다.

## 검증 증거
- 헤더 누락 등 기존 코드의 404/성공 경로를 RED로 확인하고 controller/service 변경 후 slice 통과: /tmp/jeju248-delete-header-red.log, /tmp/jeju248-delete-header-green.log.
- 계약 테스트 2개 RED 후 관련 28개 GREEN: /tmp/jeju248-delete-contract-red.log, /tmp/jeju248-delete-contract-green.log.
- Python 전체 876개 PASS, 3 skip: /tmp/jeju248-python-all.log.
- 실제 Spring OpenAPI에서 필수 If-Match, 409, body 없는 204를 확인: /tmp/jeju248-delete-openapi.log PASS.
- 실제 PG16/17용 테스트를 추가했다. stale 보존, 동시 DELETE 단일 승자, PATCH 잠금 선점 후 DELETE 재검사, DELETE 선점 후 PATCH 부활 방지, HTTP stale→최신 삭제→재삭제를 검증한다. pg_stat_activity의 실제 lock 대기를 확인해 순서를 고정한다.
- PG 테스트 결과는 아래 실제 DB 집중 검증 및 선행 변경 통합 검증에 기록한다.
- 전체 unit 1542개(9 skip), slice 65개, architecture 48개 PASS: /tmp/jeju248-unit-slice-all.log. XML 기준 failures/errors 0이다.
- 독립 Reviewer의 소스 사전 검토 finding 0. 실제 DB/전체 gate 전이므로 공식 승인이나 recorder 실행은 하지 않았다.

## 남은 절차
#239 → #246 → #238 병합 후 최신 develop 통합, PG16/17 실행, 38-operation 공식 client 생성, 전체 품질 gate/Docker, 독립 Reviewer 승인, 공식 create-pr, 원격 CI와 병합. 아직 이 브랜치의 commit/push/PR/병합 완료가 아니다.

## 실제 DB 집중 검증

/tmp/jeju248-pg-delete.log에서 PG16/17 저장소 경합 및 PG16 HTTP 총 43개 PASS, 실패/skip 0. stale 행 보존, 양방향 PATCH/DELETE 실제 lock 대기, 동시 삭제 단일 성공, HTTP 409→204→404를 확인했다. 선행 #238 통합 후 목록 ETag까지 연결된 전체 회귀와 full gate는 별도로 수행한다.

## 선행 변경의 로컬 통합 검증

#239 f8c612c → #246 0aeffc0 → #238 20d1228을 순서대로 로컬 통합했다. 원격 병합 완료를 의미하지 않는다. #238의 응답 ETag 및 기존 POST receipt union을 유지하고 #248 DELETE 계약을 결합했다.

- PG16/17 저장소 각 20개, HTTP 각 7개 총 54개 PASS, 실패·오류·skip 0: /tmp/jeju248-integrated-pg-green.log. GET 목록의 ETag → PATCH → stale DELETE 409 → GET 최신 ETag → DELETE 204 → 재삭제 404를 실제 DB로 확인했다.
- 첫 통합 실행은 테스트의 static get import 누락으로 컴파일 실패했고 import 보완 후 위 54개를 통과했다. DB assertion 실패가 아니었다.
- runtime manifest의 DELETE 409 누락을 새 계약 assertion으로 RED 확인하고 해당 operation의 status/problem mapping을 수정했다. 관련 31개 PASS: /tmp/jeju248-runtime-contract-red.log, /tmp/jeju248-runtime-contract-green.log.
- 통합 Python 전체 884개 실행, 3 skip, 실패 0: /tmp/jeju248-integrated-python-final.log.
- 원격 최신 develop 통합, 동일 SHA 전체 품질 게이트 및 독립 Reviewer 최종 승인은 아직 별도 수행해야 한다.

- 통합 Trip/SavedPlaces OpenAPI slice 및 openApiDocs PASS: /tmp/jeju248-integrated-openapi.log. 공식 frontend client 생성·검증 38 operations 및 배포 archive 생성 PASS: /tmp/jeju248-integrated-client.log.


## 2026-09-12 최종 검증 중 파일 복사 교착 수정

선행 #239(PR252), #246(PR253), #238(PR255) 병합을 최신 develop으로 통합했다. HEAD24bc8b1의 전체 게이트는 1시간 29분 47초 실행 후 Testcontainers 2.0.5의 파일 복사 정지로 실패 처리했다. main과 writer가 같은 PipedInputStream을 기다리고 컨테이너는 created 상태에 머물렀다. 자체 테스트 worker를 종료했고 실행 컨테이너는 정리됐다. 일부 테스트 결과를 전체 통과로 간주하지 않는다.

검증 차단 원인이 #238 첫 시도에서도 발생했던 같은 파이프 종료 교착이므로, 테스트 전용 PostgreSQL 컨테이너의 복사를 완료된 임시 tar의 InputStream으로 바꿨다. tar 작성과 Docker 읽기를 서로 다른 스레드에서 동시에 하지 않는다. SQL 내용·순서·파일 권한, 이미지, DB 격리와 timeout, 운영 API는 유지한다. 전송 성공·실패 모두 자원을 닫고 임시 파일을 삭제하며 Docker의 원래 RuntimeException을 보존한다.

- 실패 증거: /tmp/jeju248-thread-copy-wait.txt, /tmp/jeju248-push-final.log.
- 새 테스트 먼저 추가 후 구현 없음으로 compile RED: /tmp/jeju248-archive-red.log. 실제 파이프 교착 증거와 구분한다.
- 큰 한글 SQL의 tar 경로·권한·내용·EOF 및 Docker가 본문을 읽기 전 실패해도 2초 내 동일 오류 반환 회귀 검사 추가. 신규 2개+기존 factory 2개 통과: /tmp/jeju248-archive-green-final.log.
- 독립 소스 사전 검토 finding 0. 실제 PG와 수정 후 동일 SHA 전체 게이트는 별도 완료해야 하며 공식 승인은 아직 기록하지 않았다.

- 수정 후 실제 PG 집중 검증: DayActivityWindowMigrationIntegrationTest(PG16·17), SavedPlacesHttpPostgreSqlIntegrationTest, JdbcSavedPlaceRepositoryPg17IntegrationTest 합계 29개 통과, 실패·오류·skip 0, 3분 30초. /tmp/jeju248-archive-pg-green.log. 컨테이너 정리 확인.

## 2026-09-12 CI 잠금 관찰 회귀 수정

HEAD e377740의 로컬 전체 게이트는 통합 1049개(4 skip), test 1569개(9 skip), Python 884개(3 skip), OpenAPI 38 operations, 커버리지·빌드·Docker 및 정리까지 통과했고 PR256을 생성했다. 이후 GitHub run34644481753에서 PG17 DELETE 선점 테스트 1개가 잠금 대기를 관찰하지 못해 실패했다. CI 실패 상태에서는 병합하지 않았다.

경쟁 작업 시작 전에 pg_stat_activity 통계 스냅샷을 생성하도록 두 순서 제어 테스트를 보강했다. 동일한 DELETE 선점 실패를 1개 테스트로 재현했다(1분 7초, /tmp/jeju248-lock-snapshot-red.log). 관찰 루프에서 매번 pg_stat_clear_snapshot()을 호출해 트랜잭션에 캐시된 과거 활동 정보 대신 최신 잠금 대기를 확인하도록 수정했다. PostgreSQL 17 공식 monitoring-stats 문서의 통계 스냅샷 동작과 일치한다. 운영 삭제·수정 SQL, 트랜잭션 순서, 실제 잠금 확인 assertion 및 5초 기한은 유지한다.

PG16·17 저장소 전체 집중 검증 40개, 실패·오류·skip 0, 1분 49초 통과(/tmp/jeju248-lock-snapshot-green.log). 독립 소스 사전 검토 finding 0. 새 SHA 전체 게이트와 원격 CI는 이후 다시 수행한다.
