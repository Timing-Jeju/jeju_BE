package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PostgreSqlConcurrencyInventoryTest {
  private static final String PUSH =
      "com/timingjeju/api/global/notification/JdbcPushNotificationStoreIntegrationTest.java";
  private static final String IDEMPOTENCY =
      "com/timingjeju/api/global/idempotency/JdbcIdempotencyRecordRepositoryIntegrationTest.java";
  private static final Set<String> HIKARI_TWO =
      Set.of(
          "com/timingjeju/api/domain/accommodation/adapter/JdbcAccommodationConcurrencyIntegrationTest.java",
          "com/timingjeju/api/domain/savedplaces/repository/JdbcSavedPlaceRepositoryIntegrationTest.java",
          "com/timingjeju/api/domain/schedule/repository/JdbcScheduleMutationStoreIntegrationTest.java",
          "com/timingjeju/api/domain/transportevent/adapter/JdbcTransportEventConcurrencyIntegrationTest.java",
          "com/timingjeju/api/domain/trip/adapter/JdbcTripDetailProjectionIntegrationTest.java",
          "com/timingjeju/api/domain/trip/adapter/JdbcTripMutationIntegrationTest.java",
          "com/timingjeju/api/domain/trip/adapter/JdbcTripPlacePreferencesConcurrencyIntegrationTest.java",
          "com/timingjeju/api/domain/trip/adapter/JdbcTripPreferencesPostgreSqlIntegrationTest.java",
          "com/timingjeju/api/global/asyncrun/JdbcRunLeaseRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/importing/JdbcImportCheckpointRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/importing/JdbcImportRunStoreIntegrationTest.java",
          "com/timingjeju/api/global/kma/JdbcKmaWeatherRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/legal/JdbcLegalConsentStoreIntegrationTest.java",
          "com/timingjeju/api/global/placestop/JdbcPlaceStopLinkRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/profile/JdbcProfileProvisioningStoreIntegrationTest.java",
          "com/timingjeju/api/global/retention/JdbcSnapshotRetentionRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/snapshot/JdbcSnapshotStoreIntegrationTest.java",
          "com/timingjeju/api/global/staypolicy/JdbcStayPolicyRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tago/arrival/JdbcTagoArrivalFlightStoreIntegrationTest.java",
          "com/timingjeju/api/global/tago/arrival/JdbcTagoArrivalRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tago/route/JdbcRouteRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tago/stop/JdbcTagoStopRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/detailitem/JdbcDetailItemRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/image/JdbcPlaceImageRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/place/JdbcPlaceListRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/reference/JdbcReferenceCodeRepositoryIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/reference/ReferenceCodeSyncServiceIntegrationTest.java",
          "com/timingjeju/api/global/tourapi/sync/TransactionalIncrementalSyncCommitterIntegrationTest.java",
          "com/timingjeju/api/support/postgresql/ScheduleRevisionRunSchemaIntegrationTest.java");
  private static final Pattern CONNECTION_BEFORE_EXECUTOR =
      Pattern.compile(
          "(?s)try\\s*\\(Connection\\s+\\w+\\s*=\\s*[^;]+;\\s*var\\s+\\w+\\s*=\\s*Executors\\.");

  @Test
  void 모든_Spring_PostgreSQL_동시성_테스트가_연결_예산_contract에_등록된다() throws Exception {
    Set<String> declared = new LinkedHashSet<>(HIKARI_TWO);
    declared.add(IDEMPOTENCY);
    declared.add(PUSH);

    assertThat(discoverConcurrencyTests()).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(HIKARI_TWO).hasSize(29);
  }

  @Test
  void 최대_Hikari3과_context24는_non_context여유를_포함해_가용연결보다_작다() throws Exception {
    Path repository = PostgreSqlTestContainerFactory.locateRepositoryRoot();
    String build = Files.readString(repository.resolve("services/spring-api/build.gradle"));
    String application =
        Files.readString(
            repository.resolve("services/spring-api/src/test/resources/application.yml"));

    assertThat(application).contains("maximum-pool-size: 3").contains("minimum-idle: 0");
    assertThat(build).contains("systemProperty 'spring.test.context.cache.maxSize', '24'");
    assertThat(PostgreSqlTestConnectionBudget.CACHED_HIKARI_CONNECTIONS).isEqualTo(72);
    assertThat(
            PostgreSqlTestConnectionBudget.BUDGET_TEST_MANUAL_CONTEXT_CONNECTIONS
                + PostgreSqlTestConnectionBudget.BUDGET_TEST_PRECEDING_CONNECTIONS
                + PostgreSqlTestConnectionBudget.LAUNCHER_ADMIN_CONNECTIONS)
        .isLessThanOrEqualTo(PostgreSqlTestConnectionBudget.NON_CONTEXT_RESERVE);
    assertThat(PostgreSqlTestConnectionBudget.NON_CONTEXT_RESERVE).isEqualTo(16);
    assertThat(PostgreSqlTestConnectionBudget.TEST_CONNECTION_BUDGET).isEqualTo(88);
    assertThat(PostgreSqlTestConnectionBudget.TEST_CONNECTION_BUDGET)
        .isLessThanOrEqualTo(PostgreSqlTestConnectionBudget.SAFETY_CEILING);
    assertThat(PostgreSqlTestConnectionBudget.SAFETY_CEILING)
        .isLessThan(PostgreSqlTestConnectionBudget.POSTGRES_USABLE_CONNECTIONS);
  }

  @Test
  void connection_lock은_executor보다_먼저_정리되도록_resource순서를_지킨다() throws Exception {
    Path sources = testSources();
    List<String> unsafe = new ArrayList<>();
    for (String relative : discoverConcurrencyTests()) {
      if (CONNECTION_BEFORE_EXECUTOR.matcher(Files.readString(sources.resolve(relative))).find()) {
        unsafe.add(relative);
      }
    }

    assertThat(unsafe).isEmpty();
  }

  private static Set<String> discoverConcurrencyTests() throws Exception {
    Path sources = testSources();
    Set<String> discovered = new LinkedHashSet<>();
    try (var files = Files.walk(sources)) {
      for (Path file :
          files.filter(path -> path.toString().endsWith("IntegrationTest.java")).toList()) {
        String source = Files.readString(file);
        boolean springPostgreSql =
            source.contains("PostgreSqlTestcontainersConfiguration")
                || source.contains("PostgreSqlRepositoryIntegrationTestSupport");
        String normalized = source.toLowerCase(java.util.Locale.ROOT);
        boolean concurrent =
            source.contains("Executors.")
                || source.contains("CountDownLatch")
                || source.contains("pg_advisory")
                || normalized.contains("lock table")
                || normalized.contains("for update");
        if (springPostgreSql && concurrent) {
          discovered.add(sources.relativize(file).toString());
        }
      }
    }
    return discovered;
  }

  private static Path testSources() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("services/spring-api/src/test/java");
  }
}
