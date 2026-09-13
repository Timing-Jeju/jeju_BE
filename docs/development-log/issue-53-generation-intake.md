# #53 생성 접수 구현 — 요청 경계

## 2026-09-14 장소 선호 멱등 연결 작업 중

- 기존 place-preferences Controller가 Idempotency-Key를 무시하는 RED를 재현했다(통합 테스트 1개, 재생 헤더 누락, 10초).
- 선택적 UUID 키가 있으면 기존 IdempotencyUseCase를 통해 구조화 command의 hash와 변경 응답 bytes/ETag를 처리한다. receipt 반환 전 TripService.read로 현재 소유권을 확인한다. 키 없는 기존 호출은 유지한다.
- 신규 저장, receipt 재생, 비소유자 거부, 빈/비정규/중복 키를 테스트했다. 도메인 advice의 멱등 예외 누락으로 500 RED를 확인하고 공통 코드 및 Retry-After 전달을 추가했다.
- TripPlacePreferencesControllerIntegrationTest 14개 PASS(10초). 기존 응답 필드 수 검사는 현재 DTO의 requestedStayMinutes nullable 필드를 포함하도록 수정했다.
- OpenAPI의 누락된 키/오류 코드 테스트 2개 RED를 확인하고 canonical 계약·runtime manifest·카탈로그·문서 인터페이스를 동기화했다. 선택적 헤더의 required 생략은 false로 해석하고 UUID 형식·키 존재 여부를 별도로 검사한다. 재생 헤더와 처리 중 409 Retry-After도 기존 공통 정의로 공개한다.
- OpenAPI 3개 테스트 및 openApiDocs PASS(19초), frontend readiness 43 operations PASS, Python 계약/readiness 54개 PASS(3.203초). wire hash는 8b71d188a1af25618671328f4b75eaaf90509b566c806ffd43387d93507b5433이다. 카탈로그에 남아 있던 과거 ferry terminal 설명도 이미 구현된 canonical 계약과 일치시켰다.
- 실제 HTTP/PostgreSQL 공유 여행 조건 fixture에 장소 선호 3건을 추가했다. 테스트 SQL의 잘못된 trip_id 컬럼명을 실제 trip_plan_id로 바로잡은 뒤 replay·본문 충돌·소유권/삭제·validation 실패 예약 해제 2건 PASS(1분6초). 실제 preference write 이후 receipt 완료 trigger를 실패시키고 비트랜잭션 sequence로 쓰기 도달을 확인하는 rollback 검증까지 3건 PASS(1분7초). revision·선호 row·예약은 모두 원복되고 같은 키 재요청이 성공한다. 테스트 trigger/function/sequence는 finally에서 제거한다.
- 독립 부분 리뷰 신규 차단 finding 없음. 정식 승인/recorder는 실행하지 않았다.
- 후속 전체 unitTest 및 architectureTest PASS(22초), 기존 OS별 unit 9개 SKIP. 최종 전체 품질 게이트·Docker 완료 근거는 아니다.
- 아직 완료 아님: FE 계약 재인계 및 동일 키 재시도 orchestration이 남았다. 전체 gate/리뷰/PR 준비 완료로 해석하지 않는다.

기준 develop: `ef8aee5`, branch: `feat/53-generation-run-intake`.

## 이번 단계

사용자 최종 계획의 `/schedule-generations` 명령을 기준으로 요청을 세 필드로 제한한다.
`targetDayId`, 명시적 nullable `expectedActiveScheduleVersionId`, 정수 `candidateCount=3`만
허용한다. 생성에 필요한 날짜·장소 조건·좌표·원문을 클라이언트에서 복사하지 않는다.

- strict duplicate detection, trailing JSON 거부, 본문 크기 제한.
- 축약/비정규 UUID, 필드 생략 및 추가, count의 타입 변환을 거부한다.
- 유효 명령의 canonical JSON bytes를 명시적으로 생성한다. 공백/필드 순서는 동일하고
  null 활성 버전과 실제 UUID는 다른 본문이다. 이 bytes는 아직 멱등성 port에 연결되지 않았다.
- 예외는 안정적인 `INVALID_ASYNC_RUN_REQUEST`만 포함하며 원문/cause를 보존하지 않는다.

First RED: GenerationRequestCodec 부재로 compileTestJava 오류 2건.
최소 GREEN: 위 codec/command 및 단위 테스트 추가 후 focused unitTest 통과.
추가 경계를 포함한 codec 테스트 18건과 architectureTest가 통과했다.

## 접수 scope 검증

`GenerationAdmissionScope`는 소유자/여행/대상 Day를 먼저 검사하고 여행 revision,
nullable 활성 버전을 순서대로 비교한다. 다른 소유자에게 revision 정보를 노출하지 않는다.
DB adapter가 여행 잠금을 보유한 동일 접수 transaction에서 읽고 사용해야 하며,
이 값 객체 자체가 DB 잠금이나 경쟁 제어를 구현했다고 주장하지 않는다.

First RED: scope 클래스 부재로 compileTestJava 오류 3건.
최초 GREEN: scope 테스트 6건 + codec 테스트 18건, architectureTest 통과.
명령: `./gradlew --no-daemon spotlessApply unitTest --tests '*Generation*Test' architectureTest`.
`git diff --check` 통과. 전체 품질 게이트와 실제 DB 경쟁 검증은 아직 완료되지 않았다.

독립 검토에서 여행 밖 Day의 오류 코드가 #89와 다름을 확인했다. 해당 테스트를
422 `GENERATION_INPUT_CONSTRAINT_VIOLATION`으로 분리해 실제 실패(기존 TRIP_NOT_FOUND)를
확인한 뒤 수정했다. 소유권/여행 불일치는 여전히 404를 우선한다.
일반 pre-commit의 전체 unitTest/spotlessCheck는 통과했다. 첫 commit-msg 검사는
메시지 형식으로 거부되어 커밋되지 않았으며 규정 형식으로 재시도한다.
수정 후 `spotlessApply unitTest architectureTest` 전체 실행이 성공했다(플랫폼 조건 skip 포함).
생성 경계는 총 25건 모두 통과했고 독립 재검토 잔여 차단 finding은 0건이다.
이는 제한된 코드 검토이며 최신 HEAD 전체 품질 게이트 기반 PR 승인이 아니다.

## 선행·후속 연결

AI PR18 (`6b937c0`)은 저장 projection 기반만 제공하며 MCP 도구가 아직 해당 DTO를
반환하지 않는다. BE DB writer/worker/result/apply는 별도 연결해야 한다.

현재 여행 장소 선호는 must_visit/avoid만 지원하므로 preferred/체류시간 저장과
planner conditions를 먼저 정렬해야 한다. 기존 ASCII 멱등성 어댑터 패턴이 있으므로
UUID 전용 공통 registry의 계약을 무작정 변경하지 않는다.

기존 #89 로컬 작업은 아직 미병합이다. #53 이슈의 이전 `/generation-runs` 경로와
이전 MCP 도구명은 현재 사용자 계획에 맞춰 계약/외부 문서를 함께 정렬해야 한다.

이 커밋은 HTTP API를 노출하지 않고 run/snapshot/idempotency row를 쓰지 않는다.
따라서 queued 접수·ETag/owner 검사·원자 저장·worker 복구·결과 조회·원자적 적용,
OpenAPI/DB integration/full gate/PR 병합 완료를 주장하지 않는다. UI 변경은 없다.

## 후속 작업: 생성 lifecycle과 전용 실행 경계

- Supabase CLI 2.117.0의 `migration new generation_lifecycle`로 scaffold를 만들고,
  기존 미래 timestamp suffix보다 뒤인 `20260918000022`/Docker `060`으로 정렬했다.
- 기존 migration 수정 없이 생성 lease/fencing, 활성 Day uniqueness, 후보 전략/만료,
  종료 metadata 7일 보존 필드를 추가했다. 기존 running 작업이 있으면 migration은
  drain 요구로 중단하며 데이터를 삭제하거나 자동 보정하지 않는다.
- 원문을 저장하는 필드는 추가하지 않았다. 생성 내부 테이블의 anon/authenticated
  직접 권한은 제거하고 소유권 검증 API를 통한 접근만 준비한다.
- 실제 PostgreSQL에서 필드 부재 RED 후 migration GREEN을 확인했다.
- JdbcGenerationLeaseRepository 부재 RED 후 실제 PostgreSQL에서 최초 claim,
  중복 claim 차단, 새 인스턴스의 만료 lease 회수, 이전 fence 차단,
  중복 terminal 실패 차단 및 종료 시각 +7일 저장을 검증했다.
- 일반 MCP SDK의 35초 설정은 유지한다. 생성 활성화 시 별도 SDK에 165초 이상을
  적용한다. recommend와 generation parent의 평가 호출은 한 attempt에 단회다.
  worker 예산 factory는 recommend 1 + evaluate 3 + 저장 여유 15초를 포함한다.
- 독립 검토가 지적한 전역 timeout 회귀와 평가 retry 예산 불일치를 수정했다.
- migration 순서/체크섬 검사 14건, MCP/생성 단위 검사 및 architectureTest 통과.

아직 queued HTTP 접수, 전체 저장 조건 snapshot, 실제 worker scheduler/MCP 실행,
성공 후보 원자 저장, 결과 조회, 후보 적용은 연결되지 않았다. lease adapter와
전용 SDK가 존재한다는 사실을 전체 생성 worker 완료로 표시하지 않는다.

## #89 계약 선별 통합 및 저장 승인 진술 반영

별도 최신-develop 작업 트리에서 준비한 #89의 13개 계약/검증 파일만
`cherry-pick --no-commit`으로 가져왔다. 관련 없는 원격 브랜치를 통째로 병합하지 않았다.
과거 메모리 전용 조건을 그대로 배포 계약으로 사용하지 않고 사용자 승인 확인 진술에
맞춰 정규화 후보 24시간 durable 저장/복원으로 정렬했다. 제공자 서면 문서 직접 검토를
주장하지 않으며 원문/상세 geometry/사용자 원문 저장 금지는 그대로다.

보존 정책 테스트 5건이 기존 정책으로 실패하는 RED를 확인한 뒤 계약과 validator의
의도된 semantic checksum을 함께 갱신했다. 계약 validator 및 계약/연결 테스트 51건 통과.
새 digest: `2d9a6c4cf8f0352baa086c35f22dcf5a103e09e998f0acea4e777fff2a19636b`.
외부 문서 readiness와 runtime implementation readiness는 아직 승격하지 않았다.

## 장소 초안 저장·복원 확장

- `preferred`, nullable `requestedStayMinutes`(1~1440), 찜과 독립된 유효 canonical
  장소 선택을 추가했다. 원본/좌표 필드는 추가하지 않고 source는 user_input으로 기록한다.
- 서비스/DTO 부재 RED 및 실제 DB PLACE_NOT_FOUND RED 후 저장 통합 테스트를 통과했다.
  기존 owner/ETag 잠금, stale/tombstone 거부와 atomic replace는 유지한다.
- preferences 공개 계약·OpenAPI·wire digest를 함께 확장했다. 단위·아키텍처,
  generation lifecycle 실제 DB 검사 통과. 계약·마이그레이션 순서 36건 통과.
  OpenAPI 클래스는 slice 태그이므로 integrationTest 필터로는 실행되지 않은 점을 확인했고,
  sliceTest로 정정하여 실제 실행한다. 앞선 명령을 OpenAPI 통과 근거로 사용하지 않는다.
- 독립 부분 리뷰 결과 차단 finding 0건. 전체 생성 파이프라인 승인이나 PR 승인은 아니다.
- TripDetail의 placePreferences 누락 RED 후 같은 읽기 snapshot에서 날짜별 선호와
  체류시간을 반환하도록 연결했다. 빈 목록도 필드를 유지하고 legacy 생성 receipt DTO는
  변경하지 않는다. TripDetail canonical 계약·fixture·digest 검사 및 관련 계약 54건 통과.
- TripDetail 실제 DB 재조회/IDOR 및 관련 단위 테스트 통과. 완료 receipt의 기존 형태를
  TripDetailLegacyV12로 보존하는 RED/GREEN 및 계약 55건 통과. 실제 slice OpenAPI 검사와
  openApiDocs JSON 생성 통과. 추가 독립 부분 리뷰도 차단 finding 0건이며 union 설명 누락을 수정했다.
  generation 접수/전체 snapshot/성공 writer/조회/적용 미연결 상태는 아직 그대로다.

## Planner 조건 API 및 순차 버전 범위 (작업 중)

- TripPlannerConditions 모델/codec 부재 RED 후 canonical 숙소 ID 및 7개 닫힌 스타일 코드,
  중복 Day/스타일·원문 필드 거부를 구현했다. 일반 저장 30일과 AI 생성 5일은 분리한다.
- migration24 칼럼 부재 실제 PostgreSQL RED(기대2/실제0) 후 trip_days.lodging_place_id,
  trip_plans.planner_style_codes를 추가했다. 마이그레이션은 CLI로 생성하고 canonical suffix로 정렬했다.
- root lock/ETag/멱등 PUT /planner-conditions와 TripDetail 복원을 연결했다.
  Controller 서비스 경계와 기존 Problem advice 적용 누락을 검사 실패 후 수정했다.
  HTTP 입력 실패 매핑·전체 unit/architecture·저장/복원/IDOR/no-op DB 검사를 통과했다.
- 독립 리뷰가 지적한 미래 Day/UI 전용 스타일 변경의 전체 무효화를 affectsActive로 수정했다.
  해당 숙소가 적용된 해당 Day 또는 다음 Day에 영향을 줄 때만 일정 무효화한다.
- Day1 전용 적용 DB 회귀 테스트는 기존 assert_schedule_day_coverage의 모든 Day 필수 제약으로
  먼저 실패했다. migration25는 coverage_through_day_no의 nullable legacy/all-day와 명시적
  1~5일 prefix를 분리한다. 연속 prefix 누락·범위 밖 항목·봉인 후 범위 변경을 거부한다.
- migration25 초기 함수 종료 구문 오류는 컨테이너 exit3 로그로 확인해 수정했다.
  해당 실행이 실제 FAILED로 종료된 후에만 재검증을 시작했다. 이 실패를 성공으로 집계하지 않는다.
- 추가 독립 부분 리뷰에서 affectsActive와 migration25의 새 차단 finding 0건.
  prefix DB 회귀(미생성 Day2/UI스타일 수정 시 Day1 유지, legacy 전체coverage, prefix 누락/범위밖항목,
  봉인후변경 거부)와 planner 저장/복원, 전체 unit/architecture 재검증 통과했다.

미완료: planner PUT의 catalog/최종 OpenAPI 응답 계약, 순차 prefix를 user_edit/recovery clone에
전파, 미래 Day 장소 선호 변경 시 active 보존, 전체 generation 입력 snapshot/접수/worker/
후보 저장/조회/apply. partial 구현으로 전체 목표 완료나 PR 승인 상태를 기록하지 않는다.

## 순차 일정 편집·미래 Day 초안 회귀 (검증 중)

- 실제 PostgreSQL에서 미래 Day2 장소 선호 추가가 Day1 활성 버전을 null로 해제하는 RED를 확인했다.
  변경 전후 선호의 차이가 전역 또는 적용된 Day에 해당할 때만 무효화하도록 수정했다.
