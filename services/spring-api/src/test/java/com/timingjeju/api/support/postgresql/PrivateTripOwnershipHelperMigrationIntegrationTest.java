package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PrivateTripOwnershipHelperMigrationIntegrationTest {
  private static final String TARGET = "20260917000000_private_trip_ownership_helper.sql";
  private static final List<String> TABLES =
      List.of(
          "trip_preferences",
          "trip_transport_modes",
          "trip_transport_events",
          "trip_accommodations",
          "trip_days",
          "trip_schedule_versions",
          "trip_items",
          "itinerary_generation_runs",
          "itinerary_generation_candidates",
          "trip_legs",
          "trip_item_progress",
          "trip_execution_events",
          "compute_runs",
          "risk_events",
          "trip_weather_impacts",
          "recommendation_candidates",
          "recovery_options",
          "recovery_option_changes",
          "live_state_snapshots");

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void migration은_PG16과_PG17에서_원자적으로전환되고_replay된다(String image) throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      JdbcTemplate jdbc = jdbc(container);
      List<Map<String, Object>> policiesBefore = policies(jdbc);
      List<Map<String, Object>> grantsBefore = grants(jdbc);
      List<Map<String, Object>> rlsBefore = rls(jdbc);
      List<Map<String, Object>> dataBefore = rowCounts(jdbc);

      jdbc.execute(
          """
          create policy issue210_legacy_dependency
            on public.live_state_snapshots for select to authenticated
            using (public.owns_trip_plan(trip_plan_id))
          """);

      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target()))
          .isInstanceOf(IllegalStateException.class);

      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_private.owns_trip_plan(uuid)') is null",
                  Boolean.class))
          .as("atomic rollback removes the new helper")
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('public.owns_trip_plan(uuid)') is not null",
                  Boolean.class))
          .as("atomic rollback keeps the legacy helper")
          .isTrue();
      assertThat(policiesWithoutLegacyDependency(jdbc)).isEqualTo(policiesBefore);
      assertThat(grants(jdbc)).isEqualTo(grantsBefore);
      assertThat(rls(jdbc)).isEqualTo(rlsBefore);
      assertThat(rowCounts(jdbc)).isEqualTo(dataBefore);

      jdbc.execute("drop policy issue210_legacy_dependency on public.live_state_snapshots");
      PostgreSqlTestContainerFactory.executeScript(container, target());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from pg_policies where schemaname='public'"
                      + " and policyname like '%owner_select'"
                      + " and qual like '%timing_jeju_private.owns_trip_plan%'",
                  Integer.class))
          .isEqualTo(19);
      assertThat(grants(jdbc)).isEqualTo(grantsBefore);
      assertThat(rls(jdbc)).isEqualTo(rlsBefore);
      assertThat(rowCounts(jdbc)).isEqualTo(dataBefore);

      List<Map<String, Object>> migratedPolicies = policies(jdbc);
      PostgreSqlTestContainerFactory.executeScript(container, target());
      assertThat(policies(jdbc)).isEqualTo(migratedPolicies);
      assertThat(grants(jdbc)).isEqualTo(grantsBefore);
      assertThat(rls(jdbc)).isEqualTo(rlsBefore);
      assertThat(rowCounts(jdbc)).isEqualTo(dataBefore);

      PostgreSqlTestContainerFactory.executeScript(container, seed());
      PostgreSqlTestContainerFactory.executeScript(container, actualRlsContract());

      Path authorizationMutation = helperAuthorizationMutation();
      try {
        PostgreSqlTestContainerFactory.executeScript(container, authorizationMutation);
        assertThatThrownBy(
                () -> PostgreSqlTestContainerFactory.executeScript(container, actualRlsContract()))
            .as("helper authorization mutation must be killed on " + image)
            .isInstanceOf(IllegalStateException.class);
      } finally {
        Files.deleteIfExists(authorizationMutation);
      }
    } finally {
      container.stop();
    }
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private static Path target() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("supabase/migrations")
        .resolve(TARGET);
  }

  private static Path seed() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("db/local-postgres/seed_fixtures.sql");
  }

  private static Path actualRlsContract() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("db/queries/private_trip_ownership_helper_contract.sql");
  }

  private static Path helperAuthorizationMutation() throws Exception {
    String source = Files.readString(target(), StandardCharsets.UTF_8);
    String mutation =
        source.replace(
            "and trip_plan.user_id = current_user_id", "and trip_plan.user_id is not null");
    assertThat(mutation).as("helper authorization mutation fixture").isNotEqualTo(source);
    Path temporary = Files.createTempFile("issue210-helper-authorization-mutation-", ".sql");
    Files.writeString(temporary, mutation, StandardCharsets.UTF_8);
    return temporary;
  }

  private static List<Map<String, Object>> policies(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        """
        select schemaname,tablename,policyname,permissive,roles::text,cmd,qual,with_check
        from pg_policies
        order by schemaname,tablename,policyname
        """);
  }

  private static List<Map<String, Object>> policiesWithoutLegacyDependency(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        """
        select schemaname,tablename,policyname,permissive,roles::text,cmd,qual,with_check
        from pg_policies
        where policyname <> 'issue210_legacy_dependency'
        order by schemaname,tablename,policyname
        """);
  }

  private static List<Map<String, Object>> grants(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        """
        select grantee,table_schema,table_name,privilege_type,is_grantable
        from information_schema.role_table_grants
        where grantee in ('anon','authenticated','service_role')
        order by grantee,table_schema,table_name,privilege_type
        """);
  }

  private static List<Map<String, Object>> rls(JdbcTemplate jdbc) {
    String placeholders = String.join(",", java.util.Collections.nCopies(TABLES.size(), "?"));
    return jdbc.queryForList(
        "select n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity"
            + " from pg_class c join pg_namespace n on n.oid=c.relnamespace"
            + " where n.nspname='public' and c.relname in ("
            + placeholders
            + ") order by c.relname",
        TABLES.toArray());
  }

  private static List<Map<String, Object>> rowCounts(JdbcTemplate jdbc) {
    return TABLES.stream()
        .map(
            table ->
                Map.<String, Object>of(
                    "table",
                    table,
                    "count",
                    jdbc.queryForObject("select count(*) from public." + table, Long.class)))
        .toList();
  }
}
