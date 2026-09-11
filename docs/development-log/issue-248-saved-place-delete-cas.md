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