- `순차_일정의_추가와_수정도_Day범위를_복사한다`의 add/patch 두 경우 모두
  INTERNAL_SERVER_ERROR로 실패하는 RED를 확인했다. 기존 clone이 nullable prefix를 잃어
  legacy 전체 Day 봉인 검증을 받는 원인이었다.
- 두 user_edit 버전 생성 SQL에서 잠긴 활성 버전의 coverage_through_day_no를 복사한다.
  legacy NULL은 그대로 유지한다. recovery writer는 아직 구현되지 않았으므로 완료 범위에 넣지 않는다.
- 관련 두 Repository 전체 통합 테스트가 2분 33초에 성공했다. 단위·아키텍처 검사와
  spotlessApply도 통과했다(플랫폼 전용 기존 테스트 9개 skip). git diff --check 통과.
- 독립 읽기 전용 부분 리뷰에서 신규 차단 finding 0건을 확인했다. 전체 품질 게이트,
  전체 reviewer 승인 및 PR 생성은 여전히 미완료이며 이번 통과와 구분한다.

## Planner PUT 공개 계약 정합성 (검증 중)

- 실제 /v3/api-docs에서 planner PUT의 JSON 응답 schema 참조 누락 RED를 확인했다.
  Controller가 반환하는 다섯 필드를 닫힌 DTO로 직렬화하고 문서 인터페이스가 같은 DTO를 사용한다.
  If-Match/Idempotency-Key와 ETag/Idempotency-Replayed 및 오류 상태를 문서화했다.
- trips canonical 및 REST catalog에 기존 UI용 PUT을 등록했다. 변경 API 멱등성 검증의
  예외 범위에는 정확한 해당 PUT 경로만 추가했다. readiness를 임의로 ready로 승격하지 않았다.
- 초기 catalog 삽입 순서와 version 불일치 검사 실패를 수정하고 기존 endpoint 순서를 유지했다.
  예전 receipt union 개수에 고정된 테스트도 현재 보존 중인 V12를 포함하도록 정정했다.
  이전 receipt의 허용 여부 자체와 최신 GET의 거부 검증은 유지한다.
- 단위·아키텍처 통과, 최종 catalog 상태에서 Python 계약 92건 및 실제 slice OpenAPI 검사와
  openApiDocs 생성 통과(36초). 추가 독립 부분 리뷰의 신규 차단 finding은 0건이다.
  전체 생성 입력 snapshot/접수/worker/조회/apply와 전체 PR 품질 게이트는 아직 미완료다.

## 접수용 Day 경계 검증 (작업 중)

- GenerationDayBoundary 부재로 compileTestJava RED를 먼저 확인했다. 저장 Day 목록·숙소
  anchor·항공 입출도와 서버에서 승인된 공항 ID만 사용해 출발/도착 장소 및 활동 창을 결정한다.
- 첫날/중간날/마지막날/당일 경계와 직전 숙소 연결을 검증한다. 앞 Day 건너뛰기, 6일 이상,
  선박, 누락된 숙박/활동시간, 외부 Day anchor, 중복 ID 및 불연속 날짜는 접수 조건 오류다.
- UTC 항공 시각은 같은 instant의 KST로 변환하고 활동시간과 교집합이 없으면 거부한다.
  시간·거리·비용을 만들어 넣지 않으며 대기/이동 buffer 검증은 후속 AI evidence 경계의 책임이다.
- 최초 4개 테스트와 architecture GREEN 이후 UTC/시간 교집합/Day 계보 회귀를 추가해
  전체 unit/architecture 통과했다. 모델은 아직 intake adapter에 연결되지 않았다.
- 독립 리뷰가 발견한 사용자 지정 터미널의 승인 공항 조용한 치환은 추가 실제 RED
  (`Expecting code to raise a throwable`)로 확인했다. customTerminalName이 있으면
  canonical resolver의 승인 없이 해석하지 않고 거부하도록 수정했다. 전체 unit/architecture
  재검증 통과(53초) 및 리뷰 재확인으로 해당 finding 해결을 확인했다.

## 저장 여행 조건의 내부 입력 projection (작업 중)

- GenerationTripInput 부재 RED 후 TripAggregate에서 revision/base, 검증된 DayBoundary,
  Day 활동 창, canonical 숙소/선호 ID만 복사하는 내부 projection을 구현했다.
  이 모델은 MCP 공개 계약의 원본이 아니며 Pydantic 생성 계약을 대체하지 않는다.
- 실제 직렬화 검사는 여행 제목·항공 메모·편명이 제외됨을 확인한다. UI 전용 trendy/local도
  AI 스타일 입력에 넣지 않는다. public_transit→bus, taxi→taxi, walk→walk만 지원한다.
  rental_car를 다른 수단으로 임의 변환하지 않는다. 일반 여행 저장의 enum은 변경하지 않았다.
- 명시 체류시간을 우선하고 없으면 추천 정책 출처·버전·시행 시각을 함께 고정한다.
  추천 부재 또는 provenance 부재는 입력 오류이며 60분 기본값을 사용하지 않는다.
- 초기 테스트 및 architecture GREEN 후 제외 장소, 미지원/중복 모드, 잘못된 체류시간,
  중복 장소와 필수 장소 10개 한도를 추가해 전체 unit/architecture 통과했다(50초).
  독립 부분 리뷰 신규 차단 finding 0건.
- 후속 previous_days 연결에서는 이전에 방문한 전역 must_visit을 다시 필수로 전달하지 않도록
  적용 history와 함께 필터링해야 한다. DB 불변 저장·접수 트랜잭션·history 연결은 아직 미완료다.
- 별도 #89 worktree의 오래 실행된 품질 게이트가 정상 종료했다. 상태 파일은
  ee19f7d5b82b392673a11f29d362f1f49df5bad5에 대해서만 check/coverage/OpenAPI/Docker SUCCESS다.
  현재 generation 브랜치의 품질 게이트 근거로 사용하지 않는다.

## 불변 여행 입력 저장과 worker claim 연결

- 실제 PostgreSQL 테이블 부재 RED(기대1/실제0) 후 CLI로 migration을 생성했다.
  기존 future timestamp 순서를 유지해 새 migration26/064로 배치하고 manifest·Docker·inventory를 갱신했다.
- generation_trip_inputs는 비공개 스키마, RLS, service_role SELECT/INSERT만 허용한다.
  UPDATE는 관리자 연결에서도 trigger로 거부하고 부모 run/여행 삭제 시 cascade된다.
  원문·geometry·좌표가 들어갈 수 없는 닫힌 내부 projection과 작업/소유자/여행/Day/base/revision 계보를 검사한다.
- Java/DB는 schemaVersion+runId+ownerId+tripInput canonical envelope의 SHA-256을 공유한다.
  복원 첫 GREEN 실패는 Jackson의 KST→UTC 자동 변환이었다. 라이브러리 소스로 확인 후
  해당 reader에서만 시간대 자동 변환을 끄고 전역 설정은 유지했다.
- JDBC adapter 부재 RED 후 새 인스턴스에서 동일 입력/해시 복원, UPDATE 거부, 틀린 해시와
  중첩 원문/좌표 거부의 실제 DB GREEN을 확인했다.
- 리뷰 finding: queued 검사와 run 상태 변경 race, capture 의미검증의 restore 누락.
  Trip→run 잠금 순서를 명시하고 Java 생성자·SQL에 연속 Day/활동창/숙소/공항/장소 선호의
  대응 검증을 적용했다. airportPlaceId를 별도로 고정했다. Java 예외 미발생과 DB true 오반환
  RED 후 Day 불일치 GREEN을 확인했고, 추가 부분 리뷰에서 코드 보강을 확인했다.
- command-only run이 실제 claim되는 RED를 확인한 뒤 lease query에 여행 snapshot 계보 join을
  추가했다. 완전한 snapshot이 없는 작업은 실행하지 않는다. 기존 lease 복구 fixture도 이를 저장한다.
- 전체 unit/architecture는 통과했다. 2세션 테스트의 정리 단계에서 Day 외래키 cascade 누락을
  실제로 발견했다. 기존 run과 같은 ON DELETE CASCADE로 정렬한 뒤 snapshot/lease 통합 10개가
  통과했다(2분 3초). cleanup 실패로 잔류하던 run의 다음 테스트 간섭도 함께 해소됐다.
  해당 lease join/cascade 보완에 대한 독립 부분 리뷰 신규 차단 finding은 0건이다.
  service_role 실제 INSERT/SELECT 추가 검증에서 GENERATION_INPUT_UNAVAILABLE RED를 확인했다.
  로컬 DB의 has_function_privilege 결과 canonicalize_command_jsonb EXECUTE=false였으므로
  이 순수 변환 함수에만 service_role 실행 권한을 명시했다. security invoker는 유지한다.
  같은 role의 canonical 함수 호출 및 실제 INSERT/SELECT 복원이 통과했다.
  최종 spotlessApply/test/architectureTest와 snapshot/lease 통합 11개가 성공했다(2분 43초).
  migration inventory Python 14개도 통과했으며 최소 EXECUTE 보완의 독립 부분 리뷰 차단 0건이다.
- Supabase CLI 2.117.0 advisors를 별도 테스트 전용 compose DB에서 실행했다.
  새 snapshot 객체 관련 finding은 없으며 기존 public.spatial_ref_sys RLS ERROR 1건과
  public 확장(pgcrypto/postgis/btree_gist/fuzzystrmatch) WARN 4건을 보고했다.
  전체 보안 무결점으로 표시하지 않는다. 기존 공간 확장 이동은 이 생성 입력 변경에 섞지 않는다.
  검사 전용 tmpfs DB 컨테이너와 빈 전용 네트워크는 검증 후 삭제했다. 사용자 데이터는 없다.
  생성 접수 HTTP/원자 queue transaction, previous_days, 성공 결과 writer/조회/apply는 아직 미완료다.

## 생성 접수 API와 원자 저장 연결

- #53의 오래된 generation-runs 경로 대신 최신 #89의 schedule-generations 계약을 따른다.
  Store 부재 compile RED 후 잠긴 저장 Trip→명시된 추천 체류시간→두 snapshot+queued 원자 저장을 연결했다.
- 공항 ID는 서버 환경설정으로만 받으며 승인 TourAPI import가 succeeded이고 살아 있는
  제주국제공항 canonical 장소인지 검사한다. FE에 UUID/좌표를 넣지 않는다.
  테스트 fixture도 실제 import/snapshot 계보가 필요함을 DB RED로 확인해 계보를 함께 구성했다.
- 최초 접수/owner/ETag DB 2개 GREEN(1분 7초). 접수는 active나 candidate version을 만들지 않으며
  외부 호출 의존성이 없다. 요청 body는 3개 닫힌 필드와 16KiB 상한을 유지한다.
- Controller 부재 RED 후 202/Location/Retry-After/멱등 replay 경계를 구현했다.
  printable key 전체 SHA-256을 namespace에 포함해 기존 UUID registry와 충돌 없이 연결하고
  사용자 key 원문은 run에 저장하지 않는다. 완료 replay는 최신 ETag 비교보다 먼저 처리한다.
- 아키텍처 RED로 service 경계 누락을 확인해 application service를 경유하도록 수정했다.
  실제 controller class/POST mapping 추가를 inventory에 반영했다. 단위/아키텍처 재검증 통과.
- DB 검증을 실제 commit/rollback 경계로 확장해 동일 key 재생/다른 body 충돌,
  snapshot 실패 후 run+command 롤백, active run 중복과 선박 거부 6개가 통과했다.
  전체 unit/architecture도 통과했다(합계 2분 12초).
  별도 부분 리뷰 신규 차단 0건이지만 전체 승인은 아니다.
- 이전 Day history, worker 성공 writer, 조회와 원자 apply는 후속 구현 대상이다.
  #89 fieldErrors.reason과 기존 공통 writer의 fieldErrors.detail 불일치를 Python/MVC RED로
  재현하고 공통 field/detail에 정렬했다. 공통 writer는 변경하지 않고 #89 계약·검증기·세부 안내를
  맞췄다. 새 계약 digest는 96c5671aaba303fdaafaa9a5c32932c48dfe6bbfad9841d52ff8a901165a67d6다.
- 실제 OpenAPI 출력에서 오류의 byte schema와 정수 후보 수의 문자열 enum을 발견해 새 검사로 RED를
  확인했다. Problem schema와 정수 minimum/maximum=3으로 교정하고 nullable base·필수 헤더를 검증했다.
  최종 spotlessApply/test/architectureTest, 실제 DB 6개, openApiDocsTest/openApiDocs가 모두 통과했다
  (2분 12초). 계약 Python 48개 및 두 Compose config 검증도 통과했다.
- 독립 부분 리뷰에서 마지막 service·field/detail·OpenAPI 차분의 신규 차단 finding 0건을 확인했다.
  전체 worker/조회/apply와 최종 동일 SHA 품질·Docker·PR 승인은 아직 완료가 아니다.

## 진행도 재확인과 실제 MCP 계약 드리프트 수정

- e41170b clean tree에서 접수 구현과 남은 worker/조회/apply를 다시 확인했다. UI 변경은 없다.
- `McpExpectedCatalogTest`에 AI PR18의 생성된 recommend 입력/출력 fingerprint를 고정하는
  테스트를 먼저 추가했다. 기존 입력 해시 e0122abe와 현재 bdee2038의 불일치로 실제 RED를 확인했다.
- AI 저장소 HEAD 8d3e10c의 Pydantic/FastMCP 생성 manifest를 기준으로 recommend 두 해시만
  동기화했다. 다른 다섯 도구는 변경하지 않았다. 두 저장소 manifest의 byte 일치도 cmp로 검증했다.
- GREEN: spotlessCheck, MCP 단위 테스트, architectureTest 성공(16초).
  전체 test와 architectureTest 성공(44초, macOS에서 기존 파일시스템 관련 9개 skip).
  AI `uv run pytest tests/mcp/test_server.py -q` 9개 통과: 실제 FastMCP manifest 재생성 검사 포함.
- 다음 연결의 식별자 경계를 확인했다. BE tour_places.id UUID를 AI place_id로 직접 보내면 안 된다.
  AI normalize_tour_places는 TourAPI contentid로 `tourapi.place:{contentid}`를 만들고
  runtime_generation_gateway는 active_place.fact_id로 조회한다. 승인된 canonical source ID 매핑과
  이름 필수 AccommodationInput 변환을 worker에서 연결해야 한다. 원문·좌표 임의 복사는 하지 않는다.
- 전체 worker·후보 저장·GET·apply·FE 실연결과 최종 품질/Docker/Reviewer/PR은 아직 미완료다.

## Worker용 canonical 장소 매핑

- 입력 UUID를 AI fact ID로 오인하지 않도록 GenerationPlaceBindings 테스트를 먼저 작성했다.
  클래스 부재 compile RED 후 TourAPI contentId 기반 양방향 매핑, 공식 숙소 이름 필드,
  미지 ID/중복 canonical/중복 source ID 거부를 구현했다. 임의 이름 검색이나 좌표는 전달하지 않는다.
