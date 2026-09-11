# #224 위치 schema·runtime 제거 — 구현 진행 기록

2026-09-09. 최신 origin/develop ec2225d에서 refactor/224-remove-location-runtime 별도 worktree를 만들었다.
선행 #223은 PR244로 전체 품질/독립 리뷰/CI 통과 후 develop73cb2b9에 병합됐다. 현재 worktree는 해당 develop을 정상 merge --no-commit으로 반영한 상태다. PR241의019/init057 재정렬 전체 검증은 진행 중이며 DB SSL 연결 실패2건을 확인해 아직 성공으로 판정하지 않는다.
이 문서는 #224 전체 완료 증거가 아니다. REST 사전 검증, Java hash v2, 위치 저장소/cleanup 제거와 MCP 전송 전 차단을 구현 중이다. DB020, MCP0.8 release, 정규 전체 품질 검증은 남아 있다. 아래 과거 RED 기록은 당시 상태이며 최신 결과는 다음 절을 기준으로 한다.

## 최신 구현 및 검증

추가 진행: 선행019 변경을 동일 내용으로 로컬 사전 반영하고020/init058을 등록했다. SQL020 checksum은 `2f6b38e97216a9eddb9886306751c9368d1ace8b704fb903bdd15a3b380e9e2e`다. 실제 DB 실행·배포·커밋은 아직이다. 최신 Java main1,059/test388 컴파일 및 단위77개 PASS, 전체 Python849개 중846 PASS/3 SKIP(51.077초, `/tmp/jeju-224-static-complete.log`)다. canonical schema/smoke/음성 SQL을 v2로 전환했고, 표적 오류 메시지를 비교해 다른 guard에서 발생한 실패를 성공으로 오인하지 않게 했다. immutable 음성은 정상 shape를 유지하는 created_at 변경으로 수정했다. Supabase 역사 TTL 동시성 fixture는 별도 로컬 PG17 DB016에만 적용하도록 분리했다. 목록·checksum·경로·cutoff4개 RED→GREEN 및 shell syntax PASS이며 실제 Supabase 검증은 남아 있다. DB fixture와 SQL audit의 독립 소스 리뷰는 진행했지만 공식 전체 승인은 아니다.

PR241의 전체 gate는 JDBC SSL2개 실패 확정 뒤 소유 watchdog을 통해 중단했다. 잔류한 해당 테스트 worker의 종료와 테스트 컨테이너 자동 정리를 확인한 후 실패한 두 테스트를 정규 Gradle에서 단독 재현 중이다. 중단된 실행을 통과 증거로 사용하지 않는다.

DB020 초안은 Supabase CLI2.117.0 `migration new`로 생성한 파일을 예약 번호로 정렬해 작성했다. v1 원본 hash/계보 검증, v2 input·부모 원자적 변환, deferred 검증 후 정확한 열·함수 제거를 포함한다. manifest 미등록이며 실제 DB에는 실행하지 않았다. 초기 migration 테스트8개 RED는 미등록으로 Docker 시작 전 실패한 것으로, DB 전환 자체의 RED 증거가 아니다. 추가 독립 리뷰에서 함수 본문의 대문자·event 위치 참조 미검출을 확인해 old-field 대소문자 검사와 event 함수 signature/본문 SHA256 감사로 보강했다. PG16/17 rollback 및 공개 장소 함수 보존 테스트를 추가했으며 실제 실행은 남아 있다. 최신 전체 소스 컴파일은 main1,059/test387개 PASS, 단위77개는 재검증 중이다. SQL 추가 전의 Python841개 결과를 현재 migration 검증으로 사용하지 않는다.

후속 재검증: 최신 main1,059개/test386개 fresh javac compile 및 대상77개 단위 검증이 통과했다(`/tmp/jeju-224-current-focused-unit.log`). MCP의 schema 반환 copy 재검사와 command canonicalizer의 단일 deepCopy 사용을 각각 실제 RED→GREEN으로 확인했고 독립 재리뷰 추가 finding0이다. 전체 Python은841개 중838 PASS/3 SKIP(51.922초, `/tmp/jeju-224-static-after-runtime.log`)다. 역사501건 batch SQL 검증은017 이전+010의 migration 테스트로 이동했고, 현행 v2 통합에는 RFC3339/offset10개 경계와 다국어 hash 대조를 유지했다. 이 DB 테스트들은 컴파일만 완료했으며 실제 실행은 남아 있다.

