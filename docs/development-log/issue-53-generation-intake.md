# #53 생성 접수 구현 — 요청 경계

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