- GenerationPlaceResolver application port와 JDBC adapter를 추가했다. 단일 SELECT로 성공한
  TourAPI import, stale/tombstone/source 삭제 상태, 전체 요청 ID 존재를 확인한다.
  SQL 오류 원문은 cause 없는 GENERATION_INPUT_UNAVAILABLE로 변환한다.
- PostgreSQL 통합 테스트의 adapter 부재 RED 후 실제 canonical fixture 조회와 누락 ID 거부,
  빈 입력을 검증했다. 공식 contentId 모양을 갖춘 synthetic fixture로 정렬했다.
  spotlessApply/test/architectureTest 및 GenerationIntakeIntegrationTest 7개 성공(1분 52초).
- 독립 부분 리뷰 신규 차단 finding 0건. 전체 승인이 아니며 recorder는 실행하지 않았다.
  AI가 추가 발굴한 장소는 입력 allowlist에 없으므로 후보 저장에서 별도의 검증된 역매핑이 필요하다.
- Supabase changelog와 DB 연결 문서를 확인했다. 이번 변경은 기존 서버 JDBC 읽기만 사용하고
  스키마·RLS·ACL·배포 설정을 변경하지 않는다. 실제 로컬 PostgreSQL 쿼리로 검증했다.
- 접수 실패 주입 테스트에서 변환 실패에도 queued run이 생성되는 RED를 확인했다(1분 7초).
  JdbcGenerationIntakeStore가 선택/회피/시작/종료 장소를 resolve한 뒤에만 INSERT하도록 연결했다.
  최종 spotlessApply/test/architectureTest 및 실제 DB 통합 8개 성공(1분 53초).
  추가 접수 연결의 독립 부분 리뷰 차단 0건이다. worker 시점 publication 재검증은 여전히 필요하다.
  #79 댓글에도 RED/GREEN 진행 근거를 기록했다. 실제 MCP 실행·후보 저장·조회·apply·최종 PR은 미완료다.

## 저장 조건 → MCP 하루 조건 변환

- GenerationMcpDayConditions 부재 compile RED 후 시간·장소·체류시간·선택/회피·이동 우선순위·
  지원 스타일을 변환했다. 내부 UUID/사용자 원문/좌표는 제외한다. 첫날/마지막날 숙소 경계를
  보존하며 당일 공항→공항도 표현한다. previous_days/envelope는 orchestrator가 별도로 결합해야 한다.
- OffsetDateTime.toString의 초 생략을 RFC3339 기대 테스트로 RED 재현하고 ISO formatter로 수정했다.
  synthetic fixture와 실제 Java 출력의 전체 JSON tree 일치를 검사하고 AI Pydantic 수용도 확인했다.
- 독립 리뷰에서 서버 추천 시간을 사용자 고정 시간으로 오인하는 의미 충돌을 발견했다.
  BE 출처 기대 테스트 RED와 AI extra_forbidden RED 후 AI #19에서 Pydantic 단일 원본을 확장했다.
  user_requested는 기존 입력과 호환하며 서버 source/policy_version/policy_effective_at은 필수다.
  BE는 저장된 정책 출처를 그대로 전달하고 숫자를 새로 만들지 않는다.
- AI worktree /Users/gwongwangjae/jeju_AI_generation_contract, 커밋 45f585a.
  모델·schema·MCP manifest·합성 산출물·checksum을 정규 생성했으며 한글 검사/Ruff/Pyright와
  pytest 595 passed, 9 skipped를 확인했다. 아직 AI #19 PR/배포는 완료하지 않았다.
- BE manifest는 AI 생성본과 byte 일치한다. 최종 spotlessApply/test/architectureTest 성공(52초).
  독립 재검토에서 의미 충돌 finding 해소 및 추가 차단 0건이다. 이는 전체 승인/recorder가 아니다.
- AI #19가 배포되고 BE manifest가 함께 적용되기 전 연결을 활성화하지 않는다.
  실제 worker·후보 저장·조회·apply·FE 실연결 및 최종 품질/Docker/PR은 계속 미완료다.

## AI 계약 PR와 응답 근거 계보 검증

- AI 선행 계약을 PR https://github.com/Timing-Jeju/jeju_AI/pull/20 으로 open했다.
  HEAD 45f585ae68ca1d5b997b6645d1ed97dadfeb48fa, CI run 34758389481의 Offline quality gates 및
  격리 PostGIS/MinIO integration 모두 SUCCESS를 확인했다. OPEN이며 최종 BE 통합 PR은 아니다.
- 생성 응답에는 실행 후 새 fact ID가 생기므로 기존 고정 inboundIdAllowlist만으로는 연결할 수 없다.
  응답에서 모든 ID를 긁어 허용하는 우회 대신 GenerationEvidence의 최소 계보 검증을 추가했다.
- 클래스 부재 compile RED 후 source 승인/선언, fact 중복, derivation 입력과 timeline/결정 참조의
  폐쇄성을 검증했다. 별도 자기참조 RED 후 비재귀 Kahn 검사로 순환을 거부한다.
- fact value·formula·geometry·사용자 원문은 반환 projection에 복사하지 않는다.
  request.previous_days의 독립 ledger는 현재 Day 참조와 섞지 않는다. 이는 AI Pydantic의
  recommendations/place_decisions 검증 범위를 따른다. 빈 실패 ledger·정책 fact도 허용한다.
- 아직 MCP client/worker가 이 검증기를 호출하는 연결, canonical 결과 장소 검증, 후보 저장,
  조회·apply는 미완료다. 이 검증기나 AI PR의 CI를 전체 BE 기능 완료 근거로 사용하지 않는다.
- 최종 spotlessApply/test/architectureTest 성공(49초), 기존 macOS 파일시스템 9개 skip.
  독립 부분 리뷰 신규 차단 finding 0건이다. JSON Schema 선행 검증과 승인 source 집합 주입이
  사용 전제이며 전체 승인/recorder 실행은 아니다.

## 후보 집합의 부분 성공·모순 응답 거부

- GenerationCandidateSelection 클래스 부재 compile RED 후 정확히 세 전략/서로 다른 route ID와
  rank 1·2·3, 필수 포함·회피 제외 및 AI와 동일한 다양성 경계값을 구현했다.
  전략/ID만 바꾼 동일 경로는 거부하고 실제 AI가 허용하는 체류시간 차이는 인정한다.
- 독립 리뷰에서 JSON Schema만으로 잡지 못하는 status/failure 모순을 발견했다.
  성공+failure 응답 테스트의 expecting throwable RED 후 성공은 failure null/누락만,
  insufficient는 후보 0개와 failure 객체가 있는 경우만 허용하도록 수정했다.
- 전체 spotlessApply/test/architectureTest 성공(49초), 후보 집합 테스트 7개 통과.
  독립 재검토에서 이전 finding 해소와 신규 차단 0건을 확인했다. 전체 승인은 아니다.
- 이 판정기는 JSON Schema 선행 검증을 전제한다. MCP client 연결과 전체 시간/인접 leg/
  canonical 장소 검증 및 원자적 저장은 후속 범위이며 아직 기능 완료로 표시하지 않는다.

## AI 추가 장소의 canonical 역매핑

- resolveFactIds 부재 compile RED 후 숫자형 tourapi.place content ID만 받는 역조회 port를
  기존 GenerationPlaceResolver에 추가했다. canonical 정방향과 같은 단일 SELECT의 승인/성공
  TourAPI import·live 조건을 공유하고, 요청 값은 SQL 파라미터로만 전달한다.
- 실제 PostgreSQL 테스트에 정상 역매핑·빈 입력·미지 ID·내부 UUID·잘못된 prefix·SQL 모양
  문자열 거부를 추가했다. 일부 장소만 매핑되거나 중복되면 전체 실패한다.
- 독립 부분 리뷰 신규 차단 0건. 실제 worker의 응답 검증 호출과 후보 저장 시점의 재검증은
  아직 연결 전이다. 새 스키마·권한·원문 저장·UI 변경은 없다.
- MCP 실제 코드를 확인하니 DayTripResponse.request_id는 서버가 새로 발급하고 requestId는
  envelope 검증 후 버린다. 따라서 두 ID를 같다고 가정하면 정상 응답도 거부한다.
  후속 응답 상관 검증은 되돌아온 구조화 request와 저장 입력의 일치를 확인해야 한다.
- 최종 spotlessApply/test/architectureTest와 GenerationIntakeIntegrationTest 실제 DB 9개가
  모두 성공했다(1분 57초). 역매핑 코드를 추가했지만 전체 생성·적용 통합 완료는 아니다.

## 생성 전용 MCP 검증·projection 호출 경계

- callGeneration 부재 compile RED 후 실제 SpringAiJejuMcpClient SDK 실행 경로에
  generation parent/recommend tool/빈 static inbound/non-null projector 전용 진입점을 추가했다.
  Schema/hash 검증 후 도메인 검증·정규화 projector가 반환한 값만 호출자에게 전달한다.
  기존 call은 동일한 static ID allowlist 검증을 유지한다. 전송·감사·재시도는 공통 invoke를 공유한다.
- Schema 실패는 projector를 호출하지 않고, projector 실패/null 결과는 원문·cause 없는
  MCP_CONTRACT_INVALID로 감사한다. generation 호출은 기존의 단일 SDK 시도를 유지한다.
- 정상 failure:null을 Map.copyOf가 거부하는 문제를 생성/기존 호출 두 테스트의
  MCP_INTERNAL_ERROR RED로 재현했다. Schema 변환과 McpInvocationResult 양쪽에서
  새 Map의 unmodifiable wrapper로 null을 보존하도록 수정했다.
- 전체 spotlessApply/test/architectureTest 성공(50초), 신규 호출 경계 테스트 6개 통과.
  독립 부분 리뷰와 null 수정 재검토 모두 신규 차단 0건이며 전체 승인/recorder는 아니다.
- 실제 도메인 projector의 요청·근거·canonical 장소·시간·최소 저장 검증 구현과 worker 연결은
  아직 후속 작업이다. generic projector가 존재한다는 사실을 그 검증의 완료로 간주하지 않는다.

## 저장용 타임라인 검증·추출

- GenerationTimeline 부재 compile RED 후 KST/활동창/Day 장소 경계, 이벤트 시간과 체류분,
  순서·겹침, canonical 장소와 근거 ID, 유형별 합계를 검증하는 내부 projection을 구현했다.
  이벤트·이동수단·거리·위험 코드만 추출하며 title/geometry/원본 상세는 복사하지 않는다.
- 초기 테스트와 architecture 성공 후 실제 이동거리·risk 보존 및 허용외 수단/겹침 검사를 추가했다.
  독립 리뷰에서 후보 place_ids와 실제 방문 장소의 연결 및 상세 시간 일관성 누락을 발견했다.
- 두 테스트의 expecting throwable RED(6개 중 2개 실패) 후 실제 STAYS 이벤트의 장소 순서를
  place_ids와 exact 비교했다. 상세 유형·장소와 visit arrival/entry/departure/stay_minutes도
  외부 이벤트와 일치시킨다. 공개 계약에 없는 임의의 전체 장소 유일성 제약은 제거했다.
- 독립 재검토에서 이전 두 finding 해소 및 신규 차단 0건이다. 전체 승인/recorder는 아니다.
- Schema/근거 계보 선행 검증이 필요하며 이 클래스만으로 버스 상세·모든 비용/거리 집계·
  요청 상관·원자적 저장의 완성을 의미하지 않는다. 실제 projector/worker 연결은 후속 작업이다.
- 최종 spotlessApply/test/architectureTest 성공(49초), 타임라인 테스트 6개 통과다.

## 실제 AI 응답 Schema 기반 검증 조합

- GenerationCandidateProjection 부재 compile RED 후 Evidence→CandidateSelection→Timeline을
  하나로 묶었다. 타임라인 한 건이라도 부적합하면 모든 후보와 계보를 폐기해 insufficient 0개를
  반환하고, 미지 근거/미지 canonical ID는 오류로 전달한다. 후보는 rank 순으로 정렬한다.
- AI 45f585a의 생성 Schema와 합성 응답을 test resource로 복사했다. 원본과 byte 동일하며
  출력 Schema의 정규화 hash는 실제 BE manifest와 비교한다. 수작업 공개 Schema를 만들지 않았다.
- 공식 SDK mock transport의 callGeneration에서 실제 출력 Schema와 새 조합 projection을 실행했다.
  합성 장소 ID 변환은 ID 필드에만 적용한다. 초기 문자열 치환이 meal/rest type까지 바꿔 Schema
  실패한 것을 확인하고 구조화 ID 변환으로 수정했다. fixture 출처와 검증 한계를 함께 기록했다.
- 점수 총합/weight 합 불일치의 expecting throwable RED 후 소수 점수를 유지하면서
  가중치 100 및 반올림 합계를 검사했다. 원문·title·geometry·좌표·fact value는 결과에 없다.
- 최종 spotlessApply/test/architectureTest 성공(50초), 조합 테스트 4개 통과.
  독립 부분 리뷰 신규 차단 0건이며 전체 승인/recorder는 아니다.
- 입력 Schema는 이 SDK 조합 테스트 전용이다. 저장 입력 기반 Scope, request echo, 모든 비용/
  버스 상세 검사와 실제 worker/DB 후보 저장·조회·apply·FE 연결/최종 PR은 여전히 후속 범위다.

## 저장 입력 기반 후보 검증 기준 연결

- 공개 projection 진입점을 GenerationTripInput 기반으로 변경했다. 임의 Scope/필수/회피
  목록을 받던 진입점은 private으로 제한하고 저장된 boundary·transportModes·places와
  고정 stayMinutes에서 기준을 파생한다. 모든 대상/경계 ID는 canonical bindings로 확인한다.
- 변경된 메서드 부재 compile RED 후 기존 SDK/실제 출력 Schema 조합 테스트도 새 진입점으로
  연결했다. 같은 합성 응답에 저장 stay30이면 insuff, stay60이면 성공, 회피 추가면 insuff를 검증했다.
- 전체 spotlessApply/test/architectureTest 성공(51초), 조합 테스트 5개 통과.
  독립 부분 리뷰 신규 차단 0건이다. 전체 승인/recorder는 아니다.
- 저장 입력에서 기준을 파생하는 연결은 완료했지만 repository에서 worker가 snapshot을 읽고
  MCP 호출/요청 echo/비용·버스 상세/DB 후보 저장·조회·apply까지 수행하는 전체 흐름은 미완료다.

## 저장 스냅샷에서 실제 MCP 실행기 연결

- GenerationPlanExecutor/McpGenerationExecutor 부재 compile RED 후 저장 trip/command snapshot을
  읽고 run·owner·trip·nullable base·계약/알고리즘·command hash·Day·후보 수를 검증했다.
  canonical 정방향 매핑→공식 SDK 단일 recommend→근거 검증→추가 장소 역매핑→최소 후보 추출을 연결했다.
- 실제 AI 45f585a FastMCP list_tools의 inputSchema도 복사해 입출력 Schema hash를 BE manifest와
  검증했다. 테스트 transport/repository는 mock이며 실제 외부 호출이나 DB 후보 쓰기를 주장하지 않는다.
