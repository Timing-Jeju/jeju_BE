# Issue 247 ARM PostGIS 통합 테스트 컨테이너 누적 timeout 제거

## 문제와 Red

- 기준은 `origin/develop`의 `73cb2b9310ecb2eba5a931459a78229cd06e7028`이며 작업 브랜치는 `test/247-arm-postgis-container-timeout`이다.
- Apple Silicon에서 기존 full `integrationTest`는 case/context별 amd64 PostGIS 시작이 누적됐다. 소스 인벤토리의 보수적 물리 컨테이너 시작 상한은 direct migration 약 54회와 Spring `@ServiceConnection` 약 59회를 합친 약 113회였다.
- 최초 2시간 watchdog 실행은 exit 126으로 끝났고, 후속 진단 실행도 약 95분 뒤 외부 중단됐다. 후속 manifest는 `issue247-full-integration-20260910T063353983030Z-82205/manifest.json`이며 `rootSuiteComplete=false`, `reason=interrupted`다. 마지막 thread dump는 `SavedPlacesMigrationIntegrationTest.startAtPreviousSchemaThenApplyTarget`의 `PostgreSQLContainer.start()` readiness 대기였다. 따라서 이 실행은 Green 증거로 사용하지 않는다.
- 공통 생성자 인벤토리 회귀 테스트를 먼저 추가해 factory의 raw `new PostgreSQLContainer(...)`와 공통 pool 부재로 2건 실패하는 Red를 확인했다.
- 별도 focused 실행에서 `LocationDataPurgeMigrationIntegrationTest`의 PG16/17 70건은 두 번 통과했으므로 migration assertion 자체보다 물리 컨테이너 lifecycle 누적을 원인으로 고정했다.
- Reviewer full gate는 물리 PG16/17을 image당 1회만 시작했지만, 서로 다른 Spring TestContext가 기본 Hikari 설정(최대 10, minimum idle 미지정)을 유지해 PG16 연결이 46→66→96/100으로 증가했다. `Accommodation`, `CanonicalMigration`, `CommandInputSnapshot`, `LocationDataPurge` 등에서 `FATAL: sorry, too many clients already`가 발생해 중단됐으며 manifest는 `integrationTest-20260910T083338568158Z-79303/manifest.json`이다.
- multi-context Red는 실제 profile binding에서 context 4개의 maximum pool이 `[10, 10, 10, 10]`, minimum idle이 `[-1, -1, -1, -1]`임을 재현했다. 즉 공유 물리 컨테이너가 아니라 cache에 유지된 Spring context별 기본 Hikari 예산이 연결 고갈 원인이다.

## Green 구현

- `PostgreSqlLauncherSessionPool`이 JUnit launcher session 동안 image별 인증 전용 물리 PostGIS를 한 번만 시작한다. 현재 전체 matrix는 PG16/17 두 image이므로 물리 시작 상한은 약 113회에서 2회로 제한된다.
- migration path prefix별 template database를 한 번 만들고, 각 test case/context는 UUID database를 template에서 clone한다. case 종료 시 연결을 강제 종료한 뒤 `DROP DATABASE ... WITH (FORCE)`로 schema/data/migration ledger를 격리한다.
- `PostgreSqlTestContainerFactory`와 Spring `@ServiceConnection`은 모두 공통 pooled handle을 사용한다. raw `PostgreSQLContainer` 생성 위치는 인벤토리 테스트로 공통 pool 한 곳만 허용한다.
- `LauncherSessionListener`가 launcher session 종료 시 image pool을 역순 종료한다. 개별 Spring context가 그 뒤 handle을 정리해도 멱등적으로 안전하다.
- 물리 데이터 디렉터리는 tmpfs를 사용해 익명 Docker volume 생성을 피한다. 시작 실패는 image/container/session/elapsed/최근 log를 포함해 다시 던지고 실패한 컨테이너를 즉시 stop한다.
- 기존 `ImageResourcePool`과 PG16/17 `PostgreSqlImageFixturePool`은 병렬 중복 시작을 막고, 한 리소스의 cleanup 실패에도 나머지 cleanup을 계속하며 suppressed cause를 보존한다.
- 테스트 datasource의 Hikari는 context당 최대 2, minimum idle 0으로 제한했다. integration TestContext cache도 24로 제한해 cached-context 이론 상한은 48 connections이고, PostgreSQL 기본 100 connections 중 template/cleanup/direct migration을 위한 52 connections를 남긴다. 운영 datasource와 PostgreSQL `max_connections`는 변경하지 않았다.
- `pg_stat_activity` 계측은 Spring context 4개가 동시에 연결 2개씩 점유할 때 peak 8, 모든 context close 직후 0을 확인하고 replacement context의 신규 쿼리 성공까지 검증한다.
- 기존 PG16/17 parameter matrix 70건, migration assertion, timeout/connection termination/lock rollback case는 제거하거나 skip하지 않았고 startup timeout도 늘리지 않았다.