DB020 검증 경로도 구분했다. Docker의 기존 cleanup 동시성 SQL은 별도016 legacy DB에서 실행하므로 역사 회귀로 유지한다. Supabase smoke는 같은 SQL을 최신 DB에서 실행하는 경로가 있어 현행/역사 구분을 보완해야 한다. canonical schema/smoke 및 negative constraint의 v1 input fixture도020과 함께 갱신해야 한다. migration 등록·실제 Supabase 실행은 하지 않았다.

- canonicalizer/request/snapshot은 schemaVersion2를 사용하고 location 필드 및 digest를 해시 문서에서 제거했다. 저장 snapshot의 v1도 명시적으로 거부한다.
- 위치 모델/resolver와 TTL cleanup package·설정·전용 테스트를 제거했다. public geodata/planned anchor는 제거하지 않았다. 역사 SQL cleanup migration 테스트는 유지한다.
- JDBC input 저장소는 위치 열과 findUsableLocation API를 제거했다. 현재 v2 repository 통합 테스트를 작성하고 정상 fixture를 v2로 전환 중이다. DB migration이 없으므로 통합 성공을 주장하지 않는다.
- worker claim/재시도 종료는 v2 lineage를 명시적으로 요구한다. 해당 구조 회귀3개 RED→GREEN이다.
- 최신 운영 Java1,058개와 테스트386개를 오래된 product classpath 없이 JDK21로 컴파일했다. hash/canonicalizer/repository/context19개 사전 단위 검증은 통과했다. 이 수치는 이후 MCP guard 추가 전이다.
- MCP14개 baseline은1 PASS/13 RED였다. 전송 전에 위치 파생 키와 schema를 검사하고, 실제 wire hash를 계산한 뒤 실제 wire schema도 다시 검증하도록 수정해14 PASS를 확인했다. 추가 파생 키/정상 전송 hash 회귀 검증을 이어간다. Sentinel은 preflight의 필수 envelope 검사용으로만 사용하며 외부 호출/audit에 저장하지 않는다.
- 실행 로그: `/tmp/jeju-224-current-main-compile.log`, `/tmp/jeju-224-current-test-compile.log`, `/tmp/jeju-224-no-location-unit.log`, `/tmp/jeju-224-worker-schema-red.log`, `/tmp/jeju-224-worker-schema-green.log`, `/tmp/jeju-224-current-mcp-red.log`, `/tmp/jeju-224-mcp-prehash-green.log`.
- #241 gate와 충돌하지 않도록 이 단계는 별도 javac/JUnit/Python으로 실행했다. 정규 Gradle·PG16/17·전체 gate·공식 리뷰·PR·staging은 아직이다. FE UI는 수정하지 않았다.

## 테스트 순서

1. Trip create, schedule addItem의 거부할 body가 IdempotencyRequest hash/저장 전에 차단됨.
   정상 원문 bytes·멱등 replay·ETag를 보존함. patch/reorder/move는 기존 선검증 회귀.
2. 실제 MCP wire 계약/위치 비수집 거부가 hash/외부 호출/audit 전에 발생함.
   commandInputHash가 정상이라는 이유로 wire의 위치 부재를 추정하지 않음.
3. canonical hash no-location v2: 과거 위치 필드 제거, 증명된 non-location row만 변환.
4. SQL 새 forward migration은 #223 marker+zero residue와 catalog를 잠금 하에 확인하고
   정확한 dependency만 CASCADE 없이 제거. 부모/input/worker/MCP hash lineage와 raw 요청 receipts 경계 보존.
5. 위치 cleanup bean/config/metrics 제거와 계획 anchor/public geodata 보존, PG16/17·두 세션·ACL·fingerprint·전체 gate.

## 초기 상태 기록(아래 최신 구현으로 대체됨)