- 호출 전후 deadline을 확인하고 원문/좌표/내부 owner·trip UUID를 wire에 넣지 않는지 검사했다.
  변조된 계보·hash·Day·count와 누락 snapshot은 MCP 호출 전 거부한다.
- 재확인 중 runType/contractVersion/algorithmVersion null의 NPE RED(5개 중 1개 실패)를 재현하고
  상수 equals로 GENERATION_INPUT_UNAVAILABLE만 반환하도록 수정했다.
- 최종 spotlessApply/test/architectureTest 성공(50초), 기존 macOS 제한 테스트 9개 skip이다.
- 실행기 bean/승인 source 설정, 실제 worker claim·heartbeat·fence·원자 후보 저장, previous_days,
  request echo·비용/버스 상세 검사와 조회/apply/FE 실제 연결은 후속이다. 최종 BE PR은 아직 없다.

## 생성 워커 오케스트레이션과 재시도

- GenerationWorker/GenerationCompletionStore 및 lease retry 메서드 부재 compile RED 후 구현했다.
  워커는 단일 claim, 시작 heartbeat, 실행 전후 deadline, planner→원자 완료 저장 port를 연결한다.
  이 port가 후보 전체와 run 성공을 함께 commit하며 워커는 별도 성공 UPDATE를 하지 않는다.
- DB retry는 running·현재 fence·유효 lease·attempt<3을 요구한다. 지연은 0~60초,
  정형 오류 코드만 저장하고 lease 필드는 비운다. 지연 전 재claim·stale/expired/terminal retry를
  거부하며 다음 claim에서 attempt/fence를 증가시킨다. 테이블/권한/migration 변경은 없다.
- 독립 리뷰에서 supervisor future 종료가 실제 스레드 종료보다 빨라 추가 claim을 허용하는
  문제와 MCP retryable 오류 미변환을 발견했다. latch 회귀의 claim 2회 RED, 실제 SDK
  MCP_TIMEOUT 타입 불일치 RED를 각각 확인한 뒤 수정했다.
- ActiveRun은 실제 실행과 완료 처리가 모두 끝나야 admission을 해제한다. 늦게 시작된 실행과
  terminal 뒤 반환된 planner 결과를 거부한다. MCP adapter는 닫힌 코드만 application 예외로
  변환하며 retryable timeout/transport만 재시도한다. 원문/cause는 전달하지 않는다.
- 독립 재검토에서 이전 finding 해소·신규 차단 0건이다. 전체 승인/recorder는 아니다.
- 중간 전체 test/architecture 성공(50초), 실제 DB retry/재시작 통합 2개 성공(2분 16초).
  추가한 중단·반환객체 전달·expired retry까지 최종 spotlessApply/test/architectureTest 및
  GenerationLeaseRepositoryIntegrationTest 성공(2분 41초). 워커 12개·DB 2개 통과,
  기존 macOS 제한 9개 skip이며 종료 후 이번 Testcontainers 자원 정리를 확인했다.
- 실제 CompletionStore JDBC 후보 writer와 bean/scheduler 등록은 아직 미완료다.
  저장 전 request echo·이동 상세/비용·인접 구간 검증, previous_days, 조회/apply/FE·최종 PR을
  계속 구현해야 한다. 현재 워커 port를 DB 후보 저장의 완료 근거로 사용하지 않는다.

## 저장용 하루 합계와 이동 endpoint 계약 점검

- Candidate.totals 부재 compile RED 후 GenerationTotals를 연결했다. Pydantic Totals의
  시간·거리·비용 범위와 is_estimated 및 근거 ID를 명시 필드로만 복사한다. 비음수·min/max·
  시간 합·거리 합·버스/택시 비용 합을 long 연산으로 검사하고 알려진 fact ID만 보존한다.
- 실제 합성 응답의 합계 보존 및 마지막 후보의 거리/비용 합 불일치 거부 테스트를 추가했다.
  조합 테스트 7개·architecture 16초 성공, 독립 부분 리뷰 신규 차단 0건이다.
- 이동 상세 연결을 시도하면서 정상 응답 거부 RED를 발견해 가정을 재검토했다.
  AI runtime_routing._walking_leg의 from_id/to_id는 place_id가 아닌 entrance_id이며,
  대표좌표 endpoint는 place-point:{place_id}다. 이를 장소 ID와 직접 비교하던 임시 코드와
  그 가정에 의존한 테스트는 커밋하지 않고 제거했다. 기존 AI 합성 resource는 수정하지 않았다.
- AI runtime_generation_gateway._load_entrances가 반환하는 place_entrance fact value에는
  entrance_id만 있고 place_id 연결은 없다. BE가 검증할 수 있는 승인된 입구↔장소 계보를
  AI 계약/근거에서 보완한 다음 인접 구간 검증을 연결해야 한다. 이를 임의 prefix 추정으로
  대체하지 않는다. 합성 balanced 버스도 start09:00/access5/depart09:20/arrive09:35/egress5와
  이벤트 end09:45가 다르므로 실제 이동 상세 검사에 사용하기 전 fixture 정합성 보완이 필요하다.
- 이번 변경은 하루 합계 보존이며 이동 상세/DB 후보 writer·조회/apply/FE와 최종 PR은 미완료다.
- 최종 spotlessApply/test/architectureTest 성공(47초, 기존 macOS 제한 9 skip).

## 승인된 입구와 canonical 장소 연결 검증

- AI #21에서 승인 입구 fact에 canonical place_id를 포함했다. 기존 PR #20의
  68cf870에 반영됐으며 원격 CI 34762945268의 offline 및 격리 PostGIS/MinIO 검사가 성공했다.
  Pydantic EvidenceFact.value 내부 보완이므로 schema hash는 같지만 런타임 버전 의존성은 남는다.
- BE GenerationEntranceEvidence 부재 compile RED 후 승인 source·source kind·canonical 장소
  형식·동일 입구의 상충 관계를 검증하고 MCP 결과 callback에 연결했다. 이동별 require는
  실제 입구 fact ID가 해당 이동 근거에 포함돼야 통과한다. 원문/좌표를 저장 결과에 복사하지 않는다.
- 최초 전체 spotlessApply/test/architectureTest 성공(50초, 기존 macOS 9 skip), 독립 부분
  리뷰 신규 차단 0건이다. 추가 null endpoint 회귀는 NPE RED를 확인해 안정적인 계약 오류로 수정했다.
- null 수정 후 전체 spotlessApply/test/architectureTest도 성공했다(52초, 입구 검증 4개 통과).
- 실제 leg에서 require 호출, 대표좌표 endpoint·버스 fixture 정합성, JDBC 후보 writer,
  조회/apply/previous_days/FE 및 최종 BE PR은 미완료다. UI와 운영 flag는 변경하지 않았다.

## 실제 후보의 도보·버스 시간 연결 검사

- AI 6efe58e의 합성 예제와 provenance를 동기화했다. 원격 CI34763456146 성공을 확인했으며
  input/output Schema는 기존 Pydantic 생성본과 동일하다.
- 버스 access planned_minutes를6으로 변조해도 success인 RED를 재현했다.
  GenerationTransferTiming을 실제 CandidateProjection 호출에 연결해 도보 산술·버스 정류장
  연결·환승·승차 여유·첫 대기·전체 이동시간을 검사한다. 실패 후보 하나면 insufficient0이다.
- 조합8개/architecture16초 및 최초 전체 unit/architecture50초 성공이다.
  독립 부분 리뷰의 egress 누락 NPE를 RED로 재현하고 역참조 전 walk 검증으로 수정했다.
- 초단위 출발에 대해 AI runtime 전체 이동분의 ceil 변환과 비교해야 함을 추가 RED로 확인했다.
  정확한 시간표를 그대로 사용하되 이벤트 종료는 출발+올림된 전체분으로 검사한다.
- AI _select_route의 ModeDecision 대기는 양수 초 차이의 floor이며 admission 한도 검사 ceil과
  다르다. 초단위 대기14분 정상 회귀 RED 후 같은 floor로 수정했다. 불필요한 시간 추정은 하지 않는다.
- 이 변경은 시간·정류장 연결 검사다. 장소↔입구 require 호출, 거리·운임 상세 검사와
  실제 JDBC 후보 writer/조회/원자 적용/previous_days/FE·최종 PR은 여전히 후속이다.
- 최종 spotlessApply/test/architectureTest49초 성공(기존 macOS9skip), 시간검사4개·조합8개 통과.
  독립 재검토에서 이전 finding 해소·신규 차단0이다. 전체 승인/recorder는 아니다.

## 실제 이동의 장소·입구 연속성 연결

- unrelated-entrance 출발이 성공하는 회귀 RED 후 GenerationPlaceContinuity를 실제 후보
  projection에 연결했다. 도보와 버스 접근/하차 endpoint가 이전/다음 canonical 장소의
  승인 입구이며 해당 이동이 연결 fact를 참조하는지 검사한다.
- 대표좌표는 exact place-point:{canonical ID}, 승인 tourapi.place source fact, 해당 이동의
  장소 fact 참조와 PROVISIONAL_PLACE_POINT 표시를 모두 요구한다. policy fact를 공식
  대표좌표 근거로 승격하지 않는다. 중간 비이동 장소와 마지막 도착 경계도 확인한다.
- 기존 AI 합성 원본은 변경하지 않았다. SDK 조합 테스트에서만 합성 입구6개/source metadata와
  walk 참조를 명시적으로 추가했고 fixture provenance에 운영 근거가 아님을 기록했다.
- 최초 조합/입구/architecture18초 성공, 독립 부분 리뷰 신규 차단0이다.
  택시 endpoint·거리/운임 상세·JDBC 후보 writer·조회/apply/previous_days/FE와 최종 PR은 후속이다.
- 최종 spotlessApply/test/architectureTest53초 성공(조합11개, 기존 macOS9skip)이다.

## 2026-09-14: 후보 저장의 0분 경계점 봉인 계약 (진행 중)

- 실제 DB trip_legs가 양쪽 trip_items를 필수 참조하며, 기존 core seal은 모든 item/leg의
  시간을 양수로 요구함을 확인했다. AI 시작/종료 경계에 임의1분을 넣지 않기 위해 명시적인
  nullable boundary_role(day_start/day_end)와 체류0분 계약의 DB 회귀를 먼저 추가했다.
- 최초 격리 PostgreSQL 테스트는 boundary_role 컬럼 부재로 RED(1분42초)였다.
  Supabase CLI는 PATH에 없어 npm의 공식2.117.0을 확인해 npx help/new로 생성한 뒤 저장소의
  append-only canonical 순서027/065로 이동했다. 기존 SQL 파일은 수정하지 않았다.
- 신규 migration은 기존 facts={}를 유지하고 경계의 canonical 장소/0분exact/custom을 검사한다.
  채워진 Day별 시작·중간항목·종료 및 파생 버전의 부모 경계 보존을 검사한다.
  기존 legacy 일정에 경계 쌍을 새로 강제하지 않는다.
- manifest/세 Compose/Java·Python inventory/아키텍처/smoke 슬롯을 동기화했다.
  등록 누락 RED 후 canonical migration 순서 검사14개 성공이다.
- 두번째 DB 실행은 테스트 자료의 필수 source 누락으로 실패했다. ai_generated를 명시했고
  같은 회귀를 다시 실행 중이다. 마이그레이션 전체 승인 또는 완료로 표시하지 않는다.
- 독립 설계 검토는 Day별 검사·부모 보존·서버 편집제한·clone marker 보존·같은 장소의0분 이동
  지원을 요구했다. 조회/clone/mutation과0분 동일장소 leg, 부정/권한/동시성/upgrade 검증이
  아직 남아 있다. 후보 writer/조회/apply/previous_days/FE 및 최종 PR도 계속 미완료다.
- source 수정 후 같은 PostgreSQL 봉인 회귀1개 성공(1분22초). 현재 마이그레이션은 작업 중이며
  새 경계가 있는 일정의 일반 조회·편집까지 검증하기 전에는 커밋/배포하지 않는다.
# 2026-09-14: 0분 기준점 일정 조회 연결 (진행 중)

## 기존 편집 회귀와 택시 검증 후속

- 전체 unitTest/architectureTest GREEN(27초, 기존 환경별9개 skip) 후 첫 storage fixture 점수를80.25로 변경했다. `intValueExact`의 `Rounding necessary`로 정상/중간실패2개 모두 RED(1분10초)를 확인했다.
- Supabase 공식 Tables/Data 문서를 확인하고 CLI `migration new --help` 뒤 실제 CLI로 빈 migration을 생성했다. 저장소 canonical 순서에 맞춰028(`generation_result_projection`,init066)으로 배치하고 후보 score를 scale 제한 없는 numeric으로 변경했다. 기존0~100 CHECK/NULL 의미는 유지하며 Java는 BigDecimal을 그대로 전달한다. 숫자·계약을 임의 반올림하지 않는다.
- 028 SHA256=a9a6b10fed8703cbead4b845d51eff01751b247aff19fda6a40e3c2874442700. manifest, Compose3개, Docker 순서, Java/Python canonical inventory를 갱신했다. Python14개 GREEN. 후속 architecture/DB8개 실행 중이며 migration commit/advisors/전체 upgrade 검증은 아직 하지 않았다.
- 소수 점수 보존 후 architecture/DB8개 GREEN(1분21초): DB에서 80.25를 세 후보 모두 그대로 읽었고 중간실패 전체rollback 및 기존 lease 검사를 유지했다. 명시적인 buffer-only leg의 duration0/buffer0 assertion도 이번 실행에 포함됐다. 전체 근거 ledger, 초 단위 bus 및 다일/실행/조회/적용 연결은 미완료다.
- CLI advisors help로 `--db-url`/`--local` 지원을 확인했다. 운영/linked project에는 연결하지 않았고 이번 변경은 기존 후보 score 형식만 바꾸며 공개 권한을 늘리지 않는다. 전체 근거 ledger 저장은 계속 후속이다.

