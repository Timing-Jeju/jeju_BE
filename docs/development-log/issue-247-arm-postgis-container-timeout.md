# Issue 247 ARM PostGIS 통합 테스트 컨테이너 누적 timeout 제거

## 문제와 Red

- 기준은 `origin/develop`의 `73cb2b9310ecb2eba5a931459a78229cd06e7028`이며 작업 브랜치는 `test/247-arm-postgis-container-timeout`이다.
- Apple Silicon에서 기존 full `integrationTest`는 case/context별 amd64 PostGIS 시작이 누적됐다. 소스 인벤토리의 보수적 물리 컨테이너 시작 상한은 direct migration 약 54회와 Spring `@ServiceConnection` 약 59회를 합친 약 113회였다.
- 최초 2시간 watchdog 실행은 exit 126으로 끝났고, 후속 진단 실행도 약 95분 뒤 외부 중단됐다. 후속 manifest는 `issue247-full-integration-20260910T063353983030Z-82205/manifest.json`이며 `rootSuiteComplete=false`, `reason=interrupted`다. 마지막 thread dump는 `SavedPlacesMigrationIntegrationTest.startAtPreviousSchemaThenApplyTarget`의 `PostgreSQLContainer.start()` readiness 대기였다. 따라서 이 실행은 Green 증거로 사용하지 않는다.
- 공통 생성자 인벤토리 회귀 테스트를 먼저 추가해 factory의 raw `new PostgreSQLContainer(...)`와 공통 pool 부재로 2건 실패하는 Red를 확인했다.
- 별도 focused 실행에서 `LocationDataPurgeMigrationIntegrationTest`의 PG16/17 70건은 두 번 통과했으므로 migration assertion 자체보다 물리 컨테이너 lifecycle 누적을 원인으로 고정했다.
- Reviewer full gate는 물리 PG16/17을 image당 1회만 시작했지만, 서로 다른 Spring TestContext가 기본 Hikari 설정(최대 10, minimum idle 미지정)을 유지해 PG16 연결이 46→66→96/100으로 증가했다. `Accommodation`, `CanonicalMigration`, `CommandInputSnapshot`, `LocationDataPurge` 등에서 `FATAL: sorry, too many clients already`가 발생해 중단됐으며 manifest는 `integrationTest-20260910T083338568158Z-79303/manifest.json`이다.
- multi-context Red는 실제 profile binding에서 context 4개의 maximum pool이 `[10, 10, 10, 10]`, minimum idle이 `[-1, -1, -1, -1]`임을 재현했다. 즉 공유 물리 컨테이너가 아니라 cache에 유지된 Spring context별 기본 Hikari 예산이 연결 고갈 원인이다.
- Reviewer 재검증은 peak 15/100에서 `JdbcIdempotencyRecordRepositoryIntegrationTest`의 blocker와 loser가 Hikari 2개를 점유한 뒤 main control/release가 세 번째 연결을 기다리는 교착을 찾았다. 예외 정리도 executor가 blocker보다 먼저 닫혀 waiter를 영구 대기했다. thread 증거는 `reviewer-247-9864db94-threads.json`이다.
- connection-budget 선행-context Red는 다른 cached context 연결 1개를 유지하자 기존 session-wide `close=0` 단언이 실제 1로 실패해 테스트 순서 의존성을 재현했다.
- 세 번째 Reviewer Red는 unrelated cached context를 두 개로 늘리자 own close 뒤 session 절대값 단언이 `expected 1, but was 2`로 실패해 남은 순서 의존성을 재현했다.
- 최신 Reviewer gate는 `JdbcPushNotificationStoreIntegrationTest`에서 writer가 `legal_documents`의 ACCESS EXCLUSIVE lock을 보유하고 reader가 대기하는 동안 Hikari 2개가 모두 점유되어, 차단 상태를 조회할 세 번째 연결이 pool에서 대기하는 교착을 재현했다. 이때 resource 선언 순서 때문에 timeout 뒤 executor가 writer connection보다 먼저 닫히며 lock 해제 전에 무한 대기할 수 있었다. 전체 PG peak는 20/100이므로 서버 연결 고갈이 아니라 class-local 최소 동시 연결 수가 3인 문제다.
- 30개 Spring PostgreSQL concurrency test 전체를 먼저 inventory했다. 최초 contract Red는 Push와 StayPolicy 두 class의 `Connection → executor` resource 순서를 정확히 검출했고, test profile의 Hikari 2가 요구한 최소값 3과 다름을 검출했다.

## Green 구현