- TripCreateNoLocationPreHashTest, ScheduleCreateNoLocationPreHashTest, McpWireNoLocationPreHashTest를 추가했다.
- TripController/ScheduleMutationController 두 운영 Java 파일을 수정했고 관련19개 사전 JDK/JUnit 테스트가 통과했다. Hash v2는4개 RED, MCP는 정상대조군1 PASS/거부13 RED다. SQL 및 위치 runtime 제거는 미구현이다. 추가 DB/Gradle 실행은 없다.
- #223의017은563904e부터 불변. 향후 schema 변경은 새 migration으로만 수행한다.
- MCP_ENABLED는 계속 false. 실제 staging/provider/운영 DB는 실행하지 않았다.

## 테스트 초안 독립 검토 보완

필수 정상 필드 없는 privacy payload와 unknown test_tool로 인한 거짓 성공 가능성2건을 보완했다.
정상 create body에 금지 필드만 주입하고 동일 정상 body의 replay를 검사한다.
MCP는 실제 도구 이름과 AI0.7 합성 fixture를 사용한다. 독립 리뷰에서 current_position만 제거해도 progress.current_*의 출처가 불명확하다는 finding을 확인했다. 정상 대조군을 fixture의 itinerary로 구성한 evaluate_jeju_day_trip으로 분리하고, 같은 evaluate 입력에 금지 필드만 추가하는6개 쌍과 current/nearest ID5종의 거부를 추가했다. 역사 fixture 원문은 불변이다. schema guard mock 경계 테스트이며0.8 계획 진행 계약의 정상 검증을 대체하지 않는다. 실제 JDK/JUnit 실행 결과14개 중 정상대조군1 PASS, 거부13 RED다. MCP 운영 코드/manifest는 아직 변경하지 않았다.

NoLocationHashV2Test 초안은 위치 필드 없는 명시적 v2 문서, 결정성/갱신 요청 변화, v1 암묵 전환 거부, record 위치 필드 제거를 검증한다. 기존 코드의 위치 parameter를 null로 넘기는 helper는 실제 API 제거 때 갱신할 예정이다. 사용자 체류 시간 자체는 현재 canonical command projection의 필드가 아니므로 이 테스트가 체류 시간 wire/DB 보존까지 검증했다고 주장하지 않는다. 해당 FE 회귀는 PR6에서 확인했고 생성 입력 계보는 #89 이후에 검증한다.

### 무위치 hash 단위 테스트의 사전 RED

`NoLocationHashV2Test`를 JDK21 javac와 로컬 cached JUnit6 Launcher로 실제 canonicalizer·record 소스와 함께 컴파일·실행했다. 기준은 `ec2225d3ca5324b45a41860eb4232ed9dcd5d72b`다. 4개가 모두 의도대로 실패했다: v2 요청2개는 지원하지 않는 schema version으로 거부, v1 거부 기대는 기존 수락 때문에 실패, record 무위치 기대는 잔존 위치 필드 때문에 실패했다. Hash 운영 코드는 아직 변경하지 않았다. #223와 PR241 병합 뒤 최신 base에서 RED를 재확인하고 구현한다.

## REST create 입력 검증의 RED → GREEN

여행 생성6개·일정 추가5개의 금지/중복/잘못된 JSON 사례가 hash 이전 차단을 충족하지 못해 RED였다. 정상 raw-byte hash와 저장 응답 replay 대조군2개는 통과했다. 엄격한 duplicate/unknown/trailing JSON 검증을 IdempotencyRequest 생성 이전으로 옮겼다. Schedule의 날짜·업무 검증을 수행하는 toCommand는 기존 mutation callback 안에 유지했다. 여행 생성은 크기 제한과 null byte[]도 먼저 검사한다.

JSON literal null, 배열, 빈 입력을 추가한 뒤 여행 literal null이 NPE가 되는 RED1을 확인했다(`/tmp/jeju-224-null-boundary-red.log`: Trip9 PASS/1 FAIL, Schedule9 PASS). 역직렬화 결과 null을 TripException.invalidRequest로 처리한 뒤19/19 PASS(`/tmp/jeju-224-prehash-green.log`)다. GoogleJavaFormat1.28.0과 diff 검사도 수행했고 독립 source 재리뷰 추가 finding0이다.

