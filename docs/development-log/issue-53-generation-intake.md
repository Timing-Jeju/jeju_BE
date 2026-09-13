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