- `PostgreSqlLauncherSessionPool`이 JUnit launcher session 동안 image별 인증 전용 물리 PostGIS를 한 번만 시작한다. 현재 전체 matrix는 PG16/17 두 image이므로 물리 시작 상한은 약 113회에서 2회로 제한된다.
- migration path prefix별 template database를 한 번 만들고, 각 test case/context는 UUID database를 template에서 clone한다. case 종료 시 연결을 강제 종료한 뒤 `DROP DATABASE ... WITH (FORCE)`로 schema/data/migration ledger를 격리한다.
- `PostgreSqlTestContainerFactory`와 Spring `@ServiceConnection`은 모두 공통 pooled handle을 사용한다. raw `PostgreSQLContainer` 생성 위치는 인벤토리 테스트로 공통 pool 한 곳만 허용한다.
- `LauncherSessionListener`가 launcher session 종료 시 image pool을 역순 종료한다. 개별 Spring context가 그 뒤 handle을 정리해도 멱등적으로 안전하다.
- 물리 데이터 디렉터리는 tmpfs를 사용해 익명 Docker volume 생성을 피한다. 시작 실패는 image/container/session/elapsed/최근 log를 포함해 다시 던지고 실패한 컨테이너를 즉시 stop한다.
- 기존 `ImageResourcePool`과 PG16/17 `PostgreSqlImageFixturePool`은 병렬 중복 시작을 막고, 한 리소스의 cleanup 실패에도 나머지 cleanup을 계속하며 suppressed cause를 보존한다.
- 테스트 datasource의 Hikari는 최소 안전값인 context당 최대 3, minimum idle 0으로 제한했다. integration TestContext cache 24와 결합한 cached 상한은 72 connections다. budget test manual context 12, preceding held connection 2, launcher admin 1을 포함하도록 non-context reserve를 16으로 반올림해 총 test budget을 88로 고정했다. 이 값은 safety ceiling 90 이하이며 PostgreSQL normal-user 가용 97보다 작다. 운영 datasource, PostgreSQL `max_connections`, timeout은 변경하지 않았다.
- `pg_stat_activity` 계측은 Spring context 4개가 동시에 연결 3개씩 점유할 때 own peak 12, 모든 own context close 직후 0을 확인한다. unrelated context 2개를 보존한 채 replacement context의 신규 쿼리 성공과 session cached budget 72 이하도 검증한다.
- idempotency 동시성 harness의 advisory-lock blocker는 Hikari 밖의 단일 direct control connection으로 명시해 loser와 main release가 Hikari 2개 예산 안에서 진행한다. 정상·중간실패 모두 `unlock → statement/control connection close → executor close` 순서를 보장한다. cached Hikari 48에 동시성 control 1을 더한 전체 테스트 상한은 49이며 운영 설정, timeout, 전역 Hikari 2 및 PostgreSQL `max_connections`는 유지한다.
- connection 계측 API는 호출자가 소유한 정확한 case DB 이름 목록만 집계하는 값과 launcher session 전체 집계를 분리한다. unrelated context 2개를 시작 baseline으로 캡처하고 own peak 12/close 0 뒤 baseline이 그대로 보존되는지 확인하며 session cached 72 상한은 별도로 유지한다.
- concurrency inventory contract는 현재 30개 class를 `Hikari 2`, `idempotency Hikari 2 + direct control 1`, `Push Hikari 3` 범주로 명시한다. 새 concurrency class가 등록되지 않거나 connection이 executor보다 먼저 선언되어 cleanup 역순이 불안전하면 unit gate가 실패한다.
- Push snapshot harness는 executor를 먼저, writer connection을 나중에 선언하고 중간 실패 시 명시적으로 rollback해 lock/connection을 executor보다 먼저 정리한다. StayPolicy의 동일한 위험 순서도 같은 contract로 수정했다. Push에는 의도적 중간 실패가 5초 안에 반환되고 후속 eligibility query가 성공하는 회귀 테스트를 추가했다.
- 기존 PG16/17 parameter matrix 70건, migration assertion, timeout/connection termination/lock rollback case는 제거하거나 skip하지 않았고 startup timeout도 늘리지 않았다.

## 검증 기록