- 완료 후 lease 검사 수정의 architecture/DB6개 GREEN(1분19초). 최초 성공3 저장 테스트는 insuff 전용 adapter가 `MCP_CONTRACT_INVALID`를 반환해 RED(1분8초)였다. 성공 경로를 `JdbcGenerationCandidateWriter`로 연결하여 같은 transaction에서 draft→항목/구간→candidate3→run success를 작성한다. null base는 빈 활성 버전 없이 시작하고 base가 있으면 대상 Day 외 항목/구간을 복사하는 SQL을 추가했다. 두 번째 후보 INSERT의 인위적 실패 시 첫 후보 포함 전체 rollback을 검증하는 parameterized 회귀도 추가했다.
- 첫 writer DB 실행은 custom 경계 항목의 필수 title 누락으로 RED(7개 중1개,1분16초). 사용자/AI 자유문 대신 경계는 고정 제목, 일반 장소는 서버 canonical 이름을 사용하도록 수정했다. 후속 architecture/DB8개 실행 중이며 성공3 writer의 GREEN은 아직 확정하지 않는다.
- 제목 보완 후 architecture/DB8개 GREEN(1분19초): first base=null의 채워진 candidate 버전3개·24h·조회, 두 번째 후보 INSERT 실패 시 첫 후보/전체 버전/항목 rollback, insuff/lease 경계를 확인했다. 이는 storage fixture 검증으로 MCP 후보 다양성은 별도 실제 MCP 회귀에서 검사한다.
- 독립 검토에서 buffer-only 동일장소 연결의 버퍼가 유실됨을 확인했다. first storage fixture에 15분 buffer-only 연결을 추가해 DB 보존3건 대신0건 RED(2개 중1개,1분10초)를 재현했다. `trip_legs.facts.generation`의 schemaVersion1 닫힌 구조에 connection events/selected transfers/segment risks를 기록하도록 변경했다. 같은 장소0분 leg의 duration/buffer0은 유지하며 실제 계획 버퍼는 별도 events로 보존한다. raw JsonNode/좌표/geometry/원문은 이 record에 없다. 후속 architecture/DB8개 실행 중이다.
- buffer-only 보존 후 architecture/DB8개 GREEN(1분18초). 테스트 실행 중 추가한 명시적 duration_minutes=0/buffer_minutes=0 SQL assertion은 다음 회귀 실행에서 재확인해야 한다(기존 DB 봉인 제약은 이미 검사됐다). 전체 ledger/다일/실행 연결 완료와는 구분한다.
- 남은 저장 계약: 소수 score, 초 단위 버스 시간, 전체 정규화 evidence ledger/요금범위/risk/버퍼 이력 보존과 다일 copy DB 검증. 현재 score 소수·버스 승차 구간의 분 미만 시간은 임의 반올림하지 않고 거부한다. 이 제한은 최종 요구 충족이 아니며 production bean 미등록을 유지하고 후속 구현한다. 원본·geometry 저장은 추가하지 않았다.

- 실제 완료 저장 TDD를 시작했다. 첫 테스트는 intake 메서드 이름 오타와 누락 adapter로 컴파일 실패했고 오타 수정 후 `JdbcGenerationCompletionStore` 부재만으로 RED(6초)를 확인했다. REQUIRES_NEW transaction에서 Trip owner/revision/nullable active base를 잠그고 run identity/day/base/fence/attempt/lease/DB deadline을 확인하여 insuff0 terminal을 저장했다. 중복 완료 거부, 후보/버전 0개, 활성 pointer/revision 무변경, 정확히 7일 보존 DB GREEN(1분14초).
- 현재 adapter는 성공 후보 writer 미연결을 명시적으로 거부하며 Spring bean으로 등록하지 않았다. 이 부분 성공을 전체 pipeline 완료로 보고하지 않는다. 만료 deadline/fence/revision/lease 및 UPDATE 후 lease 만료 rollback 회귀를 추가 실행 중이다. 마지막 경우는 사후 lease 재검사 누락 RED를 예상한다.
- 후속 DB RED 확인: 6개 중 UPDATE 이후 lease 만료 시나리오만 false 대신 true를 반환했다(1분15초). 테스트가 실제 UPDATE 후 DB pg_sleep(4)로 3초 lease를 넘긴다. Trip 다음 run을 잠그고 기존 lease 만료시각을 보존한 뒤, terminal UPDATE 후 DB clock으로 deadline과 lease를 모두 재확인하여 만료 시 transaction rollback하도록 수정했다. 후속 architecture/DB 6개 검사 실행 중이다.
- 독립 부분 검토도 현재 완료 저장에서 위 사후 lease 검사 누락 외 신규 차단 finding을 찾지 못했다. 이전 taxi 상세 NPE 지적은 선행 GenerationTransferTiming 검사를 최신 코드에서 확인한 뒤 철회됐다. 정식 승인/recorder는 아니다.

- 하루 일정 조립 후속: `GenerationScheduleDay` 부재 컴파일 RED(7초) 후 시작/종료 0분 기준점, 실제 활동 항목, 인접 항목 사이 transfer/buffer 이벤트를 손실 없이 분할했다. 기준점 시각은 후보의 실제 첫 시작/마지막 종료를 사용하며 서버 활동창 안인지 검사한다. 버퍼를 방문 체류로 합치거나 시간 수치를 추가하지 않는다.
- 별도 utility로 남기지 않고 `Candidate.scheduleDay` 연결 부재 RED(6초)를 확인한 뒤 실제 candidate factory에 포함했다. 이동 없이 다른 canonical 장소로 바뀌거나 인접 활동 사이 이동이 둘 이상이면 전체 생성 불가로 처리한다. 관련 projection/architecture GREEN(18초), 연결 후 전체 unitTest/architectureTest GREEN(29초, 기존 환경별 9개 skip).
- 실제 후보 DB writer와 worker bean 실행, 조회/apply/FE는 계속 미완료다. 이번 GREEN은 후보 조립 경로의 검증이며 DB 트랜잭션이나 배포 준비 완료를 증명하지 않는다.

- 선택 이동 저장 projection 후속: `Candidate.transfers` 누락으로 컴파일 RED(7초)를 확인한 뒤 `GenerationTransfer`를 연결했다. 선택된 도보의 계획 시간/거리, 버스 승하차 canonical ID/예정 시각/승차 버퍼, 택시 요금 범위와 근거 ID만 복사한다. 원본 JSON 노드, 좌표, geometry, 정류장 이름 및 미선택 대안은 보존하지 않는다. 버스 구간 비용이 없으면 null을 유지하며 일별 합계로 배분하지 않는다.
- 후보 projection·worker·MCP 실행 관련 GREEN(15초). 선택 요금의 ModeDecision 근거 누락 회귀는 잘못된 fixture fact ID로 계약 오류가 먼저 발생했고, 기존 known fact로 수정 후 실제 ID 누락 RED(14개 중 1개, 11초)를 확인했다. decision 근거 합집합을 추가하고 전체 unitTest/architectureTest GREEN(31초, 기존 환경별 9개 skip).
- 독립 부분 검토에서 AI TimelineEvent의 빈 evidence 배열을 허용해야 한다는 지적을 확인했다. 상세 도보 근거가 있는 정상 후보를 거부하는 RED(15개 중 1개, 11초) 후 상위 이벤트만 빈 배열을 허용했다. 상세 Walk/Ride/Taxi/ModeDecision의 필수 근거와 known fact 폐쇄성은 유지한다. 후속 targeted/architecture GREEN(16초), git diff --check 통과이며 정식 리뷰 승인이나 후보 DB 저장 완료는 아니다.

- 전체 unitTest는 통과했으나 architectureTest의 migration 목록 크기만 62로 남아 RED였다(실제 63개, 48개 중 1개 실패). 027까지의 명시적 목록은 유지하고 총 개수를 63으로 고쳤다.
- architectureTest 및 기존 JdbcScheduleMutationStoreIntegrationTest 87개 GREEN(1분 37초). 앞선 실패 실행에서는 DB 편집 테스트가 실행되지 않았으므로 이 후속 결과로 구분한다.
- GenerationTransferTiming의 taxi 분기가 비어 있어, 주행시간 불일치가 통과하는 RED를 확인했다(5개 중 1개 실패, 9초). taxi_alternative의 양수 시간/거리, 타임라인 시간·전체 거리 일치, 예상요금 범위 및 is_estimated=true를 검증하도록 연결했다.
- 택시 부분 검증과 후보 projection 회귀 GREEN(12초). 잘못된 택시 구간 하나가 있으면 candidate list를 비우고 insufficient_feasible_routes를 반환한다. 이는 택시 fact endpoint/provenance 전체 검증이나 실제 후보 writer 구현 완료를 의미하지 않는다.
- 실제 AI runtime_routing.py의 Transfer 생성이 taxi_alternative와 같은 route.distance_meters를 노출함을 확인했다. MCP Schema는 수정하지 않았다.
- 진행도 재확인: 후속 전체 unitTest/architectureTest GREEN(26초). macOS에서 실행 대상이 아닌 기존 Linux 파일 핸들 관련 테스트 등 9개는 SKIPPED이며 실행 성공으로 계산하지 않는다. 실제 GenerationCompletionStore는 아직 인터페이스만 있으므로 후보 영속 저장·워커 실행 연결·결과 조회·원자적 적용 및 FE 실연동 완료로 보고하지 않는다.

## 2026-09-14 선택 이력 복원 검증 후속

- 도보 입력 연결 RED: TripService/TripPreferencePolicy 2개 거부(11초), 실제 DB check constraint 거부(1분 11초), FE 저장·복원 2개 실패(0.658초). 여행 enum과 미배포028의 check constraint에 walk를 추가하고 기존 1~3개·연속 우선순위·단일 primary 규칙을 보존했다. MCP Pydantic은 이미 walk를 지원하므로 수정하지 않았다.
- 도보 GREEN: 관련 BE 단위+실제 DB 저장→generation snapshot+OpenAPI 생성(1분 24초), 전체 unit/slice/architecture(53초, Linux 전용 9개 skip). 전체 scripts 945개 중 942개 통과·3개 skip(56.675초). FE 저장/복원 32개 통과(0.617초); pinned OpenAPI Trip enum이 구버전이라 FE typecheck는 아직 RED이며 실제 BE 커밋 기반 인계 재생성이 필요하다.
- 이번 수정 파일의 기존 Python 테스트 68개에도 개별 한글 목적 docstring을 보충했다. 관련 계약 96개 GREEN(8.197초).
- Supabase CLI 2.117.0 advisors를 격리된 PostGIS 16 DB에 실행했다. 027/028 전후 모두 spatial_ref_sys RLS ERROR 1개 및 public 확장 WARN 4개(postgis/pgcrypto/btree_gist/fuzzystrmatch)가 동일하다. fuzzystrmatch는 Docker 이미지 기본 DB 초기화가 설치하므로 기준 DB에도 같은 확장을 맞춰 비교했다. 신규 앱 객체 finding은 없으나 전체 advisor 무경고를 주장하지 않는다. 운영 DB/확장 ACL은 수정하지 않았다.
- 실제 FE 인계 검사에서 runtime manifest가 39개로 생성·조건 경로 4개를 누락한 RED를 확인했다. 활성 manifest를 43개로 닫고 생성 세 경로와 planner-conditions를 authority 검사에 연결했다. GET/apply의 미구현 429는 명시적 omission, 접수의 공통 429는 runtime-only로 구분하며 quota 구현 완료로 표시하지 않는다.

- 전체 Spring slice RED: 80개 중 planner-conditions ready canonical 투영 1개 실패(47초). ready fixture에서는 응답을 의도적으로 inline canonical schema로 투영하므로 `$ref` 문자열이 아니라 object/추가 필드 금지/정확한 필수 필드를 검증하도록 회귀를 정렬했다. 실제 미승격 런타임 OpenAPI와 별개의 테스트 조건이며 readiness 자체는 변경하지 않았다.
- GREEN: spotlessApply + 전체 unitTest/sliceTest/architectureTest(52초). Linux 전용 단위 테스트 9개는 macOS에서 skip. #89 계약 검사와 전체 파일 비밀정보 검사도 통과했다. 전체 DB 통합·커버리지·Docker·동일 SHA 품질 기록·정식 리뷰·최종 PR은 아직 후속이다.

- 전체 scripts 자동화 RED: 945개 중 6개 실패, 3개 skip(51.915초). 원인은 canonical endpoint/마이그레이션 고정 목록과 활성 OpenAPI 모드의 갱신 누락이었다. Windows gate도 38에 남아 있어 43 기대 회귀 RED 후 실행 모드를 정렬했다. 역사 모드 검증과 정확한 목록 검사는 유지했다.
- GREEN: 관련 33개(0.205초), 전체 scripts 945개 실행 중 942개 통과·3개 skip(55.252초). Windows 실행 자체를 macOS에서 검증했다는 뜻은 아니며 양 플랫폼 명령의 계약 정합성 검사다.

- #88 후보 버전 조회의 canonical 누락을 계약 테스트 RED로 확인했다. 읽기 경로를 7번째 endpoint로 등록하고 두 GET의 후보 만료/metadata 부재 410, UUID 경로 schema, REST 카탈로그와 fixture를 정렬했다. 기존 다섯 mutation의 동시성 조건은 유지했다.
- GREEN: 관련 Python 계약·OpenAPI·클라이언트 회귀 60개(3.653초), Spring Schedule/Frontend OpenAPI slice 및 문서 생성(26초), 실제 OpenAPI readiness와 TypeScript 생성 client 각 43 operations. git diff --check 통과.
- 독립 reviewer의 이번 계약 확장 부분 검토에서 신규 차단 finding 없음. 정식 승인·recorder가 아니며 전체 품질/Docker/최종 PR은 아직 완료하지 않았다.

- 조회 경로/TTL 최종 산출물: `generate_frontend_api_client.sh`에서 실제 OpenAPI43 readiness PASS, TypeScript client43 검증 PASS, build/distributions tgz 생성 PASS. FE로 자동 복사하지 않았다. #88 canonical은 아직6 endpoint/기존 read410 부재 상태이므로 다음 계약 정렬에서 별도7번째 read path와 두 만료 code/fixture, catalog를 맞춰야 한다. 현재 runtime/OpenAPI43 성공을 canonical 전체 정렬 완료로 과장하지 않는다.

- 동시 적용 검증을 실제 두 DB 트랜잭션으로 추가했다. 서로 다른 후보에 같은 revision을 전달하면 성공1/`TRIP_VERSION_CONFLICT`1, active1/selected1/revision2를 확인했다. GenerationIntakeIntegrationTest GREEN1분42초.
- 실제 후보 scheduleUrl/적용 Location이 `/schedule-versions/{versionId}`를 가리키지만 Controller가 없어 404인 문제를 MVC RED14초로 재현했다. 기존 ScheduleQueryService·소유권 검증을 재사용하는 명시 버전 경로를 추가했고 unknown query/body와 미인증을 거부한다. unit/architecture/ScheduleControllerIntegrationTest GREEN37초. inventory는 historical42를 보존하고 실제43으로 확장했다.
- 미적용 후보 만료 조회 누락은 실제DB RED1분25초로 재현했다. 조회 전후 응답시각/DB clock을 검사하고 만료410, metadata부재410을 반환한다. 독립 리뷰의 rejected 상태 TTL우회 finding은 5상태 단위에서 candidate외4상태 RED11초로 확인했다. `applied_at`이 있는 active/superseded AI버전만 TTL예외로 제한한 뒤 finding 해소 답변을 받았다(정식 승인 아님).
- 기존 metadata없는 경계봉인 fixture는 미적용 공개조회410을 검증하고, fixture 적용시각 설정 후 경계 projection을 검증하도록 정렬했다. 전체unit/architecture + GenerationIntake/ScheduleController DB·MVC + Schedule/FrontendOpenAPI slice + openApiDocs GREEN2분25초. 기존 Linux전용9개는 SKIPPED. 관련 Python123개 GREEN11.363초. runtime manifest의 원본 schedule GET410과 신규43 인계 문서를 반영했다. #88 canonical read 확장 정렬은 아직 별도 후속이다.
- FE `fix/10-generation-contract-alignment`에서 23h50 client clock 상한이 BE24h 후보를 거부하는 RED를 확인하고 상한만 제거했다. 44suite313tests GREEN4.39초, typecheck/lint/UI58 StyleSheet 일치. UI 소스·동선은 변경하지 않았다. FE에 미커밋 API검증/테스트/일지 변경이 있으며 최종 PR·원격 CI는 아직 미완료다.