## 검증 기록

- Red: `PostgreSqlContainerInventoryTest` 2건 실패 후 공통 pool 적용으로 Green.
- `./gradlew --no-daemon spotlessApply spotlessCheck compileTestJava unitTest`: 성공(Gradle 1분 15초). 전체 1,360건 중 기존 OS 전용 9건만 skip이며 failures/errors 0이다. connection/cache budget 인벤토리도 포함한다.
- `PostgreSqlSpringContextConnectionBudgetIntegrationTest`: Red 후 최종 Green 1건, XML 50.498초(Gradle 1분 7초). 실제 pooled PG16에서 context 4개 peak 8, close 후 0, replacement context 성공과 cache 24/Hikari 2·0 설정을 확인했다.
- `PostgreSqlLauncherSessionPoolIntegrationTest`: 2건 성공, failures/errors/skipped 0, XML 46.959초(Gradle 1분 22초). PG17과 다른 prefix를 먼저 실행해도 PG16/17 실제 physical start가 각각 1회이며, 같은 prefix template identity 재사용, 다른 prefix 분리, case DB 격리를 확인했다. 각 테스트는 실행 순서와 무관하게 필요한 handle을 직접 열며 전역 절대 개수 대신 baseline delta와 identity를 사용한다.
- `PostgreSqlRepositoryTestHarnessTest`: 5건 성공, failures/errors/skipped 0, XML 총 51.996초. Spring `@ServiceConnection`, canonical schema, transaction rollback 및 Hikari 종료를 검증했다.
- `SavedPlacesMigrationIntegrationTest`: 3건 성공, failures/errors/skipped 0, XML 총 17.364초. direct migration과 외부 SQL copy/exec 위임 경로를 검증했다. 두 대표 클래스의 최종 묶음 실행은 Gradle 1분 25초에 성공했다.
- `LocationDataPurgeMigrationIntegrationTest`: PG16/17 70건을 연속 두 번 통과했다. 보존된 최신 신뢰 XML은 `2026-09-10T04:45:36.655Z`, 총 886.765초, failures/errors/skipped 0이다. 이후 중복 Gradle 프로세스와 손상된 binary result가 겹친 EOF 실행은 Green 증거에서 제외했다.
- 정적 점검: `git diff --check` 성공, raw constructor는 공통 pool 한 곳만 남았으며 새 테스트에 `@Disabled`/`@Ignore`/assumption skip이 없다.
- Docker 종료 점검: Issue 247/Testcontainers 컨테이너 0개, 새 익명 volume 0개다. 중단된 full 실행이 남겼던 컨테이너와 10개 volume은 JVM 종료 뒤 Ryuk이 회수했다. 기존 Timing Jeju live demo와 FaithLog 리소스, 기존 익명 volume 2개는 변경하지 않았다.

## 남은 전체 게이트

- Reviewer가 1회 실행한 full gate는 Hikari 연결 고갈을 발견한 뒤 중단됐고 Green이 아니다. 수정 뒤에는 지시에 따라 full `integrationTest`, 별도 watchdog, `cleanIntegrationTest`, Docker smoke를 다시 실행하지 않았다.
- 수정 뒤 실제 full wall time은 아직 측정하지 않았다. Reviewer 실행이 연결 고갈 지점까지 약 23분 진행된 점과 대표 테스트 시간을 기준으로 다음 독립 quality gate의 예상 소요는 30~45분이다. 이 수치는 추정치이며 CI/호스트 부하에 따라 달라진다.
- push, PR, merge, 운영 DB 및 배포 작업은 수행하지 않는다.
