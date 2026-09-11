# 2026-09-11 Issue #246 여행 상세 숙소·교통 복원

여행 상세 응답에 저장된 `transportEvents`와 `accommodations`를 추가했다. 빈 상태는 arrival/departure null과 빈 숙소 배열로 반환하며, 기존 write payload와 같은 필드·시각 표현을 사용한다. 소유자 root 조회 후 두 child query만 실행하고 #239의 repeatable-read 트랜잭션으로 root revision과 함께 읽는다. 기존 DB 컬럼을 사용하므로 새 migration은 없다.

## 통합 상태

`feat/246-trip-detail-projections`는 로컬 #239 `bba4d8e7ed168756083b5c6a4b6695026ec26f28`를 포함하며 #246 변경은 아직 미커밋 상태다. 통합 전 작업은 stash `8f867a94a242b8206018ef30a048c95417aedde6`에 보관했고, 복원 충돌을 모두 해결했다. stash는 보존 중이다. #239의 실제 develop 병합 확인은 남아 있다.

계약은 1.2.0, 구현 이슈는 44/45/239/246이다. 이전 POST·Day PUT의 24시간 원본 receipt를 유지하기 위해 POST는 최신/V1.1/V1, PUT은 최신/V1.1 union을 사용한다. GET·PATCH는 최신 필드를 필수로 요구한다. receipt 바이트·TTL·namespace는 변경하지 않는다.

## TDD 및 로컬 검증

- 응답 필드 누락 RED → GREEN: `/tmp/jeju246-projection-unit-red.log`, `/tmp/jeju246-projection-unit-green.log`.
- JDBC child 누락 및 손상된 숙소 이름 RED → cause 없는 503 변환과 두 query 매핑 GREEN: `/tmp/jeju246-jdbc-mapping-unit-red.log`, `/tmp/jeju246-jdbc-mapping-unit-green.log`. 이 검증은 mock JDBC이며 실제 DB 실행을 대신하지 않는다.
- 기존 receipt 계약 RED → 관련 37개 GREEN: `/tmp/jeju246-legacy-contract-red.log`, `/tmp/jeju246-legacy-contract-green.log`.
- 전체 Python 880개(3 skip) PASS: `/tmp/jeju246-merged-python-all.log`.
- unit 1,385개(9 skip), 전체 slice, architecture 48개 PASS: `/tmp/jeju246-merged-unit-slice.log`.
- 기본/ready OpenAPI 13개와 compile/export PASS: `/tmp/jeju246-merged-openapi.log`.
- 공식 38-operation client 생성 PASS: `/tmp/jeju246-merged-client.log`. 실제 artifact에서 POST 3분기와 PUT 2분기도 확인했다.
- 독립 Reviewer의 사전 소스 검토 finding 0. 실제 DB와 전체 gate 전이므로 공식 APPROVED·recorder 실행은 하지 않았다.

## 다음 검증

PG16·17 각각 13개 시나리오를 작성했고 컴파일했다. 실제 server major 확인, 저장 후 HTTP GET, 소유자 404, child query 수, child 실패 503, 숙소 정렬, root 조회 중 다른 세션의 child 변경에 대한 snapshot, 과거 POST/PUT 원본 replay와 최신 GET 복원을 검증한다. 표준 @Bean/@ServiceConnection 설정의 허용된 이미지 속성을 사용한다.

#239 전체 gate와 Docker 경합을 피하기 위해 이 DB 테스트는 아직 실행하지 않았다. #239 병합 후 최신 develop 통합 → 실제 PG16·17 실행 및 기존 mutation 회귀 → commit/push의 전체 quality gate와 Docker → 독립 최종 리뷰 → 공식 PR → CI → 병합 순서로 진행한다. live Supabase·운영 배포·실제 앱 재시작 검증은 수행하지 않았다.

## 실제 DB 집중 검증 결과

최초 73개 실행에서 PG16/17의 root PATCH 테스트 두 개만 실패했다. 테스트가 TripUpdateRecord에 빈 Day ID 목록을 직접 넘겨 저장 계층의 기간 제약을 위반한 것이 원인이었다. 실제 HTTP PATCH→GET으로 바꾸어 서비스의 Day 준비와 숙소 보존, title 및 새 ETag를 함께 확인했다. 나머지 기존 mutation/controller 47개는 최초 실행에서 통과했다. 수정 후 PG16/17 상세 projection 26개 모두 PASS(실패/skip 0), /tmp/jeju246-pg-projection-green.log. 최초 실패는 /tmp/jeju246-pg-projection.log에 보존했다. 전체 gate 및 원격 병합은 여전히 별도 절차다.


## FE 타입 생성으로 발견한 OpenAPI 표현 보완

실제 export를 FE openapi-typescript로 생성하니 transportEvents의 null과 숙소 required가 누락되는 문제를 확인했다. 독립 Reviewer도 nullable reference MAJOR 및 required MINOR를 확인했다. JSON Schema 공식 참조 설명(https://json-schema.org/understanding-json-schema/structuring)에 따라 `$ref` 대상 제약도 적용되므로 객체 참조 옆 type [object,null]만으로 null을 허용할 수 없다.

- 기본 runtime 및 ready projection 컨텍스트의 Spring 테스트에서 nullable anyOf 및 숙소 required를 먼저 요구해 7개 중 3개 실패를 재현했다: /tmp/jeju246-nullable-ref-red.log.
- TripTransportEvents와 TripDetail/legacy/Summary의 nullable 참조를 `$ref` 또는 null의 anyOf로 변환한다. canonical 확장된 inline schema의 정상 nullable 표현은 그대로 유지한다.
- 기존 AccommodationPayload에 필수 9필드, 문자열 길이, 날짜·시각 pattern 및 순서 최소값을 명시했다. 실제 JSON/DB/write 처리와 receipt는 변경하지 않는다.
- #239 f8c612c 전체 gate가 실행 중인 worktree는 변경하지 않았다. 이 보완은 #246 이후 로컬 스택으로 순차 통합한다.

- 보완 후 runtime/ready Spring OpenAPI 테스트 및 export PASS: /tmp/jeju246-nullable-ref-green.log. 공식 38-operation client/archive PASS: /tmp/jeju246-nullable-ref-client.log. 생성된 types.gen.ts에서 arrival/departure 및 scoreProvenance의 `| null`, 숙소 9필드 필수를 확인했다.

- 같은 입출도 payload의 7필드 required/enum 누락도 RED로 확인하고 annotation을 보완했다. 삭제 응답 event 참조의 null도 같은 방식으로 정규화했다. runtime/ready 테스트 및 export PASS (41초): /tmp/jeju246-transport-fields-red.log, /tmp/jeju246-transport-fields-green.log. 기존 저장 처리·DB·UI는 변경하지 않는다.
- nullable 보완 후 Python 전체 880개 실행, 3 skip, 실패 0: /tmp/jeju246-nullable-ref-python.log.

- 최종 공식 38-operation client/archive PASS: /tmp/jeju246-contract-final-client.log. 독립 Reviewer 보완 사전검토 finding 0; 전체 gate 전이므로 공식 승인/recorder는 수행하지 않았다.