검증은 실제224 controller 두 소스와 테스트를 별도 임시 디렉터리에 javac로 컴파일하고 JUnit6 Launcher로 실행했다. dependency classpath는 진행 중인223 Gradle worker의 설치된 의존성을 읽기 전용으로 사용했다. ec2225d와223 main Java 차이는 JdbcRunLeaseRepository뿐이며 이 검증은 해당 클래스를 사용하지 않는다. 실행기 `/tmp/run_jeju224_prehash.py` 및 classpath 사본 `/tmp/jeju-224-jdk-classpath.txt`는 임시 보조 산출물이다. 실제 HTTP·정규 Gradle·최신 base·전체 gate를 대체하지 않는다. 아직 미커밋·미푸시다.


### 요청 크기 경계 추가 검증

여행 생성·일정 추가 각각 허용 byte 크기와 정확히 같은 정상 JSON(후행 공백 포함)의 원문 hash 및 replay 응답 보존을 확인했다. 허용 크기를 넘는 입력은 hash·저장 전에 거부한다. 기존19개에 경계4개를 더해 Trip12/Schedule11, 총23/23 PASS다(`/tmp/jeju-224-prehash-size-boundary.log`). 운영 코드는 추가 수정하지 않았고 두 테스트의 GoogleJavaFormat 일치도 확인했다. 정규 HTTP/Gradle/전체 게이트를 대체하지 않는다.

### Spring MVC와 실제 오류 Advice 경계 검증

`CreateNoLocationMvcBoundaryTest`를 추가하고 실제 Controller/Advice/ProblemResponseWriter를 standalone MockMvc로 연결했다. 여행 생성·일정 추가에서 JSON null/배열/정상 필수 입력에 위치 필드 하나를 추가한 요청은 400 INVALID_REQUEST이며 원문 비반사와 hash·저장 미호출을 확인한다. 같은 MVC 설정에서 정상 요청의 raw bytes/hash·201 replay·ETag·응답 본문을 검증하는 양성2개를 포함해8/8 PASS다(`/tmp/jeju-224-mvc-boundary.log`). 기존 직접 호출23개와 별도 MVC8개를 구분한다.

초기 테스트 fixture의 TripProblemDefinitions 등록 누락은 fixture 설정 오류로 수정했으며 제품 RED 근거로 사용하지 않는다. 독립 리뷰에서 GPS 사례에 정상 필수 필드가 빠진 false-positive 가능성을 지적받아 완전한 정상 body + 금지 필드의 쌍과 정상 replay 대조군으로 보완했다. 읽기 전용 재리뷰 추가 finding0이다. GoogleJavaFormat 적용 및 diff 검사를 수행했다. 이 검증은 인증 필터·실제 HTTP 서버·DB·정규 Gradle/전체 게이트를 포함하지 않는다. 운영 코드 추가 변경은 없다.

### 정규 Gradle 대상 검증

#223의 전체 게이트가 종료된 뒤 `./gradlew --no-daemon unitTest --tests '*TripCreateNoLocationPreHashTest' --tests '*ScheduleCreateNoLocationPreHashTest' --tests '*CreateNoLocationMvcBoundaryTest'`를 실행했다. Gradle BUILD SUCCESSFUL(21초), Trip12/Schedule11/MVC8 총31개 failures0/errors0/skipped0을 JUnit XML에서 확인했다(`/tmp/jeju-224-rest-gradle.log`). 이는 기존 사전 실행과 동일한31개를 정규 실행한 결과이며 테스트 수를 중복 합산하지 않는다. 현재 base에서의 대상 unit 검증이고, 선행 PR 병합 후 재검증·인증/DB/전체 게이트는 남아 있다.

### 정규 Gradle의 hash/MCP RED 확인

같은 base에서 `./gradlew --no-daemon unitTest --tests '*NoLocationHashV2Test' --tests '*McpWireNoLocationPreHashTest'`를 실행했다. hash v2는4개 모두 실패, MCP는14개 중 정상 대조군1개 통과/거부13개 실패, errors0/skipped0을 JUnit XML로 확인했다(`/tmp/jeju-224-hash-mcp-gradle-red.log`). 기존 사전 JDK 실행의 동일한 미구현 경계를 정규 Gradle에서 재현한 것이며 전체 테스트 성공이 아니다. MCP/hash 운영 코드 변경은 아직 없다. 이 실행이 마지막 unitTest XML을 대체하므로 앞선 REST31개 결과는 별도 실행 로그와 기록으로 구분한다.

## #223 감사 보강에 따른 의존성 갱신

