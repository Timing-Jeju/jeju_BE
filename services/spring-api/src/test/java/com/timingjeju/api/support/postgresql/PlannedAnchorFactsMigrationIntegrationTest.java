package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class PlannedAnchorFactsMigrationIntegrationTest {
  private static final String TARGET = "20260918000013_schedule_item_closed_facts.sql";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 출처_불명_legacy_facts의_upgrade는_데이터_스키마_RLS_ACL을_보존하고_중단한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      Fixture fixture;
      try (Connection connection = source.getConnection()) {
        fixture = createDraftFixture(connection);
      }
      jdbc.update(
          "insert into public.trip_items "
              + "(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,source,facts) "
              + "values (?::uuid,?::uuid,?::uuid,1,'custom','legacy planned note','user_input',?::jsonb)",
          fixture.tripPlanId(),
          fixture.dayId(),
          fixture.scheduleVersionId(),
          "{\"location\":{\"lat\":33.4,\"lng\":126.5},\"memo\":\"legacy-private-marker\"}");
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      String fingerprintSql =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String beforeSchema = jdbc.queryForObject(fingerprintSql, String.class);
      String beforeFacts =
          jdbc.queryForObject(
              "select facts::text from public.trip_items where schedule_version_id=?::uuid",
              String.class,
              fixture.scheduleVersionId());

      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container, root.resolve("supabase/migrations").resolve(TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("legacy schedule item facts require provenance audit")
          .hasMessageNotContaining("legacy-private-marker")
          .hasMessageNotContaining("33.4")
          .hasMessageNotContaining("126.5")
          .hasMessageNotContaining("Failing row");

      assertThat(jdbc.queryForObject(fingerprintSql, String.class)).isEqualTo(beforeSchema);
      assertThat(
              jdbc.queryForObject(
                  "select facts::text from public.trip_items where schedule_version_id=?::uuid",
                  String.class,
                  fixture.scheduleVersionId()))
          .isEqualTo(beforeFacts);
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_private.validate_trip_item_closed_facts()') is null",
                  Boolean.class))
          .isTrue();
    } finally {
      container.stop();
    }
  }

  private static Fixture createDraftFixture(Connection connection) throws SQLException {
    String ownerId = UUID.randomUUID().toString();
    String tripPlanId = UUID.randomUUID().toString();
    String dayId = UUID.randomUUID().toString();
    String scheduleVersionId = UUID.randomUUID().toString();
    String email = ownerId + "@issue225.test";
    try (var statement =
        connection.prepareStatement("insert into auth.users(id,email) values (?::uuid,?)")) {
      statement.setString(1, ownerId);
      statement.setString(2, email);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.user_profiles(id,email) values (?::uuid,?)")) {
      statement.setString(1, ownerId);
      statement.setString(2, email);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_plans "
                + "(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision) "
                + "values (?::uuid,?::uuid,?,'ACL QA','draft','2026-09-01','2026-09-01','fixture','issue225-acl',1)")) {
      statement.setString(1, tripPlanId);
      statement.setString(2, ownerId);
      statement.setString(3, "issue225-acl-" + tripPlanId);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?::uuid,?::uuid,1,'2026-09-01')")) {
      statement.setString(1, dayId);
      statement.setString(2, tripPlanId);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type,summary) values (?::uuid,?::uuid,1,'draft','user_edit','ACL trigger QA')")) {
      statement.setString(1, scheduleVersionId);
      statement.setString(2, tripPlanId);
      statement.executeUpdate();
    }
    return new Fixture(tripPlanId, dayId, scheduleVersionId);
  }

  private record Fixture(String tripPlanId, String dayId, String scheduleVersionId) {}
}