- 최종 상태별 예제에서 Swagger의 `setExample(null)`이 literal null 예제를 내보내는 오류를 실제 validator RED로 발견했다. example 없는 새 MediaType에 명명 예제만 넣어 수정했고 `openApiDocs` 14초 GREEN, 실제 생성 명세의 frontend readiness42 GREEN이다. 고정 codegen으로 TypeScript client42 검증과 tgz 생성도 GREEN이며 FE 저장소에는 자동 복사하거나 UI를 수정하지 않았다. `git diff --check` GREEN.

- HTTP 후보 적용 endpoint를 실제 멱등성 경계와 연결했다. nullable 최초 적용·ETag/Location·동일 응답 재생·소유권 선검사·후보 오류 MVC 테스트와 unit/architecture/runtime OpenAPI 검증은 43초 GREEN이다.
- 실제 PostgreSQL `GenerationIntakeIntegrationTest`는 1분 34초 GREEN이다. 새 Controller 인스턴스에서 실제 DB 영수증을 재생하고, 이미 선택·만료된 후보도 같은 키는 최초 body/ETag/Location을 반환하며 다른 body는 `IDEMPOTENCY_KEY_REUSED`로 거부한다. 이는 JVM 전체 재시작 검증을 대신하지 않는다.
- frontend readiness mode42를 추가했다. 기존 38개 inventory를 보존하고 planner conditions·생성 POST·조회 GET·적용 POST만 추가한다. 최초 테스트는 42 대신 9개 및 필수 헤더 누락으로 RED, 관련 Python 36개는 GREEN(2.1초)이다. 품질 게이트/client 생성 명령은 mode42로 전환했다.
- 생성 오류 정의를 HTTP handler와 문서가 공유하도록 분리하고, 상태별 생략 필드·후보 소수 점수·nullable 이전 버전 예제를 보강했다. unit/architecture/runtime OpenAPI 및 문서 생성은 42초 GREEN이다. macOS에서 기존 Linux 전용 단위 테스트 9개는 SKIPPED이며 Linux 전체 검증 완료로 주장하지 않는다.
- 독립 reviewer의 이번 부분 검토에는 필수 finding이 없었다. 테스트·정식 승인·recorder는 실행하지 않았다는 답변이며 최종 PR 승인으로 사용하지 않는다. 전체 품질/Docker/FE 실제 연결 검증과 최종 PR은 아직 남아 있다.

- 적용 만료 fence 최종 GREEN: `spotlessApply unitTest architectureTest integrationTest --tests '*GenerationIntakeIntegrationTest'` 1분54초 통과(기존 플랫폼 제외 9건 SKIPPED). 마지막 selected_at 쓰기까지 실제 실행됐음을 wrote=true로 확인하고, 만료 후 Trip active/revision·candidate version 상태·selected_at이 모두 이전 값으로 rollback됐음을 검증했다. 복구된 후보를 실제로 적용한 다음 Day2 생성 이력 회귀도 통과했다. FE applyCandidate는 nullable expectedActiveScheduleVersionId 및 If-Match/Idempotency-Key를 이미 전송하는 것을 재확인했다. HTTP 적용 endpoint/멱등성 replay·동시 적용 경쟁은 후속이다.

- 적용 만료 fence RED 재현: 5초 만료 후보에 selected_at UPDATE 뒤 6초 지연을 주입하자 예외 없이 commit하는 실패를 실제 DB에서 확인했다(1분24초). coordinator의 모든 쓰기 뒤 같은 TX에서 clock_timestamp()<expiresAt을 재확인하도록 수정했고, 만료면 Trip 포인터/revision·version 상태·selected_at 전체 rollback 회귀를 실행 중이다. 독립 부분 재검토에서 finding 해소, 정식 승인 아님.
- 최초 적용 응답 계약의 previousScheduleVersionId는 required이면서 nullable=true로 정렬했다. 신규 한글 목적 Python 회귀 RED(false)→계약51개 GREEN6.187초. 최신 canonical digest 494538f3784a88b799185bc08e370dff0c3fa9eb2cde7ab83934d8fd0bcf86d0.

- 원자 적용 저장소 연결: GenerationApplyStore/JdbcGenerationApplyStore를 추가해 Trip→run→candidate/version 잠금과 기존 TripAggregateMutationCoordinator의 revision 증가를 재사용한다. 최초 nullable base/current 검증, owner 은닉, 중복 선택·만료·근거 유무·snapshot revision/base/lineage 검사, 이전 active superseded/후보 active/Trip 포인터/selected_at을 한 TX로 연결했다. Day1 수동 SQL fixture를 실제 apply로 대체한 Day2 생성 이력 회귀까지 GREEN1분48초(unit/architecture/관련DB). 최초 RED는 저장소 port 누락 compile6초다.
- 부분 리뷰의 만료 최종 fence finding은 수정 진행 중: selected_at UPDATE 뒤 지연을 넣으면 만료 후 commit될 수 있어 모든 쓰기 뒤 DB 시각 재검사와 rollback 회귀를 추가하고 RED 확인 중이다. HTTP·멱등성 wrapper·동시 적용 검증은 아직 후속이며 적용 기능 전체 완료가 아니다.

- GET 문서 최종 후속: 새 schema 이름/상태 enum 누락 RED15초를 확인하고 DTO에 canonical schema 이름·필수 필드·enum·범위를 반영했다. 전체unit/architecture/OpenAPI slice GREEN36초, 추가 nullable 최초 base 검사와 `openApiDocs` 생성 GREEN21초. 생성 산출물과 runtime HTTP 문서를 확인했다. score finding은 독립 재검토에서 해소됐다. 아직 전체 frontend-readiness endpoint 모드와 원자 적용 연결/최종 PR 게이트는 남아 있다.

- 공개 GET 연결: GenerationQueryController→GenerationQueryService→owner 단일SELECT를 연결했다. NoQuery/BodyForbidden/canonical UUID, queued/running Retry-After2, no-store, 상태별 결과/실패 생략, nullable 최초 base, decimal score·KST시각·concrete URLs를 DTO로 제공한다. DTO compile RED6초→GREEN15초, HTTP compile RED6초→MVC5개GREEN. architecture 새controller목록 및 mapping59→61 차이를 명시적으로 갱신했고 전체unit/architecture/기존OpenAPI slice GREEN36초. 신규 GET OpenAPI schema 상세회귀는 추가 실행 중이다.
- 독립 부분 리뷰가 발견한 score integer 계약 불일치를 number로 수정했다. 신규 목적 docstring Python 회귀 RED→계약50개GREEN6.135초. semantic digest 3d5f125dd6121ea78a757d500ace69a32e066baad4a419fd1e9830a8f8e0d549. 최종 endpoint inventory/readiness 및 원자 적용은 후속이며 최종 PR 승인 아님.

- 기준 시각 durable 연결: 미배포·미커밋 028 결과 projection에 facts_as_of timestamptz를 추가했다. legacy null은 현재 시각으로 backfill하지 않는다. 성공 전이에서 후보와 함께 fenced UPDATE로 저장하며 rollback 시 null을 유지한다. reader/service는 실제 시각을 복원하고 성공 null 또는 completedAt 이후 시각을 노출하지 않는다. manifest SHA-256을 3b49259ad9ec216b37dfca64e8c0018a3f87cad2304db4b878308bf90c41d549로 갱신했다.
- TDD DB 시각: missing column RED(1분 9초) → `spotlessApply unitTest architectureTest integrationTest --tests '*GenerationIntakeIntegrationTest'` GREEN(1분 47초). migration 순서 Python 14개 GREEN. 독립 부분 리뷰 신규 차단 0건, 정식 승인 아님. Supabase 공식 changelog/tables 문서를 확인했고 운영 DB 변경 없이 canonical 격리 DB로 검증했다. advisors와 최종 품질 게이트는 아직 남았다.

- 결과 기준 시각: GenerationCandidateProjection에 필수 factsAsOf를 추가하고 MCP planning_context.planned_at에서만 복사한다. +09 offset 및 planned_at<=generated_at을 검증하며 서버 현재 시각으로 대체하지 않는다. 후보 집합 부족·타임라인 거부·이전 방문 중복으로 insufficient 전환해도 원래 시각을 보존한다. 계약 문서에 계획 평가 시각이며 개별 fact 최신성 보증이 아님을 명시했다.
- TDD 시각 회귀: factsAsOf 미구현 compile RED(6초) → synthetic insufficient fixture의 failure 객체 누락으로 RED(14초) → fixture 계약 수정 후 전체 unit/architecture GREEN(29초), 기존 플랫폼 제외 9건 SKIPPED. DB factsAsOf 저장 및 공개 응답 연결은 아직 후속이며 완성으로 보고하지 않는다.

- 최종 후속 확인: `spotlessApply unitTest architectureTest` GREEN(28초). 기존 macOS 플랫폼 제외 9건은 SKIPPED이며 통과로 집계하지 않았다. 후보 조회 변경의 실제 DB GREEN과 함께 확인했으나 HTTP GET, factsAsOf 저장·응답, nullable 원자 적용 및 최종 PR 품질 게이트는 잔여 작업이다.

- 후보 JDBC 조회 후속 GREEN: `spotlessApply integrationTest --tests '*GenerationIntakeIntegrationTest'` 1분 27초 통과. 실제 최초 생성 성공 run에서 전략 순서·소수 점수(80.25)·정확한 24시간 expiry·타인 404를 검증했다. 25시간 후에도 후보 메타데이터 조회가 7일 보존 내에서 유지되는 단위 경계를 추가하고 전체 unit/architecture 재검증을 시작했다.

- 후보 조회 연결: 소유권 범위 run과 candidate를 단일 SELECT snapshot으로 읽고, 성공은 서로 다른 ID·버전·rank·세 전략 및 점수·설명·24시간 TTL을 재검증한다. 불완전한 성공/insufficient 후보 혼입은 전체 조회 오류이며 부분 반환하지 않는다. 후보 적용 만료와 7일 작업 조회 보존은 분리한다.
- TDD: `GenerationQueryServiceTest` SavedCandidate 미구현 compile RED(6초) → 조회 record/JDBC/service 연결 후 단위·아키텍처 GREEN(16초). 독립 부분 리뷰 신규 차단 0건이며 정식 승인 기록은 아니다. 실제 DB 회귀 진행 중; 공개 HTTP DTO/GET 및 원자적 적용은 아직 완료하지 않았다.
- 앞선 실패 안내 연결은 기존 실행 handle 종료 뒤 XML을 재확인하여 GenerationIntakeIntegrationTest 20개, GenerationFailureTest 3개, GenerationQueryServiceTest 3개 모두 failures/errors=0을 확인했다.

- #95 실패 projection: GenerationFailure 미구현 compile RED6초→단위GREEN10초. known error_code만 고정 한국어 detail/retryable로 변환하고 unknown 내용은GENERATION_EXECUTION_FAILED로숨긴다. cancelled는취소코드, queued/running/succeeded는과거failure없음. reader.failure 미구현RED6초 후 실제SELECT에error_code만추가하고error_message는조회하지않는다. DB타임아웃복원+7일보존/전체unit/architecture회귀실행중. 공개GET/성공후보result는아직후속이다.

- 후보 explanation 저장 후 전체 unit/architecture/GenerationIntakeIntegrationTest GREEN(1분49초, 기존macOS제외9skip). 후보3개 설명저장, 후보2실패전체rollback, Day이력복사/조회까지회귀통과. 결과기준시각/failure projection/공개GET/apply/FE실연동/최종PR은미완료다.

- 후보 설명 실제DB RED 확인: 설명이[null,null,null]로 저장되어20개 중1개 실패(1분30초). writer의 동일 후보 INSERT에 검증된 Candidate.explanation()을 바인딩했다. 중간 후보 실패 rollback 경계는 변경하지 않는다. 독립 부분 설명 생성 리뷰 신규차단0(정식승인아님), 전체unit/architecture/접수DB 회귀재실행중.

- 후보 explanation 보완 착수: AI DurableCandidate에는 자유문 설명이 없으므로 AI recommendation_reasons.text를 영속 복사하지 않는다. 검증된 strategy 및 Totals 방문/이동/식사/휴식/버퍼 수치의 결정론적 표시 문자열을 Candidate.explanation으로 구성했다. 메서드 미구현 compile RED(6초), fixture rank1=experience_max임을 확인해 rank와strategy를 혼동한 테스트를 수정했다. 세 전략 표시 및 AI자유문 비복사 단위는 GREEN이며 실제 DB explanation 저장 누락 RED 확인 중이다.

- 추가 공유계약 회귀: validate_rest_contracts.py GREEN, test_rest_contract_readiness 53개 GREEN(2.6초), git diff --check GREEN. 이번 계약 정합화의 합산120개 테스트는 통과했으나 전체 Spring/배포 품질 게이트나 최종 PR 증거는 아니다.

- DB020과 #89 wirehash 조회 불일치 정합화: 공개 GenerationRunStatus/RevisionRunStatus에서 mcpInputHash 제거, 모든 상태 omitted, 내부 호출 기록 분류를 provenanceCases로 명시했다. 동일 응답 모양을 oneOf로 판별하지 않는다. MCP 메모리 내 hash교환과 commandInputHash는 유지하고 alias/영속저장 복원은 하지 않았다. 신규 회귀 First RED(hash property 노출)→계약49개 GREEN→위치무수집 포함67개 GREEN(5.5초). canonical digest는334311ffea283be421268a564e824d6c8c956fbe959a1841757a1f8ac2413548이다.
- 독립 부분 리뷰 신규차단0(정식승인아님). FE services/api/generations.ts가 mcpInputHash를 요구하지 않는 것을 직접 확인해 FE/UI 변경은 하지 않았다. 별개로 기존 FE 후보 expiry 검증에85,800,000ms 상한이 남아 있어24h 계약 연결 때 수정해야 한다. 공개GET/후보 metadata/apply/FE 전체연결/최종PR은 미완료다.

- 조회 service 단위3개·architecture·실제 PostgreSQL 소유권/반복 무변경 조회1개 GREEN(1분20초). 독립 부분 service 리뷰 신규차단0(정식 승인 아님). 공개GET/성공후보 요약/실패code projection 및 wirehash 계약 정합화는 미완료이며 이 결과를 전체 #95 완료로 계산하지 않는다.

- #95 조회 착수 중 계약 불일치 발견: DB020은 wire mcp_input_hash의 opaque durable 저장을 제거했으나 #89 계약은 postDispatch 조회 필수로 남아 있다. hash를 재생성/command hash로 대체하거나 DB 저장을 되살리지 않는다. 공개 DTO 연결 전에 최신 비저장 규칙으로 계약·validator·FE 타입을 함께 정합화해야 한다(아직 미완료).
- SELECT-only GenerationRunReader First RED(미구현,6초) 후 owner/trip/run/command v2/immutable trip snapshot lineage를 한 SELECT에서 확인하는 저장소를 추가했다. 첫 실제 DB 실행에서 final @Repository의 CGLIB proxy 생성 실패(1분13초)를 확인해 final을 제거했다. 독립 부분 SELECT 리뷰 신규차단0(정식 승인 아님).
- GenerationQueryService First RED(미구현,6초) 후 owner 은닉→terminal 7일 기한/등호 만료 판정을 추가했다. queued/running은 후보 만료로 종료하지 않으며 terminal retention 누락/불일치는 결과불가로 처리한다. 관련 단위/architecture/실제DB 반복읽기 무변경 회귀 실행 중. 아직 공개 GET이나 후보 summary 구현 완료가 아니다.