#223은 017 불변 원문과 018 revision request hash verifier를 원자 그룹으로 적용한다. 후속 PR241도017/init055를 사용한 번호 충돌이 발견되었으므로, #223 병합 후 미적용 PR241을019/init057로 재정렬·검증하는 순서다. #224는 두 선행 작업 병합 뒤 실제 manifest를 다시 읽고 다음 슬롯을 선택한다(현재 잠정020/init058). 이전018·019 예약 가정은 폐기한다. marker018과 최종 잔여0 및 실제 predecessor catalog를 확인해야 한다.

compute parent input_hash와 command hash의 일치는 기존 감사에서 입증되지만 revision request_hash는 독립 opaque hash다. generation에는 input_hash 열이 없다. 응답이나 부모의 독립 hash를 command hash와 같다고 가정하거나 재작성하지 않는다. v17 wrapper verifier와018 verifier 모두 구 location 열에 의존하므로 삭제 순서/catalog 검증에 포함한다. Supabase migration ledger와 canonical atomic group 경로도 유지한다.

## 020 구현과 PostgreSQL 검증 진행

020/init058은 019를 선행 계약으로 등록했다. 아래 첫 DB 실행 당시 SQL checksum은 `97790403f07286a8b238360fcc74ce3016c571408829342b281c5e0ff9ff8e54`다. 무위치 schema v2와 6인자 hash로 입력·부모를 원자적으로 변환하고, 기존 위치 열·TTL 함수·8인자 hash를 제거한다. 알 수 없는 의존성과 감사되지 않은 활성 상태는 자동 제거하지 않고 rollback한다. 공개 장소 좌표는 유지한다.

`/tmp/jeju-224-schema-focused-postgres.log`의 정규 Gradle 실행은 20분 11초 뒤 실패로 종료했다. PostgreSQL 16/17의 schema migration 22개, 입력 repository 22개, 기존 cleanup migration 2개는 모두 통과했다. worker 12개 중 11개는 이전 8인자 hash fixture, 위치 guard 50개 중 8개는 제거된 열의 SQLSTATE 42703이 아닌 바깥 오류 문구를 기대해 실패했다. fixture를 v2로 바꾸고 원인 SQLSTATE 검증으로 수정했으며 재검증 전이다. 이 부분 성공을 전체 gate 통과로 취급하지 않는다.

후속 검증에는 현재 seed와 schema/negative SQL 실행을 추가했다. 22개 migration 사례의 준비 비용을 줄이기 위해 PostgreSQL 버전별 선행 schema template을 만들고 각 사례마다 별도 DB로 복제한다. 데이터는 사례 사이에 공유하지 않으며 dropdb는 force 없이 연결 누수를 검출한다. 독립 리뷰에서 발견한 초기화 AssertionError 시 컨테이너 정리 누락은 Exception과 함께 처리하도록 수정했고 재리뷰 finding 0이다. 변경된 준비 방식과 seed 검사는 별도 재실행 중이다. 배포, Supabase 실제 실행, FE UI 변경은 없다.

### 실제 seed 및 기존 실패의 GREEN

실제 seed 연결 후 PG16/17 두 사례에서 제거된 event.location 참조로 RED를 확인했다(`/tmp/jeju-224-seed-positive-red.log`, 2분2초). pg_temp fixture helper로 정확한020 marker에서는 v2, 과거 schema에서는 v1을 사용하도록 수정했다. event/live의 과거 nullable 열과 input의 DEFAULT false 열은 생략해 양쪽 schema를 지원하며 운영 runtime fallback은 없다. 재실행은2 PASS(2분8초), 해시 호출 검사19 PASS다. 최신 Python 전체849개 중846 PASS/3 SKIP(51.521초)다.

`/tmp/jeju-224-v2-db-regression.log`의 정규 Gradle 실행은12분24초에97 PASS/0 FAIL/0 ERROR/0 SKIP다: 두 세션 lineage6, worker12, 위치 guard50, revision schema5, 새 migration22, 역사 canonical2. 기존19개 실패의 수정과 사례별 DB 복제 준비 방식도 이 실행에서 검증됐다.

추가 독립 source 리뷰에서 최종 dependency 감사의 prokind=f 조건이 저장 프로시저를 제외하는 문제를 발견했다. 일반 위치열 및 event.location을 참조하는 프로시저의 PG16/17 재현4개를 추가했고, SQL 보완 전 RED를 실행 중이다. 위97개 통과는 이 신규4개를 포함하지 않는다.

