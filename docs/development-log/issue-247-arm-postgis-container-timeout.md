# Issue 247 ARM PostGIS 통합 테스트 컨테이너 누적 timeout 제거

## 문제와 Red

- 기준은 `origin/develop`의 `73cb2b9310ecb2eba5a931459a78229cd06e7028`이며 작업 브랜치는 `test/247-arm-postgis-container-timeout`이다.
- Apple Silicon에서 기존 full `integrationTest`는 case/context별 amd64 PostGIS 시작이 누적됐다. 소스 인벤토리의 보수적 물리 컨테이너 시작 상한은 direct migration 약 54회와 Spring `@ServiceConnection` 약 59회를 합친 약 113회였다.
- 최초 2시간 watchdog 실행은 exit 126으로 끝났고, 후속 진단 실행도 약 95분 뒤 외부 중단됐다. 후속 manifest는 `issue247-full-integration-20260910T063353983030Z-82205/manifest.json`이며 `rootSuiteComplete=false`, `reason=interrupted`다. 마지막 thread dump는 `SavedPlacesMigrationIntegrationTest.startAtPreviousSchemaThenApplyTarget`의 `PostgreSQLContainer.start()` readiness 대기였다. 따라서 이 실행은 Green 증거로 사용하지 않는다.
- 공통 생성자 인벤토리 회귀 테스트를 먼저 추가해 factory의 raw `new PostgreSQLContainer(...)`와 공통 pool 부재로 2건 실패하는 Red를 확인했다.
- 별도 focused 실행에서 `LocationDataPurgeMigrationIntegrationTest`의 PG16/17 70건은 두 번 통과했으므로 migration assertion 자체보다 물리 컨테이너 lifecycle 누적을 원인으로 고정했다.

## Green 구현

- `PostgreSqlLauncherSessionPool`이 JUnit launcher session 동안 image별 인증 전용 물리 PostGIS를 한 번만 시작한다. 현재 전체 matrix는 PG16/17 두 image이므로 물리 시작 상한은 약 113회에서 2회로 제한된다.
- migration path prefix별 template database를 한 번 만들고, 각 test case/context는 UUID database를 template에서 clone한다. case 종료 시 연결을 강제 종료한 뒤 `DROP DATABASE ... WITH (FORCE)`로 schema/data/migration ledger를 격리한다.
- `PostgreSqlTestContainerFactory`와 Spring `@ServiceConnection`은 모두 공통 pooled handle을 사용한다. raw `PostgreSQLContainer` 생성 위치는 인벤토리 테스트로 공통 pool 한 곳만 허용한다.
- `LauncherSessionListener`가 launcher session 종료 시 image pool을 역순 종료한다. 개별 Spring context가 그 뒤 handle을 정리해도 멱등적으로 안전하다.
- 물리 데이터 디렉터리는 tmpfs를 사용해 익명 Docker volume 생성을 피한다. 시작 실패는 image/container/session/elapsed/최근 log를 포함해 다시 던지고 실패한 컨테이너를 즉시 stop한다.
- 기존 `ImageResourcePool`과 PG16/17 `PostgreSqlImageFixturePool`은 병렬 중복 시작을 막고, 한 리소스의 cleanup 실패에도 나머지 cleanup을 계속하며 suppressed cause를 보존한다.
- 기존 PG16/17 parameter matrix 70건, migration assertion, timeout/connection termination/lock rollback case는 제거하거나 skip하지 않았고 startup timeout도 늘리지 않았다.

## 검증 기록

- Red: `PostgreSqlContainerInventoryTest` 2건 실패 후 공통 pool 적용으로 Green.
- `./gradlew --no-daemon spotlessApply spotlessCheck compileTestJava unitTest`: 성공. 최신 unit XML에서 `ImageResourcePoolTest` 5건, `PostgreSqlContainerInventoryTest` 2건, `PostgreSqlTestContainerFactoryTest` 3건 모두 failures/errors/skipped 0.
- `PostgreSqlLauncherSessionPoolIntegrationTest`: 성공(Gradle 1분 45초). 동일 PG16 image의 병렬 handle 2개와 다른 migration prefix handle 1개가 물리 컨테이너 1개, template 2개, 격리 case database 3개를 사용함을 확인했다.
- `PostgreSqlRepositoryTestHarnessTest`: 5건 성공(최종 Gradle 2분 26초). Spring `@ServiceConnection`, canonical schema 및 launcher/session 종료 순서를 검증했다.
- `SavedPlacesMigrationIntegrationTest`: 3건 성공, failures/errors/skipped 0, XML 총 81.394초. 과거 full 실행의 마지막 관측 지점과 외부 SQL copy/exec 위임 경로를 검증했다.
- `LocationDataPurgeMigrationIntegrationTest`: PG16/17 70건을 연속 두 번 통과했다. 보존된 최신 신뢰 XML은 `2026-09-10T04:45:36.655Z`, 총 886.765초, failures/errors/skipped 0이다. 이후 중복 Gradle 프로세스와 손상된 binary result가 겹친 EOF 실행은 Green 증거에서 제외했다.
- 정적 점검: `git diff --check` 성공, raw constructor는 공통 pool 한 곳만 남았으며 새 테스트에 `@Disabled`/`@Ignore`/assumption skip이 없다.
- Docker 종료 점검: Issue 247/Testcontainers 컨테이너 0개, 새 익명 volume 0개다. 중단된 full 실행이 남겼던 컨테이너와 10개 volume은 JVM 종료 뒤 Ryuk이 회수했다. 기존 Timing Jeju live demo와 FaithLog 리소스, 기존 익명 volume 2개는 변경하지 않았다.

## 남은 전체 게이트

- 사용자 지시에 따라 공통 pool 구현 뒤 full `integrationTest`, 별도 watchdog, `cleanIntegrationTest`, Docker smoke는 실행하지 않았다.
- 실제 full wall time은 아직 측정하지 않았다. 대표 PG16 시작 약 25초와 약 111회의 중복 시작 제거를 기준으로 다음 독립 quality gate의 예상 소요는 25~40분이다. 이 수치는 추정치이며 CI/호스트 부하에 따라 달라진다.
- push, PR, merge, 운영 DB 및 배포 작업은 수행하지 않는다.