- Red: `PostgreSqlContainerInventoryTest` 2건 실패 후 공통 pool 적용으로 Green.
- `./gradlew --no-daemon spotlessApply spotlessCheck compileTestJava unitTest`: 성공(Gradle 1분 15초). 전체 1,360건 중 기존 OS 전용 9건만 skip이며 failures/errors 0이다. connection/cache budget 인벤토리도 포함한다.
- `PostgreSqlSpringContextConnectionBudgetIntegrationTest`: Red 후 최종 Green 1건, XML 50.498초(Gradle 1분 7초). 실제 pooled PG16에서 context 4개 peak 8, close 후 0, replacement context 성공과 cache 24/Hikari 2·0 설정을 확인했다.
- `PostgreSqlLauncherSessionPoolIntegrationTest`: 2건 성공, failures/errors/skipped 0, XML 46.959초(Gradle 1분 22초). PG17과 다른 prefix를 먼저 실행해도 PG16/17 실제 physical start가 각각 1회이며, 같은 prefix template identity 재사용, 다른 prefix 분리, case DB 격리를 확인했다. 각 테스트는 실행 순서와 무관하게 필요한 handle을 직접 열며 전역 절대 개수 대신 baseline delta와 identity를 사용한다.
- 두 번째 Reviewer 수정 뒤 `JdbcIdempotencyRecordRepositoryIntegrationTest`: 정상 race와 새 중간실패 cleanup 회귀를 포함한 12건 성공(Gradle 2분 43초). 첫 실행의 기존 2초 latch 실패는 재실행에서 재현되지 않았고, 교착 없이 Hikari shutdown까지 완료됐다.
- unrelated cached context 2개를 포함한 `PostgreSqlSpringContextConnectionBudgetIntegrationTest`: Red 후 1건 성공, failures/errors/skipped 0, XML 44.021초(Gradle 57초). own peak 8/close 0, unrelated baseline 2 보존, replacement context 성공, session 전체 48 이하를 확인했다.
- 두 번째 Reviewer 수정 뒤 PG16/17 `PostgreSqlLauncherSessionPoolIntegrationTest`: 2건 성공, failures/errors/skipped 0, XML 28.152초(Gradle 38초).
- 최신 concurrency inventory unit gate와 container inventory gate: 5건 성공(Gradle 8초). 30개 class 등록, cleanup 순서, Hikari 3/minimum idle 0, cache 24, cached 72 + reserve 16 = total 88, safety ceiling 90을 확인했다.
- `JdbcPushNotificationStoreIntegrationTest`: snapshot cleanup 회귀를 포함한 13건 성공, failures/errors/skipped 0, XML 52.954초(Gradle 57초). 기존 snapshot lock test도 세 번째 pool connection으로 차단 상태를 관찰한 뒤 정상 종료했다.
- `JdbcIdempotencyRecordRepositoryIntegrationTest`: 요청된 12건 성공, failures/errors/skipped 0, XML 47.447초(Gradle 51초).
- Hikari 3 기준 `PostgreSqlSpringContextConnectionBudgetIntegrationTest`: 1건 성공, failures/errors/skipped 0, XML 47.321초(Gradle 51초). own peak 예산 12, unrelated baseline 2, cached budget 72를 검증했다.
- 대표 PG16/17 `PostgreSqlLauncherSessionPoolIntegrationTest`: 2건 성공, failures/errors/skipped 0, XML 29.096초(Gradle 33초).
- `PostgreSqlRepositoryTestHarnessTest`: 5건 성공, failures/errors/skipped 0, XML 총 51.996초. Spring `@ServiceConnection`, canonical schema, transaction rollback 및 Hikari 종료를 검증했다.
- `SavedPlacesMigrationIntegrationTest`: 3건 성공, failures/errors/skipped 0, XML 총 17.364초. direct migration과 외부 SQL copy/exec 위임 경로를 검증했다. 두 대표 클래스의 최종 묶음 실행은 Gradle 1분 25초에 성공했다.
- `LocationDataPurgeMigrationIntegrationTest`: PG16/17 70건을 연속 두 번 통과했다. 보존된 최신 신뢰 XML은 `2026-09-10T04:45:36.655Z`, 총 886.765초, failures/errors/skipped 0이다. 이후 중복 Gradle 프로세스와 손상된 binary result가 겹친 EOF 실행은 Green 증거에서 제외했다.
- 정적 점검: `git diff --check` 성공, raw constructor는 공통 pool 한 곳만 남았으며 새 테스트에 `@Disabled`/`@Ignore`/assumption skip이 없다.
- Docker 종료 점검: Issue 247/Testcontainers 컨테이너 0개, 새 익명 volume 0개다. 중단된 full 실행이 남겼던 컨테이너와 10개 volume은 JVM 종료 뒤 Ryuk이 회수했다. 기존 Timing Jeju live demo와 FaithLog 리소스, 기존 익명 volume 2개는 변경하지 않았다.

## 남은 전체 게이트

- Reviewer가 1회 실행한 full gate는 Hikari 연결 고갈을 발견한 뒤 중단됐고 Green이 아니다. 수정 뒤에는 지시에 따라 full `integrationTest`, 별도 watchdog, `cleanIntegrationTest`, Docker smoke를 다시 실행하지 않았다.
- 수정 뒤 실제 full wall time은 아직 측정하지 않았다. Reviewer 실행이 연결 고갈 지점까지 약 23분 진행된 점과 대표 테스트 시간을 기준으로 다음 독립 quality gate의 예상 소요는 30~45분이다. 이 수치는 추정치이며 CI/호스트 부하에 따라 달라진다.
- push, PR, merge, 운영 DB 및 배포 작업은 수행하지 않는다.
