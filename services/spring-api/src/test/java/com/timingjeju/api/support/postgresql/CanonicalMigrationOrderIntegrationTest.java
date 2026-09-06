package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CanonicalMigrationOrderIntegrationTest {
  private static final String ORIGIN_DEVELOP = "6cfa98fd3e65ba270eceea7150c843b33dbe2a56";
  private static final String FIRST_SUFFIX = "20260918000000_trip_preferences_replace_contract.sql";
  private static final String SCHEDULE_50 = "20260918000007_schedule_item_required_references.sql";
  private static final String SCHEDULE_51 =
      "20260918000008_schedule_item_required_references_correction.sql";
  private static final List<String> POSTGIS_IMAGES =
      List.of("postgis/postgis:16-3.4", "postgis/postgis:17-3.5");
  private static final List<String> CANONICAL_SUFFIX =
      List.of(
          FIRST_SUFFIX,
          "20260918000001_trip_preferences_owner_read_helper.sql",
          "20260918000002_trip_accommodation_contract.sql",
          "20260918000003_trip_transport_event_contract.sql",
          "20260918000004_trip_place_preference_contract.sql",
          "20260918000005_trip_calendar_child_invariant_correction.sql",
          "20260918000006_profile_image_storage.sql",
          SCHEDULE_50,
          SCHEDULE_51,
          "20260918000009_jeju_timetable_route_scope.sql",
          "20260918000010_compute_run_input_location_cleanup.sql",
          "20260918000011_private_trip_ownership_helper.sql",
          "20260918000012_schedule_title_only_sealing_correction.sql");

  @Test
  void freshInstall과_originDevelopUpgrade의_schemaAndAclFingerprint가_같다() throws Exception {
    String freshInstall = freshInstall();
    String originDevelopUpgrade = originDevelopUpgrade();

    assertThat(ORIGIN_DEVELOP).hasSize(40);
    assertThat(originDevelopUpgrade).isEqualTo(freshInstall);
  }

  @Test
  void schedule50Then51Upgrade는_freshInstall과_동일한_최종_catalog를_만든다() throws Exception {
    String schedule50Then51Upgrade = schedule50Then51Upgrade();

    assertThat(schedule50Then51Upgrade).isEqualTo(freshInstall());
  }

  @Test
  void preflightRollback은_실패전_schemaAndAclFingerprint를_보존한다() throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.createBefore(SCHEDULE_51);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(
          container, repositoryPath("db/local-postgres/seed_fixtures.sql"));
      PostgreSqlTestContainerFactory.executeScript(
          container,
          repositoryPath("db/queries/legacy_schedule_item_reference_conflict_fixture.sql"));
      JdbcTemplate jdbc = jdbc(container);
      String before = schemaAndAclFingerprint(jdbc);

      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container, migrationPath(SCHEDULE_51)))
          .isInstanceOf(IllegalStateException.class);

      assertThat(schemaAndAclFingerprint(jdbc)).isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_items where id in "
                      + "('e4300000-0000-0000-0000-000000000001',"
                      + "'61000000-0000-0000-0000-000000000001') "
                      + "and item_type='custom' and title=E'\\n'",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @Test
  void Postgis_PG16과_PG17은_extension_aggregate_오류없이_fingerprint를_비교한다() throws Exception {
    for (String image : POSTGIS_IMAGES) {
      PostgreSQLContainer container =
          PostgreSqlTestContainerFactory.createBefore(FIRST_SUFFIX, image);
      try {
        container.start();
        applyCanonicalSuffix(container);
        String first = schemaAndAclFingerprint(jdbc(container));

        assertThat(first).as(image).isNotBlank();
        assertThat(schemaAndAclFingerprint(jdbc(container))).as(image).isEqualTo(first);
      } finally {
        container.stop();
      }
    }
  }

  @Test
  void Postgis_PG16과_PG17은_titleOnly_item을_seal하고_blankTitle은_거부한다() throws Exception {
    for (String image : POSTGIS_IMAGES) {
      PostgreSQLContainer container =
          PostgreSqlTestContainerFactory.createBefore(
              "20260918000009_jeju_timetable_route_scope.sql", image);
      try {
        container.start();
        PostgreSqlTestContainerFactory.executeScript(
            container, repositoryPath("db/local-postgres/seed_fixtures.sql"));
        for (String migration : CANONICAL_SUFFIX.subList(9, CANONICAL_SUFFIX.size())) {
          PostgreSqlTestContainerFactory.executeScript(container, migrationPath(migration));
        }
        JdbcTemplate jdbc = jdbc(container);
        var mutation =
            container.execInContainer(
                "psql",
                "--no-psqlrc",
                "--set",
                "ON_ERROR_STOP=1",
                "--username",
                container.getUsername(),
                "--dbname",
                container.getDatabaseName(),
                "--command",
                "set session_replication_role=replica; update public.trip_items set item_type='custom', place_id=null, title='메모 일정', facts='{}'::jsonb where id='61200000-0000-0000-0000-000000000006'; set session_replication_role=origin;");
        assertThat(mutation.getExitCode()).as(mutation.getStderr()).isZero();

        assertThatCode(
                () ->
                    jdbc.execute(
                        "select public.assert_schedule_version_sealable('60000000-0000-0000-0000-000000000003','50000000-0000-0000-0000-000000000001')"))
            .as(image)
            .doesNotThrowAnyException();
        var invalidMutation =
            container.execInContainer(
                "psql",
                "--no-psqlrc",
                "--set",
                "ON_ERROR_STOP=1",
                "--username",
                container.getUsername(),
                "--dbname",
                container.getDatabaseName(),
                "--command",
                "alter table public.trip_items drop constraint chk_trip_items_required_references; set session_replication_role=replica; update public.trip_items set title=E'\\n' where id='61200000-0000-0000-0000-000000000006'; set session_replication_role=origin;");
        assertThat(invalidMutation.getExitCode()).as(invalidMutation.getStderr()).isZero();
        assertThatThrownBy(
                () ->
                    jdbc.execute(
                        "select public.assert_schedule_version_sealable('60000000-0000-0000-0000-000000000003','50000000-0000-0000-0000-000000000001')"))
            .as(image)
            .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
            .hasMessageContaining("required reference invariants");
      } finally {
        container.stop();
      }
    }
  }

  @Test
  void schemaAndAclFingerprint는_security_projection_변조를_모두_탐지한다() throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.create();
    try {
      container.start();
      JdbcTemplate jdbc = jdbc(container);
      String freshFingerprint = schemaAndAclFingerprint(jdbc);
      List<SecurityMutation> mutations =
          List.of(
              new SecurityMutation(
                  "grant select (token_ciphertext) on public.push_devices to authenticated",
                  "revoke select (token_ciphertext) on public.push_devices from authenticated"),
              new SecurityMutation(
                  "grant select on public.push_devices to public",
                  "revoke select on public.push_devices from public"),
              new SecurityMutation(
                  "grant usage on schema auth to authenticated",
                  "revoke usage on schema auth from authenticated"),
              new SecurityMutation(
                  "grant create on schema timing_jeju_private to authenticated",
                  "revoke create on schema timing_jeju_private from authenticated"),
              new SecurityMutation(
                  "alter policy push_devices_owner_select on public.push_devices to public",
                  "alter policy push_devices_owner_select on public.push_devices to authenticated"),
              new SecurityMutation(
                  "create policy fingerprint_restrictive_probe on public.push_devices "
                      + "as restrictive for select to authenticated using (true)",
                  "drop policy fingerprint_restrictive_probe on public.push_devices"));

      for (SecurityMutation mutation : mutations) {
        jdbc.execute(mutation.apply());
        try {
          String mutatedUpgradeFingerprint = schemaAndAclFingerprint(jdbc);
          assertThat(mutatedUpgradeFingerprint).as(mutation.apply()).isNotEqualTo(freshFingerprint);
        } finally {
          jdbc.execute(mutation.revert());
        }
        assertThat(schemaAndAclFingerprint(jdbc)).as(mutation.revert()).isEqualTo(freshFingerprint);
      }
    } finally {
      container.stop();
    }
  }

  private static String freshInstall() throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.create();
    try {
      container.start();
      return schemaAndAclFingerprint(jdbc(container));
    } finally {
      container.stop();
    }
  }

  private static String originDevelopUpgrade() throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.createBefore(FIRST_SUFFIX);
    try {
      container.start();
      applyCanonicalSuffix(container);
      return schemaAndAclFingerprint(jdbc(container));
    } finally {
      container.stop();
    }
  }

  private static void applyCanonicalSuffix(PostgreSQLContainer container) throws Exception {
    for (String migration : CANONICAL_SUFFIX) {
      PostgreSqlTestContainerFactory.executeScript(container, migrationPath(migration));
    }
  }

  private static String schedule50Then51Upgrade() throws Exception {
    PostgreSQLContainer container = PostgreSqlTestContainerFactory.createBefore(SCHEDULE_50);
    try {
      container.start();
      PostgreSqlTestContainerFactory.executeScript(container, migrationPath(SCHEDULE_50));
      PostgreSqlTestContainerFactory.executeScript(container, migrationPath(SCHEDULE_51));
      for (String migration : CANONICAL_SUFFIX.subList(9, CANONICAL_SUFFIX.size())) {
        PostgreSqlTestContainerFactory.executeScript(container, migrationPath(migration));
      }
      return schemaAndAclFingerprint(jdbc(container));
    } finally {
      container.stop();
    }
  }

  private static String schemaAndAclFingerprint(JdbcTemplate jdbc) throws Exception {
    return jdbc.queryForObject(
        Files.readString(repositoryPath("db/queries/canonical_migration_fingerprint.sql")),
        String.class);
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private static Path migrationPath(String name) {
    return repositoryPath("supabase/migrations/" + name);
  }

  private static Path repositoryPath(String relative) {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot().resolve(relative);
  }

  private record SecurityMutation(String apply, String revert) {}
}
