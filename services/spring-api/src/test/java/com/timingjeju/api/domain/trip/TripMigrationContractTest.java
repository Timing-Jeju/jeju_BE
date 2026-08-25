package com.timingjeju.api.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripMigrationContractTest {
  private static final String MIGRATION = "20260902000000_trip_create_contract.sql";

  @Test
  void append_only_migration은_timezone과_client_write_policy_제로를_고정한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertTripAccessContract(sql);
    assertThat(sql)
        .contains("add column timezone text not null default 'Asia/Seoul'")
        .contains("check (timezone = 'Asia/Seoul')");
  }

  @Test
  void client_write_policy_제로_guard는_policy_재도입_변이를_거부한다() {
    String mutated =
        """
        revoke all on table public.trip_plans, public.trip_transport_modes, public.trip_days
        from anon, authenticated;
        grant select, insert, update, delete on table
          public.trip_plans, public.trip_transport_modes, public.trip_days
        to service_role;
        revoke truncate, references, trigger on table
          public.trip_plans, public.trip_transport_modes, public.trip_days
        from service_role;
        create policy trip_plans_owner_insert on public.trip_plans for insert to authenticated
        with check (true);
        """;

    assertThatThrownBy(() -> assertTripAccessContract(mutated)).isInstanceOf(AssertionError.class);
  }

  @Test
  void smoke_check의_client_write_policy_제로_guard는_약화되지_않는다() throws Exception {
    String smoke = Files.readString(repositoryRoot().resolve("db/queries/smoke_check.sql"));

    assertThat(smoke)
        .contains("from pg_policies")
        .contains("and cmd <> 'SELECT'")
        .contains("client-write RLS policies must not exist; found %");
  }

  @Test
  void compose는_새_migration을_seed보다_먼저_031로_mount한다() throws Exception {
    assertMigrationMount(Files.readString(repositoryRoot().resolve("docker-compose.yml")));
    assertMigrationMount(Files.readString(repositoryRoot().resolve("compose.yml")));
    assertMigrationMount(Files.readString(repositoryRoot().resolve("compose.test.yml")));
  }

  @Test
  void compose_mount_guard는_030과_031의_순서_변이를_거부한다() {
    String mutated =
        """
        ./supabase/migrations/20260902000000_trip_create_contract.sql:/docker-entrypoint-initdb.d/031_trip_create_contract.sql:ro
        ./supabase/migrations/20260901000000_legal_documents_consents.sql:/docker-entrypoint-initdb.d/030_legal_documents_consents.sql:ro
        ./db/local-postgres/seed_fixtures.sql:/docker-entrypoint-initdb.d/099_seed_fixtures.sql:ro
        """;

    assertThatThrownBy(() -> assertMigrationMount(mutated)).isInstanceOf(AssertionError.class);
  }

  @Test
  void trip_cursor_key는_application_compose_env_example에_같은_이름으로_연결된다() throws Exception {
    Path root = repositoryRoot();

    assertThat(Files.readString(root.resolve(".env.example")))
        .contains("# 여행 cursor HMAC key입니다. 운영에서는 32자 이상의 무작위 비밀값을 주입합니다.")
        .contains("APP_TRIPS_CURSOR_SIGNING_KEY=");
    assertThat(Files.readString(root.resolve("compose.yml")))
        .contains("APP_TRIPS_CURSOR_SIGNING_KEY: ${APP_TRIPS_CURSOR_SIGNING_KEY:-}");
    assertThat(
            Files.readString(
                root.resolve("services/spring-api/src/main/resources/application.yml")))
        .contains(
            """
              trips:
                cursor-signing-key: ${APP_TRIPS_CURSOR_SIGNING_KEY:}
            """);
  }

  @Test
  void actualPG_score_fixture는_scheduleVersion과_item을_같은_trip_named_lineage로_고정한다()
      throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/JdbcTripScoreIntegrationTest.java"));

    assertThat(source)
        .contains("values (:versionId, :tripId, 1, 'draft', 'initial')")
        .contains(".addValue(\"versionId\", VERSION)")
        .contains(".addValue(\"tripId\", TRIP)")
        .contains("values (:itemId, :tripId, :dayId, :versionId, 1, 'custom'")
        .contains("where id = :versionId and trip_plan_id = :tripId");
  }

  @Test
  void actualPG_RLS_fixture는_임시_GRANT나_policy를_만들지_않고_client_DML_ACL을_검증한다() throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/TripRlsIntegrationTest.java"));

    assertThat(source)
        .contains("assertClientMutationDenied(")
        .contains("assertTripAggregateUnchanged(")
        .contains("has_table_privilege(?, 'public.' || ?, ?)")
        .doesNotContain("grant select, insert, update, delete")
        .doesNotContain("grant select on table")
        .doesNotContain("create policy");
  }

  @Test
  void actualPG_RLS_fixture는_client_직접_DML_거부후_aggregate_불변을_검증한다() throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/TripRlsIntegrationTest.java"));

    assertThat(source)
        .contains("for (String role : List.of(\"anon\", \"authenticated\"))")
        .contains("assertThat(failure.getSQLState()).isEqualTo(\"42501\")")
        .contains("select count(*) from public.trip_days where trip_plan_id = ?")
        .contains("select count(*) from public.trip_transport_modes where trip_plan_id = ?")
        .contains("select xmin::text from public.trip_plans where id = ?")
        .doesNotContain("TRIGGER_ORDERED_CHILD_DENIAL_STATES")
        .doesNotContain("auth.uid()")
        .doesNotContain("request.jwt.claim.sub");
  }

  private static void assertMigrationMount(String compose) {
    String legalMount =
        "./supabase/migrations/20260901000000_legal_documents_consents.sql:/docker-entrypoint-initdb.d/030_legal_documents_consents.sql:ro";
    String tripMount =
        "./supabase/migrations/"
            + MIGRATION
            + ":/docker-entrypoint-initdb.d/031_trip_create_contract.sql:ro";
    String fixtureMount =
        "./db/local-postgres/seed_fixtures.sql:/docker-entrypoint-initdb.d/099_seed_fixtures.sql:ro";

    assertThat(compose).contains(legalMount).contains(tripMount).contains(fixtureMount);
    assertThat(compose.indexOf(legalMount)).isLessThan(compose.indexOf(tripMount));
    assertThat(compose.indexOf(tripMount)).isLessThan(compose.indexOf(fixtureMount));
  }

  private static void assertTripAccessContract(String sql) {
    assertThat(sql)
        .contains("revoke all on table")
        .contains("from anon, authenticated")
        .contains("grant select, insert, update, delete on table")
        .contains("to service_role")
        .contains("revoke truncate, references, trigger on table")
        .doesNotContain("create policy")
        .doesNotContain("trip_plans_owner_insert")
        .doesNotContain("trip_plans_owner_update")
        .doesNotContain("trip_plans_owner_delete")
        .doesNotContain("trip_transport_modes_owner_insert")
        .doesNotContain("trip_transport_modes_owner_update")
        .doesNotContain("trip_transport_modes_owner_delete")
        .doesNotContain("trip_days_owner_insert")
        .doesNotContain("trip_days_owner_update")
        .doesNotContain("trip_days_owner_delete");
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
