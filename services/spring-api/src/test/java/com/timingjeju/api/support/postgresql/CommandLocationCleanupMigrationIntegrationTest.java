package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CommandLocationCleanupMigrationIntegrationTest {
  private static final String TARGET = "20260918000010_compute_run_input_location_cleanup.sql";
  private static final String BASELINE_SUCCESSOR = "20260901000000_legal_documents_consents.sql";
  private static final String INPUT_ID = "10950000-0000-0000-0000-000000000006";
  private static PostgreSQLContainer container;
  private static JdbcTemplate jdbc;
  private static Map<String, Object> before;

  @BeforeAll
  static void applyTargetOnIssue108Schema() throws Exception {
    container = PostgreSqlTestContainerFactory.createBefore(BASELINE_SUCCESSOR);
    container.start();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    insertNonLocationSnapshot();
    before = immutableSnapshot();
    Path target =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("supabase/migrations")
            .resolve(TARGET);
    PostgreSqlTestContainerFactory.executeScript(container, target);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  void issue108_schema에서_additive_upgrade하고_non_location_snapshot을_보존한다() {
    assertThat(immutableSnapshot()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select"
                    + " to_regprocedure('public.redact_due_compute_run_input_locations(timestamptz,integer)')"
                    + " is not null",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select public.redact_due_compute_run_input_locations(now(), 500)", Integer.class))
        .isZero();
  }

  @Test
  void 역사_SQL의_501_due_row는_첫_batch_500과_다음_batch_1로_정리된다() {
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          var dueAt =
              java.time.Instant.now()
                  .minusSeconds(60)
                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          insertDeterministicallyOrderedDueInputs(dueAt);
          var expected =
              jdbc.queryForObject(
                  """
                  select id from public.compute_run_inputs where location_expires_at <= ?
                  order by location_expires_at, id offset 500 limit 1
                  """,
                  java.util.UUID.class,
                  java.sql.Timestamp.from(dueAt));
          jdbc.execute("set local role service_role");
          assertThat(
                  jdbc.queryForObject(
                      "select public.redact_due_compute_run_input_locations(?,500)",
                      Integer.class,
                      java.sql.Timestamp.from(dueAt)))
              .isEqualTo(500);
          assertThat(
                  jdbc.queryForObject(
                      "select id from public.compute_run_inputs where location_expires_at <= ?",
                      java.util.UUID.class,
                      java.sql.Timestamp.from(dueAt)))
              .isEqualTo(expected)
              .isEqualTo(java.util.UUID.fromString("10931000-0000-0000-0000-000000000501"));
          assertThat(
                  jdbc.queryForObject(
                      "select public.redact_due_compute_run_input_locations(?,500)",
                      Integer.class,
                      java.sql.Timestamp.from(dueAt)))
              .isOne();
          assertThat(
                  jdbc.queryForObject(
                      "select public.redact_due_compute_run_input_locations(?,500)",
                      Integer.class,
                      java.sql.Timestamp.from(dueAt)))
              .isZero();
          status.setRollbackOnly();
        });
  }

  private static void insertDeterministicallyOrderedDueInputs(java.time.Instant dueAt) {
    java.time.Instant terminalAt = dueAt.minus(java.time.Duration.ofHours(24));
    jdbc.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version, completed_at, error_code
        )
        select ('10930000-0000-0000-0000-' || lpad(series::text, 12, '0'))::uuid,
               ?, ?, ?, 'feasibility', 'failed', 'input-' || series,
               'compute/v1', 'algorithm/v1', ?, 'TEST_TERMINAL'
        from generate_series(1, 501) series
        """,
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000002"),
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000003"),
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000004"),
        java.sql.Timestamp.from(terminalAt));
    jdbc.update(
        """
        insert into public.compute_run_inputs (
          id, compute_run_id, owner_user_id, trip_plan_id, base_schedule_version_id,
          run_type, schema_version, contract_version, algorithm_version,
          structured_input, command_input_hash, location_supplied, coarse_location,
          location_precision_meters, location_policy_version, location_observed_at,
          location_expires_at
        )
        select ('10931000-0000-0000-0000-' || lpad((502 - series)::text, 12, '0'))::uuid,
               ('10930000-0000-0000-0000-' || lpad(series::text, 12, '0'))::uuid,
               ?, ?, ?, 'feasibility', 1, 'command/v1', 'algorithm/v1',
               '{"refreshExternalFacts":false}'::jsonb,
               public.compute_command_input_hash(
                 'feasibility'::text, 1::smallint, 'command/v1'::text,
                 'algorithm/v1'::text, ?::uuid,
                 '{"refreshExternalFacts":false}'::jsonb, true::boolean,
                 '{"type":"GRID_100M","gridX":109,"gridY":109}'::jsonb),
               true, '{"type":"GRID_100M","gridX":109,"gridY":109}'::jsonb,
               100, '2026-08-11.v1', ?, ?
        from generate_series(1, 501) series
        """,
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000001"),
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000002"),
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000004"),
        java.util.UUID.fromString("10950000-0000-0000-0000-000000000004"),
        java.sql.Timestamp.from(terminalAt),
        java.sql.Timestamp.from(dueAt));
  }

  private static Map<String, Object> immutableSnapshot() {
    return jdbc.queryForMap(
        """
        select compute_run_id, owner_user_id, trip_plan_id, base_schedule_version_id,
               run_type, schema_version, contract_version, algorithm_version,
               structured_input::text, command_input_hash, location_supplied, created_at
        from public.compute_run_inputs where id = ?::uuid
        """,
        INPUT_ID);
  }

  private static void insertNonLocationSnapshot() {
    jdbc.update(
        "insert into auth.users(id,email) values"
            + " ('10950000-0000-0000-0000-000000000001','issue109@example.test')");
    jdbc.update(
        "insert into public.user_profiles(id,email) values"
            + " ('10950000-0000-0000-0000-000000000001','issue109@example.test')");
    jdbc.update(
        """
        insert into public.trip_plans(
          id,user_id,public_token,start_date,end_date,source_mode,data_version
        ) values (
          '10950000-0000-0000-0000-000000000002',
          '10950000-0000-0000-0000-000000000001',
          'issue109-migration',current_date,current_date,'fixture','v1'
        )
        """);
    jdbc.update(
        """
        insert into public.trip_days(id,trip_plan_id,day_no,trip_date)
        values ('10950000-0000-0000-0000-000000000003',
                '10950000-0000-0000-0000-000000000002',1,current_date)
        """);
    jdbc.update(
        """
        insert into public.trip_schedule_versions(
          id,trip_plan_id,version_no,status,source_type,created_by_user_id
        ) values (
          '10950000-0000-0000-0000-000000000004',
          '10950000-0000-0000-0000-000000000002',1,'draft','initial',
          '10950000-0000-0000-0000-000000000001'
        )
        """);
    jdbc.update(
        """
        insert into public.compute_runs(
          id,trip_plan_id,trip_day_id,schedule_version_id,run_type,status,
          input_hash,contract_version,algorithm_version
        ) values (
          '10950000-0000-0000-0000-000000000005',
          '10950000-0000-0000-0000-000000000002',
          '10950000-0000-0000-0000-000000000003',
          '10950000-0000-0000-0000-000000000004',
          'feasibility','queued','issue109-input','compute/v1','algorithm/v1'
        )
        """);
    jdbc.update(
        """
        insert into public.compute_run_inputs(
          id,compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
          run_type,schema_version,contract_version,algorithm_version,
          structured_input,command_input_hash,location_supplied
        ) values (
          ?::uuid,
          '10950000-0000-0000-0000-000000000005',
          '10950000-0000-0000-0000-000000000001',
          '10950000-0000-0000-0000-000000000002',
          '10950000-0000-0000-0000-000000000004',
          'feasibility',1,'command/v1','algorithm/v1',
          '{"refreshExternalFacts":false}'::jsonb,
          public.compute_command_input_hash(
            'feasibility'::text,1::smallint,'command/v1'::text,'algorithm/v1'::text,
            '10950000-0000-0000-0000-000000000004'::uuid,
            '{"refreshExternalFacts":false}'::jsonb,false::boolean,null::jsonb
          ),false
        )
        """,
        INPUT_ID);
  }
}