- Runtime 조립 후 전체 unitTest/architectureTest/GenerationIntakeIntegrationTest GREEN(1분 42초, architecture는 동일 코드 UP-TO-DATE), diff 검사 GREEN. 기존 macOS 제외9건 skip. 실행/저장 빈 연결은 완료했지만 실제 생성 성공→조회→apply 전체 시나리오, 후보 메타데이터 보완, FE 연결 및 최종 품질/PR 완료를 뜻하지 않는다.

- Runtime 조립 독립 부분 리뷰: 신규 차단 0건, 실제 AI TOML의 SHA/승인18 ID 일치 확인(정식 승인 아님). 후속 #95를 다시 조회했고 설명의 legacy /generation-runs 경로와 최신 #89 catalog의 /schedule-generations/{runId} 차이를 확인했다. 구현은 이미 접수 pollUrl과 FE 계약에 사용되는 최신 경로를 유지한다.

- 실제 실행/저장 빈 조립을 GenerationRuntimeConfiguration에 추가했다. 설정 클래스 미구현 compile RED(6초) 후 McpGenerationExecutor+JdbcGenerationCompletionStore를 기능ON/workerON에서 연결했다. AI 6efe58의 승인 출처 18개를 원본 TOML SHA와 함께 고정했으며 호출 승인과 원본 저장 허가는 구분했다. OFF/접수전용 미등록, MCP client 누락 startupfail, 실제 worker→executor→snapshot 누락 실패코드 기록을 검증했다. 최초 테스트의 JdbcTemplate.afterPropertiesSet을 SQL 호출로 오인한 assertion을 수정했고 설정 단위+architecture GREEN(17초). 전체 unit/접수 DB 회귀는 실행 중이다.

- 워커 설정 후 생성 접수 DB 회귀 GREEN(1분 51초), 로그 경계 수정 후 전체 unitTest/architectureTest GREEN(28초), diff whitespace 검사 GREEN. 기존 macOS 제외 9건은 skip이다. 독립 재리뷰에서 로그 finding 해소·신규 차단 0건 확인(정식 승인 아님). 실제 adapter 빈 조립·결과 조회·apply·FE·최종 PR은 계속 미완료다.

- 워커 lifecycle 설정 First RED(설정 클래스 없음, 6초) 후 feature OFF 미등록·ON 주기 claim·180초 lease/10초 heartbeat·종료 후 새 claim 금지를 연결했다. 부분 테스트 GREEN(11초). 접수 전용 DB 테스트는 worker.enabled=false를 명시했다. 실제 MCP/Completion adapter 빈 조립은 별도 미완료이므로 전체 production 연결 완료가 아니다.
- 독립 부분 리뷰에서 scheduler 기본 로거로 claim 예외 상세가 흘러가는 finding을 받았다. 민감 문구를 넣은 합성 예외의 경계 이탈 RED(5개 중 1개, 14초) 후 polling에서 RuntimeException을 잡고 message/cause 없이 GENERATION_POLL_FAILED만 기록하도록 수정했다. 전체 회귀와 수정 후 GREEN 확인은 진행 중이다. 정식 승인 기록은 생성하지 않았다.

- 최신 전체 unitTest/architectureTest/GenerationIntakeIntegrationTest GREEN(1분 49초). previous_days 실제 SDK 전달, 사전 필수 재방문 거부(run 0건), 결과 재방문 insufficient0, 이력 복원/복사/변조/상한을 포함한다. 독립 부분 리뷰에서 이번 연결의 신규 차단 finding 0건이며 formal 승인 아님. 최종 PR 전 전체 quality-gate/Docker/coverage는 여전히 필요하다.

- 활동 이력 누락(실제 방문+휴식 중 방문만 저장) RED(1분 13초) 후 양방향 canonical 장소/역할 집합 검사를 추가했다. Day2 후보별 ledger가 [1,1,1]인 RED(1분 15초) 후 이전 Day 이력/evidence를 같은 candidate transaction에서 복사하도록 연결했다.
- 전체 생성 접수/architecture GREEN(1분 28초): 후보별 Day1+Day2 이력 보존, Day 직접 삭제 거부와 기존 ledger 보존을 확인했다. 028 해시는 eed4f2b5443b5ccd8aa17730b54fce5a4ca41383cac0228cfaf251b79b42896e이다. fixture 날짜 변경은 기존 보호 규칙을 우회하지 않고 최초 seed에서 2일 캘린더·항공 이벤트를 원자 생성하도록 수정했다.
- JdbcGenerationDayHistoryRepository 미구현 compile RED(6초) 후 baseScheduleVersionId에 고정된 봉인 이력 복원을 추가했다. 접수 시 prefix 누락을 거부하고, DB shape/계보 및 KST를 복원 후 재검증한다. 기존 026 snapshot이 해시한 불변 버전 참조를 사용하며 과거 원문이나 전체 타임라인을 복제하지 않는다.
- 전체 unitTest/architectureTest/GenerationIntakeIntegrationTest GREEN(1분 46초): 독립 mapper 이력 복원, 복사된 history/evidence 동일성, fact/edge 상한 사례도 포함한다. macOS 전용 제외 9개는 skip이다.
- McpGenerationExecutor 생성자 연결 compile RED(7초) 후 previous_days와 과거 fact/place ID allowlist를 실제 SDK 요청에 연결했다. Pydantic 생성 input/output Schema를 사용하는 SDK 회귀 GREEN(12초).
- 이전 방문 장소 재추천을 success로 수용하는 RED(11초) 후 후보 전체를 insufficient0으로 폐기하도록 했다. Pydantic 실제 required∩previous visit 입력 validator에 맞춰 필수 재방문 충돌은 외부 호출 전에 입력 오류로 거부한다. SDK 후보 projection 회귀 GREEN(12초). 접수 단계의 필수 재방문 충돌 시 run 0건과 이후 preferred 입력의 복사 DB 검증을 추가하여 전체 회귀 실행 중이다.
- 미완료: 수동 편집 후 이력 재구성, worker production 조립, 결과 조회·만료, 원자적 apply, FE 실연동, 최종 품질/Docker/정식 review/PR. 부분 GREEN으로 전체 연결 완료를 선언하지 않는다.

- INSERT lineage guard RED(봉인 후보에 대한 INSERT가 PK 충돌만 발생, 1분 13초) 후, Trip→version 잠금 순서·draft 상태·Day 날짜·기준점 시작/종료·canonical 장소와 역할 일치를 검사했다. 중첩 invalid 값은 행 전체를 오류 detail로 노출하기 전 generic 오류로 거부한다.
- 전체 GenerationIntakeIntegrationTest 및 architectureTest GREEN(1분 26초), canonical migration 14개 GREEN. 028 현재 해시 03609a4dd20d96e60012d66ce39499cb8b9d61c06e0eacbcca4e2592d3e9644b.
- 독립 리뷰의 Day 1 이력 복사 누락을 재현하기 위해 Day 1 활성 fixture→Day 2 실제 접수/snapshot/lease/completion→후보별 이력 2개 DB 시나리오를 추가하고 RED 실행 중이다. 이 fixture의 활성화는 적용 API의 검증 증거가 아니며 MCP 전략/반복 방문 검증도 별도다.

- Day ledger 중첩 검증 함수 미구현 RED(1분 10초) 후, JSON 연산자 우선순위와 PL/pgSQL 변수/alias 충돌을 실제 DB 실패로 확인하여 수정했다. nested 원본/geometry, NULL 역할, 미지 근거/출처, 순환 참조를 거부하고 UPDATE를 불변 guard로 막는다.
- 독립 부분 리뷰의 반복 전체 fact 탐색 성능 finding을 반영했다. 정수 index·indegree 배열·역방향 연결·대기열의 Kahn 검사로 변경하고 4096 facts/16384 edges/1MiB 운영 상한을 둔다. 공개 MCP Schema 변경은 아니다.
- 실제 DB GREEN(1분 13초): 후보 3개 저장, 중첩 변조 9개, 4096개 긴 체인 허용/4097개 거부, UPDATE 거부, 두 번째 후보 실패 시 이력까지 전체 롤백. canonical migration 검사 14개 GREEN. 추가한 봉인 후보 INSERT lineage 거부 테스트는 다음 RED 실행 중이다.
- 독립 부분 리뷰에서 수정된 순환 검사와 NULL/연산자 처리에 신규 차단 finding 0건. 정식 전체 승인 아님. 이전 Day 이력 복사 및 수동 편집 후 이력 재검증/무효화 정책은 아직 미연결이다.
- 사용자 요청: 최종 PR 단계에서 1시간 이상인 검증은 백그라운드로 넘기고 실행 위치·상태·후속 확인을 남긴 뒤 멈춘다. 결과 미확인 검증을 통과로 표시하지 않는다. 그 전 구현과 짧은 검증은 계속한다.

- 미커밋 028에 private generation_day_results를 추가하고 completion writer에 history/evidence 저장을 연결했다. 일정 버전/Day 복합 FK로 Run 7일 보존과 분리하며 RLS, service_role SELECT/INSERT만 허용한다. top-level JSON 필드는 닫혀 있으나 중첩 DB shape 검증과 불변 guard는 후속이다.
- 후보 3개 이력 저장 및 두 번째 후보 실패 롤백 통합 시나리오 GREEN(1분 20초), canonical migration 검사 14개 GREEN. 실행 중 추가한 ledger 잔존 0개 assertion은 다음 전체 생성 접수 통합 회귀에서 재검증한다. 이전 Day 이력 복사와 snapshot 복원 연결은 아직 미완료다.

- 선택 Day projection은 역할별 canonical 장소와 합계·근거 ID만 MCP previous_days 형태로 변환한다. 활동 상세 근거를 포함하며 Pydantic 생성 SelectedDayHistory Schema로 검증한다. 전체 타임라인과 원본 값은 이력 wire에 포함하지 않는다.
- 저장 계보를 직접 복원하는 생성자가 미지 부모·미지 출처·순환을 허용하는 회귀 RED를 확인했다(GenerationEvidenceTest 5개 중 1개 실패, 13초). 계보 폐쇄성과 순환 검사를 공통 생성자로 옮겨 최초 응답과 복원 경로에 동일하게 적용했다.
- spotlessApply 및 전체 unitTest/architectureTest GREEN(33초). 기존 macOS 실행 제외 9개는 SKIPPED이며 통과로 계산하지 않는다.
- Day ledger DB 회귀는 JDBC JSON 연산자 파라미터 혼동 수정 후 재실행했다. 2개 중 1개 RED(1분 17초), 실제 원인은 generation_day_results 테이블 미구현이다. 테이블·저장 연결·다음 Day snapshot 입력은 아직 완료하지 않았다.
- 최종 BE PR은 아직 없으며 전체 품질 게이트, Docker, 독립 정식 리뷰는 후속이다.

## 동일 장소 0분 이동 연결

- 기존 양수 이동/동일 장소 0분 이동을 parameterized DB 테스트로 분리했다. 0분 사례가 core sealing 함수의 양수 제한으로 RED(2개 중 1개 실패, 1분 40초)였다.
- 미커밋 027의 core validator에서 동일 non-null canonical 장소·walk·동일 출도착·출발=직전 항목 종료·모든 시간 구성/거리/요금/버퍼 0일 때만 0분 이동을 허용했다. 기존 권한은 유지하며 신규 공개 grant는 없다. migration manifest SHA256을 ef8fb0539056b7a965e733ae96d64a805e2b75a20cff80b92889ea20a135348d로 갱신했다.
- JdbcScheduleStore는 0분 이동의 동일 장소/시각/0원·0m 조건을 재검증한다. DTO와 REST durationMinutes 최소값은 0이며, 일반 경로를 임의로 0분 처리하지 않는다.
- 후속 DB RED는 `SCHEDULE_LEG_INCOMPLETE`(1분 13초): 일정 복사에서 기존 conservative fallback이 1분을 만들었다. live anchor 거리가 0이고 canonical 장소가 같으면 `same_place_continuity_v1` 위치 연속성으로 기록하도록 수정했다. 다른 장소의 기존 fallback과 외부 호출 정책은 바꾸지 않았다.
- GREEN: 조회 단위 테스트 19개 및 양수/0분 저장→조회→편집·복사 DB 시나리오 2개 통과(1분 18초). canonical migration 및 일정 계약 테스트 35개 통과(1.694초). 독립 부분 reviewer 신규 차단 finding 0건, 정식 승인/recorder 아님.
- DB에서 거리 null/양수, 요금/버퍼/각 시간 구성 양수, taxi, 30초 불일치 등 10개 변조를 savepoint별로 거부하는 추가 회귀를 실행 중이다. 전체 품질/Docker/upgrade 및 최종 generation completion writer는 여전히 후속이다.
- 추가 GREEN: 양수/0분 DB 시나리오와 잘못된 0분 leg 10개 거부, OpenAPI 재생성까지 통과했다(1분 19초). 이후 전체 unitTest/architectureTest 및 기존 JdbcScheduleMutationStoreIntegrationTest 회귀를 시작했다.
- Supabase changelog의 Breaking Change 항목과 공식 DB function 문서를 확인했다. 이번 변경은 기존 invoker 함수의 결정론적 검사이며 auth/realtime/extension/API 노출 설정 변경은 없다. 운영 DB 변경이나 원본·geometry 로그/저장은 수행하지 않았다.

## 2026-09-14 FE 저장 순서와 서버 공항 확정 후속

- FE 별도 worktree의 root→활동 시간→planner PUT→최종 GET 저장과 멱등 재시도 journal, canonical 숙소·스타일 복원을 연결했다. 관련 41개 및 전체 45 suites/324 tests, typecheck/lint/api:check/ui:check PASS. 화면·컴포넌트 파일 변경은 없으며 기존 58개 StyleSheet 일치만 검증했다.
- 항공 이벤트의 터미널 두 필드가 명시적 null일 때 기존 서비스 XOR가 거부하는 단위 RED(7개 중1개,5초) 및 실제 DB RED(1분4초)를 확인했다. 생성과 항공 저장이 동일 `TripAirportResolver`를 공유하고, owner/CAS 잠금 후 설정된 canonical ID의 제주국제공항·성공 TourAPI import·live 상태를 확인한다. 외부 조회나 FE UUID 하드코딩은 없다.
- 항공 요청만 null 예외를 허용하며 저장 행/응답의 XOR는 유지한다. 선박·두 필드 동시 지정은 계속 거부한다. 승인 공항이 없으면 PLACE_NOT_FOUND이며 어떤 장소도 추정하지 않는다. 실제 저장→생성 intake GREEN(1분12초 실행에 unit/architecture 포함).
- transport HTTP/DB 확장 회귀와 전체 unit/slice/architecture GREEN(3분43초, 기존 macOS 환경별 unit 9개 skip). Python 계약 정책 누락 RED→GREEN, 전체 scripts 947개 PASS/3skip(61.092초). wire SHA는 `a3d084234d5bc222a4551ff3f3ed9523960fb56371e790c08e3fb5d94687e4a5`로 catalog·ownership fixture·validator에 함께 반영했다.
- 제한적 독립 검토의 신규 차단 finding은 없으나 공식 승인/recorder는 실행하지 않았다. 항공 PUT 멱등성 및 FE 항공·장소 선호 저장, 재시작 journal, 최종 전체 품질 게이트·Docker·PR은 아직 남아 있다. 최종 1시간 이상 예상 검증은 사용자 요청대로 백그라운드로 실행하고 handle/log를 남긴 뒤 중단한다. 현재 장시간 최종 게이트는 시작하지 않았다.