프로시저4개는 모두 예외 없이 migration이 성공하는 의도된 RED로 재현됐다(`/tmp/jeju-224-procedure-red.log`, 2분2초). 두 감사 조건을 f/p/w로 넓히고 aggregate에 대한 pg_get_functiondef 호출은 CASE로 계속 차단했다. 현재020 checksum은 `97daf26d6756f45d66a393d04339304f6f881af34c32d46a2cf15abfe0df985b`이며 manifest를 함께 갱신했다. 독립 재리뷰에서 해당 finding 해결을 확인했고 canonical/hash 정적33개 PASS다.

최신 소스의 `NoLocationSchemaMigrationIntegrationTest` 전체를 실제 PostgreSQL 16/17로 다시 실행했다. schema v2 정상 전환·원자 rollback과 프로시저 의존성4개를 포함한26개가4분1초에 모두 통과했고 failures/errors/skipped는0이다. 이어서 Spotless, 단위 테스트1,533개(9 SKIP), architecture48개가 failures/errors0으로 통과했다. common 품질 게이트도 계약·비밀·SQL 정책 검사와 Python849개(3 SKIP)를 포함해 성공했다. canonical 전체7, 두 세션 lineage6, worker22, 위치 guard50, revision schema5, schema v2 26개를 함께 실행한 최신 DB 회귀는116개가18분37초에 failures/errors/skipped0으로 통과했다. PR241 최신 HEAD는 CI Green·MERGEABLE이지만 독립 리뷰와 병합 전이므로, 이 결과는 로컬 사전 검증이며 #224 병합 가능 판정이 아니다.

### 독립 hash 저장 제거와 closed command hash 결합

#223 리뷰의 잔여 finding을 #224 DB020에서 다시 감사했다. `mcp_compute_call_logs.mcp_input_hash`와
`schedule_revision_runs.request_hash`가 closed command hash와 독립적으로 남는 두 경계를 PG16/17
테스트로 먼저 고정했다. wire hash 열 부재 기대는 실제 열 1개 때문에 실패했고, 다른 revision
request hash 거부 기대는 commit 성공으로 실패해 의도한 RED 4건을 확인했다.

DB020은 MCP wire hash 열과 관련 constraint를 제거하고, revision lineage verifier가
`request_hash = command_input_hash`를 요구하도록 보강했다. Java audit record/writer는 wire hash를
보존하지 않으며 실제 MCP 요청·응답 검증 동안만 메모리에서 사용한다. 위치 잔여 verifier도
독립 wire hash의 존재 자체 대신 command lineage 불일치와 revision hash 불일치를 집계한다.
최신 DB020 SHA-256은
`69df6c6f019efb1e3544ae36ec4b0332a8c299cca059a8931cebd04dcc3d163a`이며 manifest 값과 같다.

새 schema migration 클래스는 PG16/17 30/30(4분15초), MCP 단위42/42,
MCP schema2/2가 통과했다. 기존 회귀 fixture도 v2 hash와 제거된 열에 맞춰 갱신했다.
첫 확장 실행은 120개 중11개가 과거 fixture 때문에 실패했으며 이를 성공 근거로 사용하지 않는다.
수정 뒤 실패했던 네 클래스83/83(5분37초), 마지막 여섯 클래스 전체120/120
(18분45초, failures/errors/skips 0)이 통과했다. 구성은 canonical7, lineage6,
command snapshot22, 위치 guard50, schema v2 30, revision schema5다.

공통 품질 게이트는 원자 cutover generator·실행기·직접 push 차단 정책을 포함해
Python865개 중862 PASS/3 SKIP로 성공했다(`/tmp/jeju-224-common-after-atomic-cutover.log`).
v2 hash 직접 호출 inventory는 최종 안정 상태의
20개를 exact typed call로 검증한다. Spring Spotless와 test compile도 통과했다.

### 017·018 원자 배포 경계 보강

