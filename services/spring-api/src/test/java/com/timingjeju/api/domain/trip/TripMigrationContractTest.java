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
  void append_only_migration은_timezone과_owner_create_RLS를_고정한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("add column timezone text not null default 'Asia/Seoul'")
        .contains("check (timezone = 'Asia/Seoul')")
        .contains("revoke all on table")
        .contains("from anon, authenticated")
        .doesNotContain("grant select, insert, update, delete on table")
        .contains("trip_plans_owner_insert")
        .contains("user_id = (select auth.uid())")
        .contains("trip_transport_modes_owner_insert")
        .contains("trip_days_owner_insert")
        .contains("trip_plans_owner_update")
        .contains("trip_plans_owner_delete")
        .contains("trip_transport_modes_owner_update")
        .contains("trip_transport_modes_owner_delete")
        .contains("trip_days_owner_update")
        .contains("trip_days_owner_delete")
        .contains("public.owns_trip_plan(trip_plan_id)");
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
  void actualPG_RLS_fixture의_trigger_dependency권한은_transaction_local_SELECT후_폐쇄된다()
      throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/TripRlsIntegrationTest.java"));

    assertThat(source)
        .contains(
            "grant select on table public.trip_schedule_versions, public.trip_items to authenticated")
        .contains("List.of(\"trip_schedule_versions\", \"trip_items\")")
        .contains("'public.' || ?, 'SELECT'")
        .doesNotContain(
            "grant insert, update, delete on table public.trip_schedule_versions, public.trip_items");
  }

  @Test
  void actualPG_RLS_fixture는_trigger_ordering_거부만_허용하고_savepoint후_불변을_검증한다() throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/TripRlsIntegrationTest.java"));

    assertThat(source)
        .contains("TRIGGER_ORDERED_CHILD_DENIAL_STATES = Set.of(\"42501\", \"P0001\")")
        .contains("assertTriggerOrderedChildInsertDenied(")
        .contains("assertOtherTripUnchanged(connection, otherTripXmin)")
        .contains("select count(*) from public.trip_days where trip_plan_id = ?")
        .contains("select count(*) from public.trip_transport_modes where trip_plan_id = ?")
        .contains("select xmin::text from public.trip_plans where id = ?")
        .contains(".doesNotContain(OTHER.toString())")
        .contains("trip plan <trip-id> does not exist")
        .doesNotContain("TRIGGER_ORDERED_CHILD_DENIAL_STATES = Set.of(\"42501\", \"P0001\",");
  }

  @Test
  void actualPG_RLS_fixture의_crossOwner_day_FK_update는_P0001후_aggregate가_불변이다() throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve(
                    "services/spring-api/src/test/java/com/timingjeju/api/domain/trip/adapter/TripRlsIntegrationTest.java"));

    assertThat(source)
        .contains("assertTriggerOrderedChildUpdateDenied(")
        .contains("assertThat(failure.getSQLState()).isEqualTo(\"P0001\")")
        .contains("String ownTripXmin =")
        .contains(
            "assertTripDayAndRootsUnchanged(connection, ownTrip, ownDay, ownTripXmin, otherTripXmin)")
        .contains("select count(*) from public.trip_days where id = ? and trip_plan_id = ?")
        .contains("id = ? and user_id = ? and title = 'own' and xmin::text = ?")
        .contains("id = ? and user_id = ? and title = 'other' and xmin::text = ?")
        .doesNotContain(
            "assertTriggerOrderedChildUpdateDenied(\n            connection,\n            \"update public.trip_transport_modes");
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