## 2026-09-14 교통 이벤트 PUT 멱등 재시도 검증

- 선택적 UUID Idempotency-Key를 transport-event PUT에 연결했다. 키 없는 기존 요청은 유지하고, 키를 사용한 요청은 원래 본문·ETag를 재생한다. 현재 소유권 확인은 receipt 조회보다 먼저 실행한다. DELETE와 다른 선호 PUT으로 보장을 확대하지 않았다.
- 실제 HTTP First RED는 응답 유실 재시도에서 기존 ETag로 409가 반환된 실패였다. GREEN에는 동일 키·본문의 정확한 응답 재생, 본문 변경 409, 다른 사용자/삭제된 여행 404, 빈 값·비정규 UUID·중복 헤더 400, 실패한 저장의 예약 롤백 후 같은 키 재사용을 포함한다.
- 최종 해당 범위 실행 `spotlessApply unitTest sliceTest architectureTest integrationTest --tests '*TransportEventHttpPostgreSqlIntegrationTest' --tests '*TransportEventControllerIntegrationTest'` PASS(1분 56초). integrationTest만 두 클래스 필터이며 전체 DB 통합 검사가 아니다. 환경별 단위 테스트 9개 SKIPPED.
- canonical transport 계약·오류 fixture·OpenAPI runtime manifest·검증기를 함께 갱신했다. wire SHA는 `8026127e4bd249078aa5284e36ddc504741e2707d24302d4cc4f8c308f8fe15e`다. 선택적 헤더 예외는 transport PUT에만 한정한다. 관련 Python 54개와 mode43 readiness PASS는 해당 후속 HTTP 테스트 추가 전 확인했다.
- FE PUT wrapper의 선택적 키 전달 테스트 포함 7개와 typecheck PASS. 아직 saveTrip 입도·출도 저장 순서에는 연결하지 않았으며, 재시작 journal·장소 입력 저장·최종 전체 품질 게이트·Docker·정식 리뷰·PR은 완료되지 않았다. 최종 장시간 검증은 시작하지 않았다.

## 2026-09-14 선박 일반 저장과 1차 PR 범위

- 사용자 지시에 따라 앱 재시작 후 입력·작업 journal 복원 보강은 후속으로 분리했다. 1차 PR의 앱 실행 중 실제 저장→생성→세 후보 검토→선택 적용, 서버 worker 복구와 원자적 적용 요구는 유지한다. UI 구조 변경은 없다.
- 기존 화면에 선박 항구 입력이 없어 일반 저장까지 막히던 조건을 수정했다. 선박 두 터미널 null은 항구 미확정으로 보존한다. 항공은 여전히 승인된 공항 확정 후 터미널 하나를 저장하고, 양쪽 값 동시 지정은 양 수단 모두 거부한다. 선박은 생성 입력 검증에서 거부하며 임의 항구 이름·좌표를 만들지 않는다.
- 서비스 RED(7개 중 1개, 4초) 후 수정, 실제 HTTP·DB RED(422, 1분 7초) 후 additive migration 029/067을 추가했다. CLI로 빈 파일을 생성하고 현행 canonical suffix 뒤로 순서를 정렬했다. 기존 migration, 기존 행, RLS·ACL은 변경하지 않는다. Docker 3개 구성·manifest·순서 테스트·smoke의 실제 constraint 확인도 연결했다.
- GREEN: 해당 단위·아키텍처·HTTP/DB 회귀 1분 13초. 전체 unit/slice/architecture 및 두 transport integration 클래스·OpenAPI 재생성 2분 4초 PASS(환경별 unit 9개 skip). 직접 SQL로 항공 null 전환과 선박 양 터미널 지정 거부도 검증했다.
- wire SHA는 `7789e14f05290d2a24b13c0351fdb723965c89ee967ac1b48bba1bbd313e20d6`로 갱신했다. 전체 Python 949개 중 공통 REST validator의 optional key 미등록으로 10개 실패한 원인을 확인하고 해당 PUT만 허용하는 정책·회귀를 추가했다. 관련 78개 및 OpenAPI43 readiness PASS; 전체 Python 재검사는 진행 중이다.
- 제한적 독립 검토에서 신규 차단 finding 없음. 정식 승인/recorder는 아니다. FE 항공·장소 저장 orchestration, 삭제 재시도, 최신 계약 동기화 및 전체 품질/Docker/최종 PR은 계속 진행해야 한다.
- 후속 GREEN: 전체 scripts 950개 검사 완료(3skip, 56.503초). 항구 미확정 null 선박의 실제 DB intake 거부·run 0건 테스트 PASS(1분9초). Supabase security advisors를 동일 격리 Testcontainers DB에 시도했으나 DB 종료와 겹쳐 연결이 끊겼다. 진단 완료로 계산하지 않고 최종 격리 DB 검증에서 재실행한다. 새 제약은 행·권한·함수를 추가하지 않지만 전체 보안 진단 통과를 이 사실로 대체하지 않는다.

## 2026-09-14 교통 DELETE receipt와 FE 입출도 orchestration

- DELETE 응답 유실 후 같은 키·ETag 재시도가409인 실제 HTTP RED(1분4초)를 확인했다. PUT·DELETE의 공통 mutate에서 선택적 UUID 키, 현재 owner 선검사, 같은 트랜잭션 receipt를 사용한다. DELETE의 검증된 eventType만 해시에 포함하며 raw query와 request body는 저장하지 않는다. 다른 selector409·타 사용자404·빈 키400·동일 본문/ETag 재생 및 DB 무변경 GREEN(1분7초).
- OpenAPI slice/재생성 PASS(18초), 관련 Python108개 PASS(5.944초), OpenAPI43 readiness PASS. 전체 unit/slice/architecture와 두 교통 integration 클래스 PASS(1분54초, 환경별 unit9skip). 전체 scripts950개 검사 완료(3skip,51.802초). wire SHA `9a91c53218c360d94d1492a166ad63c9d38fc3f2bab64c03b5e3e84908738cf1`로 정렬했다.
- FE saveTrip에 실제 입도·출도 PUT/삭제 단계를 넣었다. 최초 조건 snapshot, 단계별 키/ETag/body, 남은 단계만 재시도, 최종 GET 유실 시 조회만 재시도, 입력 변경/최종 ETag 불일치의 완료 오표시 방지를 검증했다. 부분 reviewer의 기존 상세 유실 finding은 RED2개로 재현 후 동일 수단 상세 보존·수단 변경 시 터미널/편명만 초기화·메모 보존으로 수정했다. 재검토 신규 차단0이며 정식 승인/recorder는 아니다.
- 날짜별 장소 선호 저장, 최신 FE 계약 인계, 실제 생성·검토·적용 최종 연결 검증과 전체 품질 게이트/Docker/정식 리뷰/PR은 남아 있다. 앱 재시작 journal 보강만 사용자 요청으로 후속이며 서버 worker 복구와 적용 정합성은 유예하지 않는다.

## 공개 입력 복원 문서 후속

- 재생성 OpenAPI에서 TripDetail plannerConditions/placePreferences 및 place-preferences requestedStayMinutes 예시 누락을 확인했다. 실제 `/v3/api-docs` slice RED(15초) 후 공통 customizer의 예시를 현재 DTO에 맞췄다. 사용자 지정 90분과 미지정 null을 구분한다.
- planner-conditions가 Controller 태그와 불완전한 예시를 노출하는 slice RED(18초)를 확인했다. 공통 operation document 및 조건부 헤더 경로에 등록하여 여행 태그, 필수 If-Match/Idempotency-Key, ETag/replay 응답, 저장 결과와 Problem 예시를 문서화했다.
- `FrontendOpenApiRuntimeIntegrationTest` 5개 GREEN(최종 15초), 전체 OpenAPI 생성 GREEN(25초 실행에 포함). 일정 GET의 별도 inline 예시에도 boundaryRole=null을 추가했다.
- 필수 헤더 manifest의 planner-conditions 누락에 대한 한글 목적 Python 테스트 RED→GREEN. 관련 OpenAPI 검사 테스트 31개 GREEN(2.308초). 검증을 느슨하게 하지 않고 실제 헤더를 필수로 등록했다.
- 기존 mode38 operation 목록은 역사적 계약이므로 변경하지 않았다. 생성 접수의 공통 문서화 및 신규 operation을 포함하는 후속 활성 모드/권위 manifest 연결은 아직 필요하다. 전체 FE readiness 통과나 최종 PR 준비 완료를 선언하지 않는다.

- 후속 복사 RED: 활성 후보의 일반 방문 메모 수정이 `SCHEDULE_ITEM_INVALID`로 실패(1분 27초). 공통 source mapper/복사 INSERT/position clone에 boundary role을 전달한 뒤 동일 DB 시나리오 GREEN(1분 19초).
- 기준점 자체의 동일 위치 move가 허용되는 RED(예외 미발생, 1분 11초)를 확인했다. patch/delete/move는 명시적 `ensureEditable` 검사로 거부하도록 수정하고 거부 후 버전 수 유지 assertion을 추가했다.
- 공개 DTO는 boundaryRole이 없어서 일반/시작/종료 3개 직렬화 테스트 RED(10초). nullable boundaryRole과 기준점에 한정된 stayMinutes=0 설명을 DTO 및 REST canonical 계약에 연결했다. 공개 OpenAPI 생성 검증은 후속 실행이 필요하다.
- 독립 reviewer의 저장/조회/clone 부분 검토 결과 신규 차단 finding 0건. 알려진 공개 계약·편집 보호·0분 leg 후속 범위를 제외한 부분 검토이며 정식 승인 또는 recorder 증거가 아니다.
- 후속 GREEN: `spotlessApply unitTest integrationTest` 실행(마지막 integrationTest만 시나리오 필터 적용)에서 전체 단위 테스트와 기준점 저장→조회→일반 방문 수정·복사→기준점 patch/delete/move 거부 및 버전 수 보존이 통과했다(1분 29초). 공개 DTO 직렬화 3개도 포함한다. Linux 전용 단위 테스트 9개는 macOS 조건으로 skip됐다.
- `openApiDocs` 생성 GREEN(13초). REST 일정 validator의 기존 필드 목록 및 success fixture 누락을 갱신했고, generation boundary 계약 변조 4개 subcase RED→GREEN 및 일정 계약 테스트 21개 GREEN을 확인했다.
- 전체 `validate_openapi_frontend_readiness.py --mode 38`는 아직 FAIL이다. 생성 접수/planner-conditions의 공개 inventory·domain tag·parameter/응답 예시와 header 계약, place-preferences의 requestedStayMinutes 예시, TripDetail의 plannerConditions/placePreferences 예시가 미등록이다. boundaryRole fixture 갱신 전 생성된 산출물도 재생성이 필요하다. 이 검사를 통과했다고 주장하지 않으며 최종 PR 전 필수 후속 범위에 포함한다.

- 실제 DB에 봉인된 기준점 포함 후보를 `ScheduleStore.readOwned`로 조회하는 테스트가 `INTERNAL_SERVER_ERROR`로 실패했다(RED, 1분 24초).
- 조회 SQL과 내부 snapshot에 `boundary_role`을 연결했다. 일반 항목은 최소 1분을 유지하며, 0분 기준점은 명시된 역할·canonical 장소·동일 시작/종료·0분 버퍼를 모두 요구한다.
- 동일 DB 통합 테스트 GREEN(1분 27초), `JdbcScheduleStoreTest` 기존 4개와 잘못된 기준점 7개 사례 GREEN(15초, spotlessApply 포함).
- 내부 역할 보존 assertion을 추가했다. 공개 DTO/OpenAPI 및 복사·편집 보호, 동일 장소 0분 이동 연결은 아직 진행 전이다. 최종 PR 준비 완료로 간주하지 않는다.
# 2026-09-14 저장된 구간 위험의 공개 조회 연결 (진행 중)

- GenerationTimeline의 저장된 등급/사유를 trip_legs.facts.generation.risks에서 제한적으로 읽는 ScheduleLegRiskProjection을 추가했다. 일반 일정은 riskLevel=null, 빈 생성 risk는 unknown이며 점수로 새 등급을 계산하지 않는다. 자유문 형태의 reason은 거부하고 예외 원문은 노출하지 않는다.
- JdbcScheduleStore → ScheduleLegSnapshot → ScheduleLegResponse의 riskLevel/riskReasonCodes를 연결하고 canonical schedules 계약에 필드를 추가했다. 새로운 DB 저장/외부 호출은 없다.
- First RED: 새 projection 미구현 컴파일 실패. GREEN: spotlessApply 및 projection unit3개 통과(5초).
- 실제 JdbcScheduleStoreIntegrationTest 7개 중5개 통과,2개 실패(1분10초). 활성 일정의 DB 위험정보 조회 assertion은 통과했지만 후보 조회2개가 CANDIDATE_EVIDENCE_UNAVAILABLE로 실패했다. 전체 통합 검증 성공이 아니며 후보fixture/조회 계약 원인을 추가 점검해야 한다.
- validate_schedules_contract.py와 diff-check 통과. OpenAPI 생성/검증·FE 재인계·UI risk mapping·전체 qualitygate·정식 reviewer·최종 PR은 아직 남아 있다. 현 변경은 미커밋이다.
- 후속 OpenAPI slice/생성 및 projection unit PASS(21초). 조회 fixture의 AI 버전에 생성 후보 보존 row가 없어 거부되는 것이 현행 TTL 계약임을 확인했다. 해당 테스트는 보존 근거 누락 거부·child 조회 전2query를 검증하도록 바로잡았다. JdbcScheduleStoreIntegrationTest 전체7개 PASS(1분4초), 운영 조회 허용 조건은 완화하지 않았다. 유효 후보 긍정 조회는 실제 GenerationIntakeIntegrationTest 생성 파이프라인으로 별도 확인한다.
- 독립 부분 reviewer 신규 차단 없음(정식 승인/recorder 아님). 전체 완료 및 PR 준비를 의미하지 않는다.
- 실제 GenerationIntakeIntegrationTest의 최초 성공완료 normal/rollback/concurrent_apply 3개 시나리오 PASS(1분27초). 생성된 후보3개·만료 전 긍정 조회·만료 거부·소유권·롤백·동시 적용 경로를 포함한다. 전체 integrationTest/qualitygate 성공 증거와 구분한다.