저장소 workflow와 실행 스크립트에는 실제 `supabase db push` 호출이 없지만, 운영자가 일반 CLI를
직접 실행하면017이 commit된 뒤018이 실패할 수 있는 위험은 남아 있었다. 실행 가능한 script/workflow의
고정 CLI, 환경변수 CLI, Python argv, 줄 연속, `npx`/`bunx`/`pnpm` 고정 버전 호출을 검사하는
fail-closed 정책을 공통 gate에 추가했다. cutover wrapper도 raw `db push`를 호출할 수 없다.

고정 `npx --yes supabase@2.110.0`을 실제 실행하고 016까지만 복제한 격리 로컬 Supabase를 기동했다.
ledger는 `version text NOT NULL`, `statements text[] NULL`, `name text NULL`이며 version 단일 PK,
52개 이력의 마지막은016이었다. 실제 Supabase PG17 canonical fingerprint는
`f653e443df2891370dcb07d2ce36260e`였다. raw PostGIS fixture의016 fingerprint는 PG16
`19745c65ef17192f09bfbb7d3167a3d1`, PG17 `948a3dbda299b1b6621522b69c3167bb`로
서로 달라 server major와 기반 환경별 승인값이 필요함을 확인했다. 격리 스택은 조회 후 중지·삭제했다.

배포 실행기 초안은 reviewed SHA, clean tree, 지정 psql major, 생성 SQL byte,
0600·현재 소유자·비 symlink DB URL 파일, ledger 구조를 모두 쓰기 전에 검사했다. URL은 argv에 넣지
않고 `PGDATABASE` 자식 환경에만 전달하고, 사후 marker018·residue0·ledger2행이 다르면 성공으로
보고하지 않는다. 이 초안의 실행 파일과 SQL 재개방 문제는 아래 검사-사용 결합 보강에서 해결했다.
초기 실행기·직접 push 정책·기존 group 생성기 대상 Python20/20가 통과했다. 실제 staging 적용이나
reviewed SHA 주입은 하지 않았다.

동일 transaction preflight 구현 뒤 첫 PG16/17 실행은 PK 열의 `information_schema.sql_identifier[]`와
검토값 `text[]` 비교 타입 오류로2건 실패했다. `column_name::text`를 명시하고 실제 CLI ledger 순서와
달랐던 합성 fixture를 `version, statements, name`으로 수정했다. 재실행은 Supabase 합성 ledger
PG16/17 2/2가1분58초에 통과했다. 이어 실제 CLI2.110.0 격리 PG17의016 상태에 생성 group SQL을
적용해 marker018, residue0, 이력017·018을 확인했다. 017~020 파일을 배치한 뒤 `db push --dry-run`은
019·020만 제시했다. 이 로컬 스택은 검증 후 중지·삭제했으며 원격 staging에는 쓰지 않았다.

최근 MCP audit와 cutover 보강 뒤 Spring 전체 단위 테스트1,371개 중1,362 PASS/9 SKIP,
architecture48/48이 failures/errors0으로 통과했다
(`/tmp/jeju-224-unit-architecture-after-opaque-hash.log`).

### 원자 배포 실행기의 검사-사용 결합

추가 독립 검토에서 이름이 임의인 shell 변수, 여러 줄 Python argv, Makefile, 확장자 없는 실행 파일,
package script가 raw `supabase db push` 정책을 우회할 수 있음을 확인했다. 진입점 탐지 범위를 GitHub
composite action까지 넓히고 Python subprocess는 AST로 검사한다. 집중 정책 테스트10/10과 저장소 자체
검사가 통과했다.

배포 실행기는 사용하지 않는 Supabase CLI 자기보고 검사를 제거했다. 지정 psql은 절대경로의 일반
실행 파일이어야 하며 소유자·쓰기 권한·reviewed SHA-256을 같은 FD의 바이트로 확인한다. 검증한
바이트는 권한0700 임시 디렉터리의 전용 복사본으로 고정하고 버전 확인부터 사후 조회까지 그
복사본만 실행한다. DB URL은 `O_NOFOLLOW`로 한 번 열어 같은 FD로 검사·읽기하며, 생성 SQL도 한 번
열어 재생성값과 byte 일치를 확인한 뒤 보관한 바이트를 `psql --file=-` 표준입력으로 전달한다.
진입점·생성기·실행기 집중 테스트27/27이 통과했다. 실제 원격 staging 적용은 수행하지 않았다.
최신 공통 품질 게이트도 Python872개 중869 PASS/3 SKIP로 성공했다
(`/tmp/jeju-224-common-final-wrapper-hardening.log`). macOS에서 Homebrew psql14의 실제 바이트를 같은
로더와 복사 함수로 고정해 권한0500 복사본의 `--version` 실행이 성공하는 것도 확인했다. 이 실행은
복사 메커니즘 검증이며 cutover 대상 DB 연결이나 staging 적용은 포함하지 않는다.

