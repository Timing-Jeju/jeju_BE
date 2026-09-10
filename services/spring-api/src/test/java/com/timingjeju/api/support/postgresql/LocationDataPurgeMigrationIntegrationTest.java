package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.global.asyncrun.JdbcRunLeaseRepository;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class LocationDataPurgeMigrationIntegrationTest {
  private static final String TARGET = "20260918000017_user_location_write_guard_purge.sql";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 직접_nested_위치를_제거해도_수동_event와_공개_geodata는_보존된다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_execution_events where location is not null",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_execution_events "
                      + "where id=? and metadata=jsonb_build_object('source','manual') and trip_plan_id=? and schedule_version_id=?",
                  Integer.class,
                  fixture.event(),
                  fixture.trip(),
                  fixture.version()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.live_state_snapshots", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.tour_places where id=? and "
                      + "ST_Equals(location::geometry,ST_SetSRID(ST_MakePoint(126.5,33.5),4326))",
                  Integer.class,
                  fixture.place()))
          .isEqualTo(1);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update public.trip_execution_events set occurred_at=now() where id=?",
                      fixture.event()))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
      assertThat(
              jdbc.queryForObject(
                  "select coalesce(sum(residue_count),0) from "
                      + "timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 의미가_불명확한_event_metadata는_삭제하지_않고_스키마와_데이터를_롤백한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      insertLegacyFixture(jdbc, true);
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('events',(select jsonb_agg(t order by id) "
              + "from public.trip_execution_events t),'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("legacy non-location metadata requires audit")
          .hasMessageNotContaining("private-location-marker")
          .hasMessageNotContaining("private-manual-note")
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,false", "postgis/postgis:17-3.5,false",
    "postgis/postgis:16-3.4,true", "postgis/postgis:17-3.5,true"
  })
  void redacted_여부와_무관하게_위치_input과_부모_run_MCP_hash를_함께_제거한다(String image, boolean redacted)
      throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, redacted);
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(
              jdbc.queryForObject("select count(*) from public.compute_run_inputs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_runs where id=?", Integer.class, run))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mcp_compute_call_logs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where id=?",
                  Integer.class,
                  fixture.version()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_plans where id=?",
                  Integer.class,
                  fixture.trip()))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,queued", "postgis/postgis:17-3.5,queued",
    "postgis/postgis:16-3.4,running", "postgis/postgis:17-3.5,running"
  })
  void 실행_가능한_위치_run이_있으면_삭제나_lease_변경없이_전체_롤백한다(String image, String status) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, false);
      if (status.equals("queued")) {
        jdbc.update(
            "update public.compute_runs set status='queued',completed_at=null,started_at=null,"
                + "facts_snapshot_at=null,source_data_version=null where id=?",
            run);
      } else {
        jdbc.update(
            "update public.compute_runs set status='running',completed_at=null,"
                + "lease_owner='fixture-worker',heartbeat_at=now(),lease_expires_at=now()+interval '30 seconds',"
                + "fencing_token=7,next_attempt_at=null where id=?",
            run);
      }
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('events',(select jsonb_agg(t order by id) "
              + "from public.trip_execution_events t),'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t),"
              + "'runs',(select jsonb_agg(t order by id) from public.compute_runs t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'logs',(select jsonb_agg(t order by id) from public.mcp_compute_call_logs t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active location lineage requires audit")
          .hasMessageNotContaining(run.toString())
          .hasMessageNotContaining("private-location-marker")
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 위치_결과의_proposed_version과_후손만_제거하고_평가_대상_원본은_보존한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, false);
      UUID proposed = UUID.randomUUID();
      UUID descendant = UUID.randomUUID();
      jdbc.update(
          "insert into public.trip_schedule_versions "
              + "(id,trip_plan_id,version_no,base_schedule_version_id,status,source_type) "
              + "values (?,?,2,?,'draft','recovery'),(?,?,3,?,'draft','user_edit')",
          proposed,
          fixture.trip(),
          fixture.version(),
          descendant,
          fixture.trip(),
          proposed);
      jdbc.update(
          "insert into public.recovery_options "
              + "(trip_plan_id,compute_run_id,base_schedule_version_id,proposed_schedule_version_id,option_type,status,title) "
              + "values (?,?,?,?,'skip_optional','proposed','fixture recovery')",
          fixture.trip(),
          run,
          fixture.version(),
          proposed);
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(jdbc.queryForObject("select count(*) from public.recovery_options", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where id in (?,?)",
                  Integer.class,
                  proposed,
                  descendant))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where id=?",
                  Integer.class,
                  fixture.version()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_runs where id=?", Integer.class, run))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select coalesce(sum(residue_count),0) from "
                      + "timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,missing_input", "postgis/postgis:17-3.5,missing_input",
    "postgis/postgis:16-3.4,parent_hash", "postgis/postgis:17-3.5,parent_hash",
    "postgis/postgis:16-3.4,log_hash", "postgis/postgis:17-3.5,log_hash",
    "postgis/postgis:16-3.4,matched_corrupt_hash", "postgis/postgis:17-3.5,matched_corrupt_hash"
  })
  void 입력_없음이나_hash_관계가_불명확한_legacy_계보는_추정_삭제하지_않는다(String image, String kind) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, !kind.equals("matched_corrupt_hash"));
      switch (kind) {
        case "missing_input" ->
            jdbc.update("delete from public.compute_run_inputs where compute_run_id=?", run);
        case "parent_hash" ->
            jdbc.update("update public.compute_runs set input_hash=repeat('c',64) where id=?", run);
        case "log_hash" ->
            jdbc.update(
                "update public.mcp_compute_call_logs set command_input_hash=repeat('c',64) where compute_run_id=?",
                run);
        case "matched_corrupt_hash" -> {
          // Disposable pre-migration corruption fixture only: restore the guard before the audited
          // migration.
          jdbc.execute(
              "alter table public.compute_run_inputs disable trigger trg_compute_run_inputs_immutable");
          try {
            jdbc.update(
                "update public.compute_run_inputs set command_input_hash=repeat('c',64) where compute_run_id=?",
                run);
          } finally {
            jdbc.execute(
                "alter table public.compute_run_inputs enable trigger trg_compute_run_inputs_immutable");
          }
          jdbc.update("update public.compute_runs set input_hash=repeat('c',64) where id=?", run);
          jdbc.update(
              "update public.mcp_compute_call_logs set command_input_hash=repeat('c',64) where compute_run_id=?",
              run);
        }
        default -> throw new IllegalArgumentException("unsupported fixture");
      }
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('events',(select jsonb_agg(t order by id) "
              + "from public.trip_execution_events t),'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t),"
              + "'runs',(select jsonb_agg(t order by id) from public.compute_runs t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'logs',(select jsonb_agg(t order by id) from public.mcp_compute_call_logs t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("legacy compute hash lineage requires audit")
          .hasMessageNotContaining(run.toString())
          .hasMessageNotContaining("c".repeat(64))
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 이미_commit된_입력없는_legacy_parent도_worker가_claim하지_않는다(String image) {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, true);
      jdbc.update("delete from public.compute_run_inputs where compute_run_id=?", run);
      jdbc.update(
          "update public.compute_runs set status='queued',completed_at=null,started_at=null,"
              + "facts_snapshot_at=null,source_data_version=null where id=?",
          run);
      // DriverManagerDataSource uses a fresh auto-commit connection for each operation.
      var leases =
          new JdbcRunLeaseRepository(jdbc)
              .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10);
      assertThat(leases).isEmpty();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_runs where id=? "
                      + "and status='queued' and fencing_token=0 and lease_owner is null",
                  Integer.class,
                  run))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,active_output", "postgis/postgis:17-3.5,active_output",
    "postgis/postgis:16-3.4,active_descendant", "postgis/postgis:17-3.5,active_descendant",
    "postgis/postgis:16-3.4,applied_option", "postgis/postgis:17-3.5,applied_option"
  })
  void 활성_출력이나_후손_또는_이미_적용한_수정안이_있으면_모든_계보를_보존한다(String image, String kind) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertTerminalLocationInput(jdbc, fixture, true);
      UUID proposed = UUID.randomUUID();
      UUID descendant = UUID.randomUUID();
      jdbc.update(
          "insert into public.trip_schedule_versions "
              + "(id,trip_plan_id,version_no,base_schedule_version_id,status,source_type) "
              + "values (?,?,2,?,'draft','recovery'),(?,?,3,?,'draft','user_edit')",
          proposed,
          fixture.trip(),
          fixture.version(),
          descendant,
          fixture.trip(),
          proposed);
      jdbc.update(
          "insert into public.recovery_options "
              + "(trip_plan_id,compute_run_id,base_schedule_version_id,proposed_schedule_version_id,option_type,status,title,applied_at) "
              + "values (?,?,?,?,'skip_optional',?,'fixture recovery',case when ? then now() end)",
          fixture.trip(),
          run,
          fixture.version(),
          proposed,
          kind.equals("applied_option") ? "applied" : "proposed",
          kind.equals("applied_option"));
      if (!kind.equals("applied_option")) {
        UUID active = kind.equals("active_output") ? proposed : descendant;
        UUID day = UUID.randomUUID();
        jdbc.update(
            "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) "
                + "values (?,?,1,'2026-09-01')",
            day,
            fixture.trip());
        jdbc.update(
            "insert into public.trip_items "
                + "(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,planned_start_at,planned_end_at,stay_minutes,source,facts) "
                + "values (?,?,?,1,'custom','location-free manual item','2026-09-01T00:00:00Z','2026-09-01T01:00:00Z',60,'user_input','{}'::jsonb)",
            fixture.trip(),
            day,
            active);
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                connection -> {
                  connection.setAutoCommit(false);
                  try (var versionUpdate =
                          connection.prepareStatement(
                              "update public.trip_schedule_versions set status='active',applied_at=now() where id=?");
                      var tripUpdate =
                          connection.prepareStatement(
                              "update public.trip_plans set active_schedule_version_id=?,status='planned' where id=?")) {
                    versionUpdate.setObject(1, active);
                    versionUpdate.executeUpdate();
                    tripUpdate.setObject(1, active);
                    tripUpdate.setObject(2, fixture.trip());
                    tripUpdate.executeUpdate();
                    connection.commit();
                  } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                  } finally {
                    connection.setAutoCommit(true);
                  }
                  return null;
                });
      }
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('trips',(select jsonb_agg(t order by id) from public.trip_plans t),"
              + "'versions',(select jsonb_agg(t order by id) from public.trip_schedule_versions t),"
              + "'items',(select jsonb_agg(t order by id) from public.trip_items t),"
              + "'options',(select jsonb_agg(t order by id) from public.recovery_options t),"
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
              + "'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t),"
              + "'runs',(select jsonb_agg(t order by id) from public.compute_runs t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'logs',(select jsonb_agg(t order by id) from public.mcp_compute_call_logs t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active location lineage requires audit")
          .hasMessageNotContaining(proposed.toString())
          .hasMessageNotContaining(descendant.toString())
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,false,draft", "postgis/postgis:17-3.5,false,draft",
    "postgis/postgis:16-3.4,true,draft", "postgis/postgis:17-3.5,true,draft",
    "postgis/postgis:16-3.4,false,sealed", "postgis/postgis:17-3.5,false,sealed",
    "postgis/postgis:16-3.4,true,sealed", "postgis/postgis:17-3.5,true,sealed",
    "postgis/postgis:16-3.4,false,sealed_route", "postgis/postgis:17-3.5,false,sealed_route",
    "postgis/postgis:16-3.4,true,sealed_route", "postgis/postgis:17-3.5,true,sealed_route",
    "postgis/postgis:16-3.4,false,sealed_route_delete_failure",
        "postgis/postgis:17-3.5,false,sealed_route_delete_failure"
  })
  void 위치_생성_input의_세_후보와_MCP_계보를_함께_제거하고_원래_Day는_보존한다(
      String image, boolean redacted, String candidateShape) throws Exception {
    boolean sealed = !candidateShape.equals("draft");
    boolean forcedFailure = candidateShape.equals("sealed_route_delete_failure");
    boolean withRoute = candidateShape.equals("sealed_route") || forcedFailure;
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID day = UUID.randomUUID(), run = UUID.randomUUID(), input = UUID.randomUUID();
      jdbc.update(
          "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
          day,
          fixture.trip());
      jdbc.update(
          """
          insert into public.itinerary_generation_runs
            (id,trip_plan_id,trip_day_id,base_schedule_version_id,status,structured_input,
             contract_version,algorithm_version,idempotency_key,requested_by_user_id,
             created_at,started_at,completed_at)
          select ?,id,?,?,'succeeded',jsonb_build_object('targetDayId',?::text,
            'candidateCount',3,'refreshExternalFacts',false),'fixture','fixture',?,user_id,
            now()-interval '27 hours',now()-interval '26 hours',now()-interval '25 hours'
          from public.trip_plans where id=?
          """,
          run,
          day,
          fixture.version(),
          day.toString(),
          "generation-" + run,
          fixture.trip());
      jdbc.update(
          """
          insert into public.compute_run_inputs
            (id,generation_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,
             schema_version,contract_version,algorithm_version,structured_input,command_input_hash,
             location_supplied,coarse_location,location_precision_meters,location_policy_version,
             location_observed_at,location_expires_at)
          select ?,id,requested_by_user_id,trip_plan_id,base_schedule_version_id,'itinerary_generation',
            1,contract_version,algorithm_version,structured_input,
            public.compute_command_input_hash('itinerary_generation'::text,1::smallint,contract_version::text,
              algorithm_version::text,base_schedule_version_id::uuid,structured_input::jsonb,true::boolean,?::jsonb),
            true,?::jsonb,100,'1.0.0',now()-interval '26 hours',
            public.compute_run_input_known_expiry(null,id,null,trip_plan_id,now())
          from public.itinerary_generation_runs where id=?
          """,
          input,
          "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}",
          "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}",
          run);
      jdbc.update(
          """
          insert into public.mcp_compute_call_logs
            (generation_run_id,request_id,tool_name,status,contract_version,command_input_hash,
             mcp_input_hash,schema_checksum,request_fact_count,response_fact_count,attempt_no,latency_ms,error_code)
          select generation_run_id,?,'recommend_jeju_day_trips','transport_error','0.7.0',
            command_input_hash,repeat('a',64),repeat('b',64),0,0,1,1,'MCP_TIMEOUT'
          from public.compute_run_inputs where id=?
          """,
          "generation-log-" + input,
          input);
      for (int rank = 1; rank <= 3; rank++) {
        UUID candidateVersion = UUID.randomUUID();
        jdbc.update(
            "insert into public.trip_schedule_versions "
                + "(id,trip_plan_id,version_no,base_schedule_version_id,status,source_type) "
                + "values (?,?,?,?,'draft','ai_generation')",
            candidateVersion,
            fixture.trip(),
            rank + 1,
            fixture.version());
        if (sealed) {
          insertSealedCandidateItemsAndLeg(jdbc, fixture, day, candidateVersion, withRoute);
        }
        jdbc.update(
            "insert into public.itinerary_generation_candidates "
                + "(trip_plan_id,generation_run_id,schedule_version_id,rank_no) values (?,?,?,?)",
            fixture.trip(),
            run,
            candidateVersion,
            rank);
      }
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_items where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isEqualTo(sealed ? 6 : 0);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_legs where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isEqualTo(sealed ? 3 : 0);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mobility_route_snapshots where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isEqualTo(withRoute ? 3 : 0);
      if (redacted) {
        assertThat(
                jdbc.queryForObject(
                    "select public.redact_due_compute_run_input_locations(now(),500)",
                    Integer.class))
            .isEqualTo(1);
      }
      var originalRouteConstraints = routeReferenceConstraints(jdbc);
      assertThat(originalRouteConstraints).hasSize(3);
      if (forcedFailure) {
        jdbc.execute(
            """
            create function public.issue223_refuse_route_delete() returns trigger
            language plpgsql as $$ begin
              raise exception using errcode='23514', message='synthetic purge interruption';
            end; $$;
            create trigger issue223_refuse_route_delete before delete on public.mobility_route_snapshots
            for each row execute function public.issue223_refuse_route_delete();
            """);
        String schema =
            Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
        String data =
            "select md5(jsonb_build_object('versions',(select jsonb_agg(t order by id) from public.trip_schedule_versions t),"
                + "'items',(select jsonb_agg(t order by id) from public.trip_items t),"
                + "'legs',(select jsonb_agg(t order by id) from public.trip_legs t),"
                + "'routes',(select jsonb_agg(t order by id) from public.mobility_route_snapshots t),"
                + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
                + "'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t),"
                + "'runs',(select jsonb_agg(t order by id) from public.itinerary_generation_runs t),"
                + "'candidates',(select jsonb_agg(t order by id) from public.itinerary_generation_candidates t),"
                + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
                + "'logs',(select jsonb_agg(t order by id) from public.mcp_compute_call_logs t))::text)";
        String triggers =
            "select jsonb_agg(jsonb_build_array(t.oid::text,t.tgenabled,t.tgdeferrable,t.tginitdeferred) order by t.oid)::text "
                + "from pg_trigger t join pg_class c on c.oid=t.tgrelid where c.relnamespace='public'::regnamespace";
        String beforeSchema = jdbc.queryForObject(schema, String.class);
        String beforeData = jdbc.queryForObject(data, String.class);
        String beforeTriggers = jdbc.queryForObject(triggers, String.class);
        assertThatThrownBy(
                () ->
                    PostgreSqlTestContainerFactory.executeScript(
                        container,
                        PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("location purge integrity requires audit")
            .hasMessageNotContaining("Failing row");
        assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
        assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
        assertThat(jdbc.queryForObject(triggers, String.class)).isEqualTo(beforeTriggers);
        assertThat(routeReferenceConstraints(jdbc)).isEqualTo(originalRouteConstraints);
        return;
      }
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(routeReferenceConstraints(jdbc)).isEqualTo(originalRouteConstraints);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.itinerary_generation_runs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.itinerary_generation_candidates", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject("select count(*) from public.compute_run_inputs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mcp_compute_call_logs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForList(
                  "select id from public.trip_schedule_versions where trip_plan_id=?",
                  UUID.class,
                  fixture.trip()))
          .containsExactly(fixture.version());
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_days where id=?", Integer.class, day))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_items where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_legs where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mobility_route_snapshots where trip_plan_id=?",
                  Integer.class,
                  fixture.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.tour_places where id=?",
                  Integer.class,
                  fixture.place()))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  // 정리 중 FK 검사를 유예하더라도 원래 OID·정의·검증 상태와 검사 시점을 복원해야 한다.
  private static java.util.List<java.util.Map<String, Object>> routeReferenceConstraints(
      JdbcTemplate jdbc) {
    return jdbc.queryForList(
        """
        select constraint_record.oid::text, conname, condeferrable, condeferred, convalidated,
          pg_get_constraintdef(constraint_record.oid) as definition,
          (select jsonb_agg(jsonb_build_object('oid', trigger_record.oid::text,
            'name', tgname, 'enabled', tgenabled, 'deferrable', tgdeferrable,
            'deferred', tginitdeferred) order by trigger_record.oid)::text
           from pg_trigger trigger_record
           where tgconstraint = constraint_record.oid) as trigger_state
        from pg_constraint constraint_record
        where conrelid = 'public.mobility_route_snapshots'::regclass
          and conname in ('fk_route_planned_version', 'fk_route_planned_origin_item',
            'fk_route_planned_destination_item')
        order by conname
        """);
  }

  private static void insertSealedCandidateItemsAndLeg(
      JdbcTemplate jdbc, Fixture fixture, UUID day, UUID version, boolean withRoute) {
    UUID from = UUID.randomUUID(), to = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.trip_items
          (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
           planned_start_at,planned_end_at,stay_minutes,source,facts)
        values (?,?,?,?,1,'place_visit',?,'2026-09-01T00:00:00Z','2026-09-01T01:00:00Z',60,'ai_generated','{}'),
               (?,?,?,?,2,'place_visit',?,'2026-09-01T02:00:00Z','2026-09-01T03:00:00Z',60,'ai_generated','{}')
        """,
        from,
        fixture.trip(),
        day,
        version,
        fixture.place(),
        to,
        fixture.trip(),
        day,
        version,
        fixture.place());
    jdbc.update(
        """
        insert into public.trip_legs
          (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
           transport_mode,planned_departure_at,planned_arrival_at,walk_minutes,duration_minutes,facts)
        values (?,?,?,1,?,?,'walk','2026-09-01T01:00:00Z','2026-09-01T01:01:00Z',1,1,'{}')
        """,
        fixture.trip(),
        day,
        version,
        from,
        to);
    if (withRoute) {
      UUID snapshot = UUID.randomUUID();
      jdbc.update(
          """
          insert into public.mobility_route_snapshots
            (id,trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,
             origin_anchor_kind,origin_anchor_id,destination_anchor_kind,destination_anchor_id,
             transport_mode,duration_minutes,source_provider,source_operation,expires_at)
          values (?,?,?,?,?,'place',?,'place',?,'walk',1,'fixture','route',now()+interval '1 hour')
          """,
          snapshot,
          fixture.trip(),
          version,
          from,
          to,
          fixture.place(),
          fixture.place());
      jdbc.update(
          "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
          snapshot,
          version);
    }
    jdbc.update("update public.trip_schedule_versions set status='candidate' where id=?", version);
    jdbc.queryForObject(
        "select public.assert_schedule_version_sealable(?,?)",
        Object.class,
        version,
        fixture.trip());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions "
                    + "where id=? and status='candidate'",
                Integer.class,
                version))
        .isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 위치없는_command라도_원문없는_MCP_wire_hash는_안전으로_판정하지_않는다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = UUID.randomUUID(), day = UUID.randomUUID();
      jdbc.update(
          "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
          day,
          fixture.trip());
      String input = "{\"riskEventId\":\"" + UUID.randomUUID() + "\",\"optionCount\":3}";
      String hash =
          jdbc.queryForObject(
              """
          select public.compute_command_input_hash('recovery'::text,1::smallint,
            'fixture'::text,'fixture'::text,?::uuid,?::jsonb,false::boolean,null::jsonb)
          """,
              String.class,
              fixture.version(),
              input);
      jdbc.update(
          """
          insert into public.compute_runs
            (id,trip_plan_id,trip_day_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version)
          values (?,?,?,?,'recovery','queued',?,'fixture','fixture')
          """,
          run,
          fixture.trip(),
          day,
          fixture.version(),
          hash);
      jdbc.update(
          """
          insert into public.compute_run_inputs
            (compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,schema_version,
             contract_version,algorithm_version,structured_input,command_input_hash,location_supplied)
          select ?,user_id,id,?,'recovery',1,'fixture','fixture',?::jsonb,?,false
          from public.trip_plans where id=?
          """,
          run,
          fixture.version(),
          input,
          hash,
          fixture.trip());
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String lineage =
          "select md5(jsonb_build_object('runs',(select jsonb_agg(t order by id) from public.compute_runs t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'logs',(select jsonb_agg(t order by id) from public.mcp_compute_call_logs t),"
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t))::text)";
      for (String status : java.util.List.of("succeeded", "transport_error")) {
        UUID log = UUID.randomUUID();
        // 0.7 permits current_position independently of command input; no wire body remains.
        jdbc.update(
            """
            insert into public.mcp_compute_call_logs
              (id,compute_run_id,request_id,tool_name,status,contract_version,command_input_hash,mcp_input_hash,
               schema_checksum,request_fact_count,response_fact_count,attempt_no,latency_ms,error_code)
            values (?,?,?,'revalidate_jeju_day_trip',?,'0.7.0',?,repeat('a',64),repeat('b',64),
              0,0,1,1,case when ?='succeeded' then null else 'MCP_TIMEOUT' end)
            """,
            log,
            run,
            "unclassified-wire-" + log,
            status,
            hash,
            status);
        String beforeSchema = jdbc.queryForObject(schema, String.class);
        String beforeData = jdbc.queryForObject(lineage, String.class);
        assertThatThrownBy(
                () ->
                    PostgreSqlTestContainerFactory.executeScript(
                        container,
                        PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
            .as(status)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user location residue requires audit")
            .hasMessageNotContaining(hash)
            .hasMessageNotContaining("a".repeat(64))
            .hasMessageNotContaining("Failing row");
        assertThat(jdbc.queryForObject(schema, String.class)).as(status).isEqualTo(beforeSchema);
        assertThat(jdbc.queryForObject(lineage, String.class)).as(status).isEqualTo(beforeData);
        // Only remove this synthetic log to exercise the next isolated case.
        jdbc.update("delete from public.mcp_compute_call_logs where id=?", log);
      }
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where compute_run_id=?",
                  Integer.class,
                  run))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select input_hash from public.compute_runs where id=?", String.class, run))
          .isEqualTo(hash);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 생성_메시지는_대화_owner와_trip이_일치하는_생성_출력만_정리한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID owner =
          jdbc.queryForObject(
              "select user_id from public.trip_plans where id=?", UUID.class, fixture.trip());
      UUID run = insertGenerationLocationInput(jdbc, fixture);
      UUID conversation = UUID.randomUUID(), message = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID(), otherTrip = UUID.randomUUID();
      String email = otherOwner + "@issue223-message.test";
      jdbc.update("insert into auth.users(id,email) values (?,?)", otherOwner, email);
      jdbc.update("insert into public.user_profiles(id,email) values (?,?)", otherOwner, email);
      jdbc.update(
          """
          insert into public.trip_plans(id,user_id,public_token,start_date,end_date,source_mode,data_version)
          values (?,?,?,'2026-09-01','2026-09-01','fixture','fixture')
          """,
          otherTrip,
          owner,
          "message-trip-" + otherTrip);
      jdbc.update(
          "insert into public.ai_conversations(id,user_id,trip_plan_id) values (?,?,?)",
          conversation,
          owner,
          fixture.trip());
      jdbc.update(
          """
          insert into public.ai_messages(id,conversation_id,role,content,generation_run_id)
          values (?,?,'assistant','synthetic generated message',?)
          """,
          message,
          conversation,
          run);
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('messages',(select jsonb_agg(t order by id) from public.ai_messages t),"
              + "'conversations',(select jsonb_agg(t order by id) from public.ai_conversations t),"
              + "'runs',(select jsonb_agg(t order by id) from public.itinerary_generation_runs t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
              + "'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t))::text)";
      for (String kind :
          java.util.List.of("user", "system", "other_owner", "other_trip", "no_trip")) {
        jdbc.update(
            "update public.ai_conversations set user_id=?,trip_plan_id=? where id=?",
            kind.equals("other_owner") ? otherOwner : owner,
            kind.equals("no_trip") ? null : kind.equals("other_trip") ? otherTrip : fixture.trip(),
            conversation);
        jdbc.update(
            "update public.ai_messages set role=? where id=?",
            kind.equals("user") || kind.equals("system") ? kind : "assistant",
            message);
        String beforeSchema = jdbc.queryForObject(schema, String.class);
        String beforeData = jdbc.queryForObject(data, String.class);
        assertThatThrownBy(
                () ->
                    PostgreSqlTestContainerFactory.executeScript(
                        container,
                        PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
            .as(kind)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("external location lineage requires audit")
            .hasMessageNotContaining(message.toString())
            .hasMessageNotContaining("Failing row");
        assertThat(jdbc.queryForObject(schema, String.class)).as(kind).isEqualTo(beforeSchema);
        assertThat(jdbc.queryForObject(data, String.class)).as(kind).isEqualTo(beforeData);
      }
      jdbc.update(
          "update public.ai_conversations set user_id=?,trip_plan_id=? where id=?",
          owner,
          fixture.trip(),
          conversation);
      jdbc.update("update public.ai_messages set role='assistant' where id=?", message);
      jdbc.update(
          """
          insert into public.ai_messages(conversation_id,role,content,generation_run_id)
          values (?,'tool','synthetic tool message',?)
          """,
          conversation,
          run);
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(jdbc.queryForObject("select count(*) from public.ai_messages", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.itinerary_generation_runs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject("select count(*) from public.compute_run_inputs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.ai_conversations where id=?",
                  Integer.class,
                  conversation))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_plans where id in (?,?)",
                  Integer.class,
                  fixture.trip(),
                  otherTrip))
          .isEqualTo(2);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 출처를_입증할_수_없는_멱등성_hash와_응답은_삭제하거나_안전으로_판정하지_않는다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID owner =
          jdbc.queryForObject(
              "select user_id from public.trip_plans where id=?", UUID.class, fixture.trip());
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('receipts',(select jsonb_agg(t order by idempotency_key) from public.api_idempotency_records t),"
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
              + "'live',(select jsonb_agg(t order by id) from public.live_state_snapshots t))::text)";
      for (String state : java.util.List.of("PROCESSING", "COMPLETED")) {
        UUID key = UUID.randomUUID();
        jdbc.update(
            """
            insert into public.api_idempotency_records
              (owner_sub,http_method,normalized_path,idempotency_key,request_hash,attempt_token,state,
               response_status,response_headers,response_body,created_at,lease_expires_at,completed_at,expires_at)
            values (?,'POST',?,?,repeat('c',64),?,?,
              case when ? then 200 end,case when ? then decode('ff00','hex') end,
              case when ? then decode('ff00','hex') end,now(),
              case when ? then null else now()+interval '2 minutes' end,
              case when ? then now() end,now()+interval '24 hours')
            """,
            owner,
            "/api/v1/trips/" + fixture.trip() + "/feasibility-runs",
            key,
            UUID.randomUUID(),
            state,
            state.equals("COMPLETED"),
            state.equals("COMPLETED"),
            state.equals("COMPLETED"),
            state.equals("COMPLETED"),
            state.equals("COMPLETED"));
        String beforeSchema = jdbc.queryForObject(schema, String.class);
        String beforeData = jdbc.queryForObject(data, String.class);
        assertThatThrownBy(
                () ->
                    PostgreSqlTestContainerFactory.executeScript(
                        container,
                        PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
            .as(state)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user location residue requires audit")
            .hasMessageNotContaining("c".repeat(64))
            .hasMessageNotContaining("Failing row");
        assertThat(jdbc.queryForObject(schema, String.class)).as(state).isEqualTo(beforeSchema);
        assertThat(jdbc.queryForObject(data, String.class)).as(state).isEqualTo(beforeData);
        // Remove only this synthetic receipt to exercise the next isolated case.
        jdbc.update(
            "delete from public.api_idempotency_records where owner_sub=? and idempotency_key=?",
            owner,
            key);
      }
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      assertThat(
              jdbc.queryForObject(
                  "select coalesce(sum(residue_count),0) from timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select timing_jeju_planner_private.user_location_guard_purge_revision()",
                  String.class))
          .isEqualTo("20260918000017");
      for (String role : java.util.List.of("anon", "authenticated", "service_role")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_function_privilege(?, 'timing_jeju_planner_private.user_location_guard_purge_revision()', 'EXECUTE')",
                    Boolean.class,
                    role))
            .isFalse();
        assertThat(
                jdbc.queryForObject(
                    "select has_function_privilege(?, 'timing_jeju_planner_private.user_location_residue_counts()', 'EXECUTE')",
                    Boolean.class,
                    role))
            .isFalse();
      }
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 일반_JSON의_geohash_잔여량은_자동_삭제없이_전환을_중단한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      Fixture fixture = insertLegacyFixture(jdbc, false);
      var transaction =
          new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
      transaction.executeWithoutResult(
          status -> {
            jdbc.update(
                """
            insert into public.trip_preferences(trip_plan_id,arrival_region_code,departure_region_code,raw_answers)
            values (?,'JEJU','JEJU','{"nested":[{"Geo-Hash":"private-derived-marker"}]}'::jsonb)
            """,
                fixture.trip());
            jdbc.update(
                """
            insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary)
            values (?,'public_transit',1,true)
            """,
                fixture.trip());
          });
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object('preferences',(select jsonb_agg(t order by trip_plan_id) from public.trip_preferences t),"
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("user location residue requires audit")
          .hasMessageNotContaining("private-derived-marker")
          .hasMessageNotContaining("Failing row");
      assertThat(jdbc.queryForObject(schema, String.class)).isEqualTo(beforeSchema);
      assertThat(jdbc.queryForObject(data, String.class)).isEqualTo(beforeData);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 현재_seed는_미분류_wire_hash없이_정상_일정과_입력만_제공한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      PostgreSqlTestContainerFactory.executeScript(
          container, PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/local-postgres/seed_fixtures.sql"));
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.mcp_compute_call_logs", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select sum(residue_count) from timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
      assertThat(
              jdbc.queryForObject("select count(*) from public.compute_run_inputs", Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_plans where active_schedule_version_id is not null",
                  Integer.class))
          .isPositive();
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 정상_command도_독립_revision_request_hash의_비위치를_증명하지_못한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var dataSource =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(dataSource);
      Fixture fixture = insertLegacyFixture(jdbc, false);
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/local-postgres/20260918000017_location_cutover_group.sql"));
      UUID run = insertNormalRevisionInput(jdbc, dataSource, fixture);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where schedule_revision_run_id=? and not location_supplied",
                  Integer.class,
                  run))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select coalesce(sum(residue_count),0) from timing_jeju_planner_private.user_location_residue_counts() "
                      + "where object_name='unclassified_schedule_revision_request_hashes'",
                  Long.class))
          .isEqualTo(1);
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 미분류_revision_hash는_017_정리까지_같은_transaction에서_되돌린다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var dataSource =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(dataSource);
      Fixture fixture = insertLegacyFixture(jdbc, false);
      insertNormalRevisionInput(jdbc, dataSource, fixture);
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object("
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
              + "'live',(select jsonb_agg(t order by trip_plan_id) from public.live_state_snapshots t),"
              + "'inputs',(select jsonb_agg(t order by id) from public.compute_run_inputs t),"
              + "'runs',(select jsonb_agg(t order by id) from public.schedule_revision_runs t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      assertThatThrownBy(
              () ->
                  PostgreSqlTestContainerFactory.executeScript(
                      container,
                      root.resolve("db/local-postgres/20260918000017_location_cutover_group.sql")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("user location residue requires audit")
          .hasMessageNotContaining("Failing row");
      assertThat(beforeSchema.equals(jdbc.queryForObject(schema, String.class))).isTrue();
      assertThat(beforeData.equals(jdbc.queryForObject(data, String.class))).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is null",
                  Boolean.class))
          .isTrue();
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void Supabase_이력은_감사_실패시_유지하고_성공시에만_세_버전을_같이_등록한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID run = insertNormalRevisionInput(jdbc, source, fixture);
      jdbc.execute("create schema supabase_migrations");
      jdbc.execute(
          "create table supabase_migrations.schema_migrations(version text primary key, name text, statements text[])");
      try (var files = Files.list(root.resolve("supabase/migrations"))) {
        for (var file :
            files
                .filter(path -> path.getFileName().toString().matches("[0-9]{14}_.+[.]sql"))
                .filter(path -> path.getFileName().toString().compareTo(TARGET) < 0)
                .sorted()
                .toList()) {
          jdbc.update(
              "insert into supabase_migrations.schema_migrations(version) values (?)",
              file.getFileName().toString().substring(0, 14));
        }
      }
      var script = root.resolve("db/local-postgres/location_cutover_supabase.sql");
      assertThat(
              jdbc.update(
                  "delete from supabase_migrations.schema_migrations where version='20260918000016'"))
          .isEqualTo(1);
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, script))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("location cutover migration history mismatch");
      jdbc.update(
          "insert into supabase_migrations.schema_migrations(version) values ('20260918000016'),('20260918000017')");
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, script))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("location cutover migration history mismatch");
      assertThat(
              jdbc.update(
                  "delete from supabase_migrations.schema_migrations where version='20260918000017'"))
          .isEqualTo(1);
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, script))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("user location residue requires audit");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from supabase_migrations.schema_migrations where version >= '20260918000017'",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_execution_events where location is not null",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is null",
                  Boolean.class))
          .isTrue();
      // 이 테스트가 만든 합성 미분류 행만 제거해 별도의 정상 적용 입력을 준비한다.
      jdbc.update("delete from public.compute_run_inputs where schedule_revision_run_id=?", run);
      jdbc.update("delete from public.schedule_revision_runs where id=?", run);
      jdbc.execute(
          "alter table supabase_migrations.schema_migrations add constraint fixture_history_write_failure check (version < '20260918000017')");
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, script))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("fixture_history_write_failure");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from supabase_migrations.schema_migrations where version >= '20260918000017'",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_execution_events where location is not null",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is null",
                  Boolean.class))
          .isTrue();
      jdbc.execute(
          "alter table supabase_migrations.schema_migrations drop constraint fixture_history_write_failure");
      PostgreSqlTestContainerFactory.executeScript(container, script);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from supabase_migrations.schema_migrations where version in ('20260918000017','20260918000018','20260918000020')",
                  Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from supabase_migrations.schema_migrations "
                      + "where version in ('20260918000017','20260918000018','20260918000020') "
                      + "and name is not null and statements is not null",
                  Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select timing_jeju_planner_private.user_location_guard_purge_revision()",
                  String.class))
          .isEqualTo("20260918000020");
      assertThat(
              jdbc.queryForObject(
                  "select sum(residue_count) from timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, script))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("location cutover migration history mismatch");
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "postgis/postgis:16-3.4,timeout", "postgis/postgis:17-3.5,timeout",
    "postgis/postgis:16-3.4,disconnect", "postgis/postgis:17-3.5,disconnect",
    "postgis/postgis:16-3.4,lock", "postgis/postgis:17-3.5,lock"
  })
  void 그룹_중간_timeout과_연결_종료는_017의_데이터와_schema를_복구한다(String image, String mode) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var source =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(source);
      insertLegacyFixture(jdbc, false);
      String schema =
          Files.readString(root.resolve("db/queries/canonical_migration_fingerprint.sql"));
      String data =
          "select md5(jsonb_build_object("
              + "'events',(select jsonb_agg(t order by id) from public.trip_execution_events t),"
              + "'live',(select jsonb_agg(t order by trip_plan_id) from public.live_state_snapshots t))::text)";
      String beforeSchema = jdbc.queryForObject(schema, String.class);
      String beforeData = jdbc.queryForObject(data, String.class);
      String original =
          Files.readString(
              root.resolve("db/local-postgres/20260918000017_location_cutover_group.sql"));
      String marker = "-- Issue #223: an opaque revision request hash";
      assertThat(original.indexOf(marker)).isPositive();
      String delayed =
          original.replace(
              marker,
              (mode.equals("timeout") ? "set local statement_timeout='100ms';\n" : "")
                  + "select pg_sleep(30);\n"
                  + marker);
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        if (mode.equals("timeout")) {
          assertThatThrownBy(() -> statement.execute(delayed))
              .isInstanceOf(java.sql.SQLException.class)
              .satisfies(
                  failure ->
                      assertThat(((java.sql.SQLException) failure).getSQLState())
                          .isEqualTo("57014"));
        } else if (mode.equals("lock")) {
          try (var blocker = source.getConnection();
              var blocked = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            blocked.execute("lock table public.schedule_revision_runs in row exclusive mode");
            statement.execute("set lock_timeout='100ms'");
            try {
              assertThatThrownBy(() -> statement.execute(original))
                  .isInstanceOf(java.sql.SQLException.class)
                  .satisfies(
                      failure ->
                          assertThat(((java.sql.SQLException) failure).getSQLState())
                              .isEqualTo("55P03"));
            } finally {
              blocker.rollback();
            }
          }
        } else {
          int pid;
          try (var result = statement.executeQuery("select pg_backend_pid()")) {
            result.next();
            pid = result.getInt(1);
          }
          try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future =
                executor.submit(
                    () -> {
                      try {
                        statement.execute(delayed);
                        return false;
                      } catch (java.sql.SQLException expected) {
                        return true;
                      }
                    });
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            boolean sleeping = false;
            while (System.nanoTime() < deadline) {
              sleeping =
                  Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "select exists(select 1 from pg_stat_activity where pid=? and wait_event='PgSleep')",
                          Boolean.class,
                          pid));
              if (sleeping) break;
              Thread.sleep(25);
            }
            assertThat(sleeping).isTrue();
            assertThat(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid))
                .isTrue();
            assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          }
        }
      }
      assertThat(beforeSchema.equals(jdbc.queryForObject(schema, String.class))).isTrue();
      assertThat(beforeData.equals(jdbc.queryForObject(data, String.class))).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is null",
                  Boolean.class))
          .isTrue();
    } finally {
      container.stop();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void 위치_계보가_입증된_종료_revision은_원본_일정을_남기고_그룹에서_정리한다(String image) throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      var jdbc =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      Fixture fixture = insertLegacyFixture(jdbc, false);
      UUID day = UUID.randomUUID(), run = UUID.randomUUID();
      jdbc.update(
          "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
          day,
          fixture.trip());
      jdbc.update(
          """
          insert into public.schedule_revision_runs
            (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
             contract_version,algorithm_version,idempotency_key,request_hash,status,
             failure_code,completed_at,next_attempt_at)
          select ?,user_id,id,?,?,'fixture','fixture',?,repeat('b',64),'queued',
            null,null,now() from public.trip_plans where id=?
          """,
          run,
          fixture.version(),
          day,
          UUID.randomUUID(),
          fixture.trip());
      jdbc.update(
          "update public.schedule_revision_runs set status='failed',failure_code='FIXTURE_FAILURE',completed_at=now(),next_attempt_at=null where id=?",
          run);
      jdbc.update(
          """
          insert into public.compute_run_inputs
            (schedule_revision_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
             run_type,schema_version,contract_version,algorithm_version,structured_input,
             command_input_hash,location_supplied,coarse_location,location_precision_meters,
             location_policy_version,location_observed_at,location_expires_at)
          select run.id,run.owner_user_id,run.trip_plan_id,run.base_schedule_version_id,
            'schedule_revision',1,run.contract_version,run.algorithm_version,input,
            public.compute_command_input_hash('schedule_revision'::text,1::smallint,
              run.contract_version::text,run.algorithm_version::text,
              run.base_schedule_version_id::uuid,input::jsonb,true::boolean,coarse::jsonb),
            true,coarse,100,'1.0.0',now(),
            public.compute_run_input_known_expiry(null,null,run.id,run.trip_plan_id,now())
          from public.schedule_revision_runs run cross join lateral
            (select jsonb_build_object('targetDayId',run.target_trip_day_id::text,
              'affectedItemIds','[]'::jsonb,'instructionCodes','[]'::jsonb) as input,
              '{"type":"GRID_100M","gridX":53,"gridY":38}'::jsonb as coarse) command
          where run.id=?
          """,
          run);
      PostgreSqlTestContainerFactory.executeScript(
          container, root.resolve("db/local-postgres/20260918000017_location_cutover_group.sql"));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.schedule_revision_runs where id=?",
                  Integer.class,
                  run))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs where schedule_revision_run_id=?",
                  Integer.class,
                  run))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where id=?",
                  Integer.class,
                  fixture.version()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select sum(residue_count) from timing_jeju_planner_private.user_location_residue_counts()",
                  Long.class))
          .isZero();
    } finally {
      container.stop();
    }
  }

  private static UUID insertNormalRevisionInput(
      JdbcTemplate jdbc, DriverManagerDataSource dataSource, Fixture fixture) {
    UUID day = UUID.randomUUID(), run = UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource))
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
                  day,
                  fixture.trip());
              jdbc.update(
                  """
                insert into public.schedule_revision_runs
                  (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
                   contract_version,algorithm_version,idempotency_key,request_hash)
                select ?,user_id,id,?,?,'fixture','fixture',?,repeat('a',64)
                from public.trip_plans where id=?
                """,
                  run,
                  fixture.version(),
                  day,
                  UUID.randomUUID(),
                  fixture.trip());
              jdbc.update(
                  """
                insert into public.compute_run_inputs
                  (schedule_revision_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
                   run_type,schema_version,contract_version,algorithm_version,structured_input,
                   command_input_hash,location_supplied)
                select run.id,run.owner_user_id,run.trip_plan_id,run.base_schedule_version_id,
                  'schedule_revision',1,run.contract_version,run.algorithm_version,input,
                  public.compute_command_input_hash('schedule_revision'::text,1::smallint,
                    run.contract_version::text,run.algorithm_version::text,
                    run.base_schedule_version_id::uuid,input::jsonb,false::boolean,null::jsonb),false
                from public.schedule_revision_runs run cross join lateral
                  (select jsonb_build_object('targetDayId',run.target_trip_day_id::text,
                    'affectedItemIds','[]'::jsonb,'instructionCodes','[]'::jsonb) as input) command
                where run.id=?
                """,
                  run);
            });
    return run;
  }

  private static UUID insertGenerationLocationInput(JdbcTemplate jdbc, Fixture fixture) {
    UUID day = UUID.randomUUID(), run = UUID.randomUUID();
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
        day,
        fixture.trip());
    jdbc.update(
        """
        insert into public.itinerary_generation_runs
          (id,trip_plan_id,trip_day_id,base_schedule_version_id,status,structured_input,
           contract_version,algorithm_version,idempotency_key,requested_by_user_id,created_at,started_at,completed_at)
        select ?,id,?,?,'succeeded',jsonb_build_object('targetDayId',?::text,
          'candidateCount',3,'refreshExternalFacts',false),'fixture','fixture',?,user_id,
          now()-interval '27 hours',now()-interval '26 hours',now()-interval '25 hours'
        from public.trip_plans where id=?
        """,
        run,
        day,
        fixture.version(),
        day.toString(),
        "message-generation-" + run,
        fixture.trip());
    jdbc.update(
        """
        insert into public.compute_run_inputs
          (generation_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,
           schema_version,contract_version,algorithm_version,structured_input,command_input_hash,
           location_supplied,coarse_location,location_precision_meters,location_policy_version,
           location_observed_at,location_expires_at)
        select id,requested_by_user_id,trip_plan_id,base_schedule_version_id,'itinerary_generation',
          1,contract_version,algorithm_version,structured_input,
          public.compute_command_input_hash('itinerary_generation'::text,1::smallint,contract_version::text,
            algorithm_version::text,base_schedule_version_id::uuid,structured_input::jsonb,true::boolean,?::jsonb),
          true,?::jsonb,100,'1.0.0',now()-interval '26 hours',
          public.compute_run_input_known_expiry(null,id,null,trip_plan_id,now())
        from public.itinerary_generation_runs where id=?
        """,
        "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}",
        "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}",
        run);
    return run;
  }

  private static UUID insertTerminalLocationInput(
      JdbcTemplate jdbc, Fixture fixture, boolean redacted) {
    UUID run = UUID.randomUUID();
    UUID input = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.compute_runs
          (id,trip_plan_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version,
           created_at,started_at,completed_at,facts_snapshot_at,source_data_version)
        values (?,?,?,'feasibility','failed',
                public.compute_command_input_hash('feasibility'::text,1::smallint,'fixture'::text,'fixture'::text,?::uuid,
                  '{"refreshExternalFacts":false}'::jsonb,true::boolean,?::jsonb),'fixture','fixture',
                now()-interval '27 hours',now()-interval '26 hours',now()-interval '25 hours',
                now()-interval '26 hours','fixture')
        """,
        run,
        fixture.trip(),
        fixture.version(),
        fixture.version(),
        "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}");
    jdbc.update(
        """
        with command as (select ?::jsonb as coarse, '{"refreshExternalFacts":false}'::jsonb as input)
        insert into public.compute_run_inputs
          (id,compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,schema_version,
           contract_version,algorithm_version,structured_input,command_input_hash,location_supplied,
           coarse_location,location_precision_meters,location_policy_version,location_observed_at,location_expires_at)
        select ?,?,trip.user_id,trip.id,?,'feasibility',1,'fixture','fixture',input,
          public.compute_command_input_hash('feasibility'::text,1::smallint,'fixture'::text,'fixture'::text,?::uuid,input::jsonb,true::boolean,coarse::jsonb),
          true,coarse,100,'1.0.0',now()-interval '26 hours',
          public.compute_run_input_known_expiry(?,null,null,trip.id,now())
        from command cross join public.trip_plans trip where trip.id=?
        """,
        "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}",
        input,
        run,
        fixture.version(),
        fixture.version(),
        run,
        fixture.trip());
    jdbc.update(
        """
        insert into public.mcp_compute_call_logs
          (compute_run_id,request_id,tool_name,status,contract_version,command_input_hash,mcp_input_hash,
           schema_checksum,request_fact_count,response_fact_count,attempt_no,latency_ms,error_code)
        select compute_run_id,?,'evaluate_jeju_day_trip','transport_error','0.7.0',command_input_hash,
               repeat('a',64),repeat('b',64),0,0,1,1,'MCP_TIMEOUT'
        from public.compute_run_inputs where id=?
        """,
        "legacy-location-" + input,
        input);
    if (redacted) {
      assertThat(
              jdbc.queryForObject(
                  "select public.redact_due_compute_run_input_locations(now(),500)", Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.compute_run_inputs "
                      + "where id=? and location_supplied and coarse_location is null and command_input_hash is not null",
                  Integer.class,
                  input))
          .isEqualTo(1);
    }
    return run;
  }

  private static Fixture insertLegacyFixture(JdbcTemplate jdbc, boolean ambiguous) {
    UUID owner = UUID.randomUUID();
    UUID trip = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID place = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    String email = owner + "@issue223-purge.test";
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
    jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
    jdbc.update(
        "insert into public.trip_plans "
            + "(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision) "
            + "values (?,?,?,'legacy purge','draft','2026-09-01','2026-09-01','fixture','issue223',1)",
        trip,
        owner,
        "purge-" + trip);
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) "
            + "values (?,?,1,'draft','initial')",
        version,
        trip);
    jdbc.update(
        "insert into public.tour_places(id,name,normalized_name,category,location,source_provider) "
            + "values (?,'공개 장소','공개 장소','ATTRACTION',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture')",
        place);
    String metadata =
        ambiguous
            ? "{\"source\":\"manual\",\"note\":\"private-manual-note\"}"
            : "{\"source\":\"manual\",\"accuracyMeters\":18,\"nested\":{\"gridX\":53,\"locationDigest\":\"private-location-marker\"}}";
    jdbc.update(
        "insert into public.trip_execution_events "
            + "(id,trip_plan_id,schedule_version_id,event_type,client_event_id,occurred_at,location,metadata) "
            + "values (?,?,?,'trip_started','legacy-event',now(),ST_GeogFromText(?),?::jsonb)",
        event,
        trip,
        version,
        "SRID=4326;POINT(126.51 33.51)",
        metadata);
    jdbc.update(
        "insert into public.live_state_snapshots "
            + "(trip_plan_id,schedule_version_id,status,current_location,current_place_id,next_action,facts) "
            + "values (?,?,'yellow',ST_GeogFromText(?),?,?,'{}'::jsonb)",
        trip,
        version,
        "SRID=4326;POINT(126.51 33.51)",
        place,
        "private-location-marker");
    return new Fixture(trip, version, place, event);
  }

  private record Fixture(UUID trip, UUID version, UUID place, UUID event) {}
}
