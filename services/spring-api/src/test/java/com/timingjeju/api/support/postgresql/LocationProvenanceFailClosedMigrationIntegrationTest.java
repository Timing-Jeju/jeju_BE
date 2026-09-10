package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@Tag("integration")
class LocationProvenanceFailClosedMigrationIntegrationTest {
  private static final String TARGET = "20260918000020_location_provenance_fail_closed.sql";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void legacy_alias는_전체_rollback하고_정리후_service_role_hash와_좌표배열을_차단한다(String image)
      throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      try (var connection =
          DriverManager.getConnection(
              container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        UUID owner = UUID.randomUUID();
        UUID trip = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID day = UUID.randomUUID();
        String email = owner + "@issue223-forward.test";
        jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
        jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
        jdbc.update(
            "insert into public.trip_plans "
                + "(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision) "
                + "values (?,?,?,'forward guard','draft','2026-09-01','2026-09-01','fixture','issue223',1)",
            trip,
            owner,
            "forward-" + trip);
        jdbc.update(
            "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) "
                + "values (?,?,1,'draft','initial')",
            version,
            trip);
        jdbc.update(
            "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
            day,
            trip);
        jdbc.update(
            "insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary) "
                + "values (?,'public_transit',1,true)",
            trip);
        jdbc.update(
            "insert into public.trip_preferences"
                + "(trip_plan_id,arrival_region_code,departure_region_code,raw_answers) "
                + "values (?,'JEJU','JEJU','{\"current_position\":[126.51,33.51]}'::jsonb)",
            trip);

        String fingerprint =
            Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
        String before = jdbc.queryForObject(fingerprint, String.class);
        assertThatThrownBy(
                () ->
                    PostgreSqlTestContainerFactory.executeScript(
                        container,
                        PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user location residue requires audit")
            .hasMessageNotContaining("126.51")
            .hasMessageNotContaining("33.51")
            .hasMessageNotContaining("Failing row");
        assertThat(jdbc.queryForObject(fingerprint, String.class)).isEqualTo(before);
        assertThat(
                jdbc.queryForObject(
                    "select timing_jeju_planner_private.user_location_guard_purge_revision()",
                    String.class))
            .isEqualTo("20260918000018");

        jdbc.update("delete from public.trip_preferences where trip_plan_id=?", trip);
        PostgreSqlTestContainerFactory.executeScript(
            container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
        assertThat(
                jdbc.queryForObject(
                    "select timing_jeju_planner_private.user_location_guard_purge_revision()",
                    String.class))
            .isEqualTo("20260918000020");

        assertSqlState23514(
            () ->
                jdbc.update(
                    "insert into public.schedule_revision_runs "
                        + "(owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,"
                        + "contract_version,algorithm_version,idempotency_key,request_hash) "
                        + "values (?,?,?,?, 'fixture','fixture',?,repeat('c',64))",
                    owner,
                    trip,
                    version,
                    day,
                    UUID.randomUUID()));
        assertSqlState23514(
            () ->
                jdbc.update(
                    "insert into public.mcp_compute_call_logs"
                        + "(request_id,tool_name,status,contract_version,mcp_input_hash) "
                        + "values ('owner-unproven-mcp','evaluate_jeju_day_trip','transport_error',"
                        + "'0.7.0',repeat('c',64))"));
        jdbc.execute("set role service_role");
        assertSqlState23514(
            () ->
                jdbc.update(
                    "insert into public.schedule_revision_runs "
                        + "(owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,"
                        + "contract_version,algorithm_version,idempotency_key,request_hash) "
                        + "values (?,?,?,?, 'fixture','fixture',?,repeat('d',64))",
                    owner,
                    trip,
                    version,
                    day,
                    UUID.randomUUID()));
        assertSqlState23514(
            () ->
                jdbc.update(
                    "insert into public.mcp_compute_call_logs"
                        + "(request_id,tool_name,status,contract_version,mcp_input_hash) "
                        + "values ('unproven-mcp','evaluate_jeju_day_trip','transport_error',"
                        + "'0.7.0',repeat('e',64))"));
        assertSqlState23514(
            () ->
                jdbc.update(
                    "insert into public.trip_preferences"
                        + "(trip_plan_id,arrival_region_code,departure_region_code,raw_answers) "
                        + "values (?,'JEJU','JEJU','{\"nested\":[[126.51,33.51]]}'::jsonb)",
                    trip));

        jdbc.execute("reset role");
        UUID existingRevision = UUID.randomUUID();
        String revisionInput =
            "{\"targetDayId\":\"" + day + "\",\"affectedItemIds\":[],\"instructionCodes\":[]}";
        String commandInputHash =
            jdbc.queryForObject(
                """
                select public.compute_command_input_hash(
                  'schedule_revision'::text,1::smallint,'fixture'::text,'fixture'::text,
                  ?::uuid,?::jsonb,false::boolean,null::jsonb)
                """,
                String.class,
                version,
                revisionInput);
        jdbc.execute(
            "alter table public.schedule_revision_runs disable trigger aaa_independent_hash_provenance");
        connection.setAutoCommit(false);
        jdbc.update(
            "insert into public.schedule_revision_runs "
                + "(id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,"
                + "contract_version,algorithm_version,idempotency_key,request_hash) "
                + "values (?,?,?,?,?,'fixture','fixture',?,repeat('a',64))",
            existingRevision,
            owner,
            trip,
            version,
            day,
            UUID.randomUUID());
        jdbc.update(
            """
            insert into public.compute_run_inputs
              (schedule_revision_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
               run_type,schema_version,contract_version,algorithm_version,structured_input,
               command_input_hash,location_supplied)
            values (?,?,?,?, 'schedule_revision',1,'fixture','fixture',
              ?::jsonb,?,false)
            """,
            existingRevision,
            owner,
            trip,
            version,
            revisionInput,
            commandInputHash);
        connection.commit();
        connection.setAutoCommit(true);
        jdbc.execute(
            "alter table public.schedule_revision_runs enable trigger aaa_independent_hash_provenance");
        jdbc.execute(
            "alter table public.mcp_compute_call_logs disable trigger aaa_independent_hash_provenance");
        jdbc.update(
            """
            insert into public.mcp_compute_call_logs
              (schedule_revision_run_id,request_id,tool_name,status,contract_version,
               command_input_hash,mcp_input_hash,schema_checksum,request_fact_count,
               response_fact_count,attempt_no,latency_ms,error_code)
            select schedule_revision_run_id,'existing-mcp','evaluate_jeju_day_trip',
              'transport_error','0.7.0',command_input_hash,repeat('a',64),repeat('b',64),
              0,0,1,1,'MCP_TIMEOUT'
            from public.compute_run_inputs where schedule_revision_run_id=?
            """,
            existingRevision);
        jdbc.execute(
            "alter table public.mcp_compute_call_logs enable trigger aaa_independent_hash_provenance");
        assertSqlState23514(
            () ->
                jdbc.update(
                    "update public.schedule_revision_runs set request_hash=repeat('b',64) where id=?",
                    existingRevision));
        assertSqlState23514(
            () ->
                jdbc.update(
                    "update public.mcp_compute_call_logs set mcp_input_hash=repeat('c',64) "
                        + "where request_id='existing-mcp'"));
        jdbc.execute("set role service_role");
        assertSqlState23514(
            () ->
                jdbc.update(
                    "update public.schedule_revision_runs set request_hash=repeat('b',64) where id=?",
                    existingRevision));
        assertSqlState23514(
            () ->
                jdbc.update(
                    "update public.mcp_compute_call_logs set mcp_input_hash=repeat('c',64) "
                        + "where request_id='existing-mcp'"));
        jdbc.execute("reset role");
        assertSqlState23514(
            () ->
                jdbc.update(
                    "update public.schedule_revision_runs set request_hash=repeat('b',64) where id=?",
                    existingRevision));
      }
    } finally {
      container.stop();
    }
  }

  private static void assertSqlState23514(ThrowingSql write) {
    Throwable failure = catchThrowable(write::run);
    assertThat(failure).hasRootCauseInstanceOf(SQLException.class);
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    assertThat(((SQLException) root).getSQLState()).isEqualTo("23514");
  }

  @FunctionalInterface
  private interface ThrowingSql {
    void run() throws Exception;
  }
}