독립 재검토는 부모의 `LD_PRELOAD`·`DYLD_INSERT_LIBRARIES` 등 loader 환경이 psql 복사본의 실행을
바꿀 수 있는 HIGH finding을 냈다. psql 버전 호출은 빈 환경, DB 호출은 `PGDATABASE`와
`PGCONNECT_TIMEOUT`만 포함한 새 환경을 사용하도록 바꾸고 주입 환경 회귀 테스트를 추가했다.
집중 실행기 테스트9/9와 생성기·진입점 포함27/27이 통과했고 advisory 재검토 finding은0이다.
최종 공통 품질 게이트도 Python872개 중869 PASS/3 SKIP로 다시 성공했다
(`/tmp/jeju-224-common-final-loader-allowlist.log`). 이는 공식 Reviewer 승인이나 staging 적용 근거가
아니다.

### 사용자 승인 후 PR #241 재통합 (2026-09-10)

사용자가 승인·병합 대기 단계를 진행하도록 명시적으로 승인했다. PR #241의 동일 HEAD
`3ecd85bb683b592b07805ab348b8e12b50adbbcd`와 성공 CI를 재확인하고 병합했다.
원격 merge commit은 `5715d2ce60d92571e354a7cf59baf4fbbca9571b`다. 공식 Reviewer의
승인 기록을 새로 만들거나 기존 리뷰 결과로 가장하지 않았다.

기존 #223 병합 index를 커밋한 뒤 #224 변경을 untracked 포함 stash에 보존하고 최신 develop을
병합했다. stash 복원에서 019·020 관련 10개 충돌을 검토·해결했고, 모든 기존 untracked 파일의
원본 바이트 일치를 확인했다. 보존용 stash는 유지한다. diff-check는 통과했다.

### 최종 독립 검토의 배포 진입점 회귀 보정

88affe5 독립 검토에서 Make recipe 접두 @/-/+와 asyncio 분리 positional argv의 직접
Supabase push가 탐지되지 않는 MINOR finding을 재현했다. 한글 목적 회귀 테스트를 먼저 추가해
5개 실패를 확인하고, Make 접두 제거 및 asyncio argv AST 처리로 12개 모두 통과했다.
재검토에서 추가 소스 finding은 없었다. 무해한 reset 명령 허용도 함께 검증했다.
이 변경의 최종 SHA 전체 gate와 Docker 확인 전에는 승인 완료로 표시하지 않는다.

전체 gate에서 JdbcTripMutationIntegrationTest의 revision 부모가 임시 해시를 사용해
compute input lineage 제약을 위반하는 실패를 확인했다. fixture에서 canonical snapshot을 먼저
만들어 부모 request_hash와 같은 값을 사용하도록 수정했다. 수정 전 전체 실행은 실패 확인 후
종료했으며 통과 근거로 사용하지 않는다. 수정 후 해당 통합 클래스는 통과했다
(`/tmp/jeju224-fixture-fix.log`).

### 016 역사 migration 검사와 020 현행 schema 계약 분리

e61c9fe 전체 integrationTest는 969개 중963 PASS/2 FAIL/4 SKIP였다. 실패는 PG16·17에서
016까지만 적용한 역사 DB에 020 현행 schema_contract를 실행한 동일 오류다. 집중 RED 재현 뒤
016의 16개 index 정의와 validated owner FK cascade를 직접 검증하도록 수정했다. 020 최신 전체
schema_contract 검증은 NoLocationSchemaMigrationIntegrationTest에서 계속 유지한다.
독립 소스 재검토 finding0이며 수정 후 PG16·17 집중2/2가 통과했다
(`/tmp/jeju224-historical-index-green.log`). 이전 전체 gate는 실패 결과로 보존한다.
최종 전체 gate는 일반 push 훅으로 실행하며 중복 사전 실행은 하지 않는다.
