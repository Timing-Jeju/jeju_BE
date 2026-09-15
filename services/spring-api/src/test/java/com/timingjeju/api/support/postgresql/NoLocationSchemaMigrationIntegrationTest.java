package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 위치 비수집 전환은 기존 무위치 입력을 보존하고 불명확한 실행에서는 원자적으로 중단한다. */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class NoLocationSchemaMigrationIntegrationTest {
  private static final String TARGET = "20260918000020_remove_user_location_runtime.sql";
  private static final String INPUT = "{\"refreshExternalFacts\":false}";
  private static final String TEMPLATE = "issue224_predecessor";
  private static final java.util.Map<String, org.testcontainers.postgresql.PostgreSQLContainer>
      CONTAINERS = new java.util.LinkedHashMap<>();

  @AfterAll
  static void stopOwnedContainers() {
    RuntimeException failure = null;
    for (var container : CONTAINERS.values()) {
      try {
        container.stop();
      } catch (RuntimeException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    }
    CONTAINERS.clear();
    if (failure != null) throw failure;
  }

  private static DatabaseCase freshDatabase(String image) throws Exception {
    var container = CONTAINERS.get(image);
    if (container == null) {
      container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
      try {
        container.start();
        databaseCommand(container, "createdb", "--template", container.getDatabaseName(), TEMPLATE);
        databaseCommand(container, "dropdb", container.getDatabaseName());
        CONTAINERS.put(image, container);
      } catch (Exception | AssertionError error) {
        try {
          container.stop();
        } catch (RuntimeException cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    }
    databaseCommand(container, "createdb", "--template", TEMPLATE, container.getDatabaseName());
    return new DatabaseCase(container);
  }

  private static void databaseCommand(
      org.testcontainers.postgresql.PostgreSQLContainer container, String operation, String... args)
      throws Exception {
    var command =
        new java.util.ArrayList<>(
            java.util.List.of(operation, "--username", container.getUsername()));
    command.addAll(java.util.List.of(args));
    assertThat(container.execInContainer(command.toArray(String[]::new)).getExitCode())
        .as("isolated fixture database %s", operation)
        .isZero();
  }

  private record DatabaseCase(org.testcontainers.postgresql.PostgreSQLContainer container)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      databaseCommand(container, "dropdb", container.getDatabaseName());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void v1_무위치_input과_부모를_함께_v2로_변환하고_공개_장소는_보존한다(String image) throws Exception {
    try (var database = freshDatabase(image)) {
      var container = database.container();
      var jdbc = jdbc(container);
      var fixture = fixture(jdbc);
      String before = immutableInput(jdbc, fixture.input());
      String placeBefore = publicPlace(jdbc, fixture.place());
      jdbc.execute(
          """
          create aggregate public.issue224_public_sum(integer)
            (sfunc=pg_catalog.int4pl, stype=integer, initcond='0')
          """);
      jdbc.execute(
          """
          create function timing_jeju_planner_private.issue224_public_location()
          returns text language plpgsql set search_path='' as $$
          declare result text;
          begin select place.location::text into result from public.tour_places place limit 1;
            return result;
          end; $$;
          """);

      PostgreSqlTestContainerFactory.executeScript(container, migration());

      var mapper = new ObjectMapper();
      var expected =
          new CommandInputCanonicalizer(mapper)
              .canonicalize(
                  new CommandInputRequest(
                      new CommandInputParent.Compute(fixture.run()),
                      "feasibility",
                      2,
                      "fixture/v2",
                      "fixture/v2",
                      mapper.readTree(INPUT),
                      fixture.owner(),
                      fixture.trip(),
                      fixture.base()));
      assertThat(
              jdbc.queryForObject(
                  "select schema_version from public.compute_run_inputs where id=?",
                  Integer.class,
                  fixture.input()))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "select command_input_hash from public.compute_run_inputs where id=?",
                  String.class,
                  fixture.input()))
          .isEqualTo(expected.commandInputHash())
          .isNotEqualTo(fixture.oldHash());
      assertThat(
              jdbc.queryForObject(
                  "select input_hash from public.compute_runs where id=?",
                  String.class,
                  fixture.run()))
          .isEqualTo(expected.commandInputHash());
      assertThat(immutableInput(jdbc, fixture.input())).isEqualTo(before);
      assertThat(publicPlace(jdbc, fixture.place())).isEqualTo(placeBefore);
      assertThat(
              jdbc.queryForObject(
                  "select public.issue224_public_sum(value) from (values (1),(2),(3))"
                      + " inputs(value)",
                  Integer.class))
          .isEqualTo(6);
      assertThat(
              jdbc.queryForObject(
                  "select timing_jeju_planner_private.issue224_public_location()", String.class))
          .isNotBlank();
      assertThat(
              jdbc.queryForObject(
                  """
                  select count(*) from information_schema.columns where table_schema='public'
                    and ((table_name='compute_run_inputs' and column_name in
                      ('location_supplied','coarse_location','location_precision_meters','location_policy_version',
                       'location_observed_at','location_expires_at','location_redacted_at'))
                      or (table_name='trip_execution_events' and column_name='location')
                      or (table_name='live_state_snapshots' and column_name in ('current_location','current_place_id')))
                  """,
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  """
                  select to_regprocedure('public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb,boolean,jsonb)') is null
                    and to_regprocedure('public.redact_due_compute_run_input_locations(timestamptz,integer)') is null
                    and to_regprocedure('public.shorten_compute_run_input_location_expiry(uuid,timestamptz)') is null
                    and to_regprocedure('public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb)') is not null
                  """,
                  Boolean.class))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  """
                  select not has_table_privilege('service_role','public.compute_run_inputs','UPDATE')
                    and not has_table_privilege('service_role','public.compute_run_inputs','DELETE')
                    and has_table_privilege('service_role','public.compute_run_inputs','SELECT')
                    and has_table_privilege('service_role','public.compute_run_inputs','INSERT')
                    and not has_function_privilege('anon',
                      'public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb)','EXECUTE')
                    and not has_function_privilege('authenticated',
                      'public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb)','EXECUTE')
                    and not has_function_privilege('service_role',
                      'public.compute_command_input_hash(text,smallint,text,text,uuid,jsonb)','EXECUTE')
                  """,
                  Boolean.class))
          .isTrue();
      // Current Docker fixtures and SQL contracts must also use the v2 schema.
      for (String script :
          java.util.List.of(
              "db/local-postgres/seed_fixtures.sql",
              "db/queries/schema_contract.sql",
              "db/queries/database_negative_constraints.sql")) {
        PostgreSqlTestContainerFactory.executeScript(
            container, PostgreSqlTestContainerFactory.locateRepositoryRoot().resolve(script));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void v2_MCP_감사에는_독립_wire_hash_열을_남기지_않는다(String image) throws Exception {
    try (var database = freshDatabase(image)) {
      var container = database.container();
      fixture(jdbc(container));
      PostgreSqlTestContainerFactory.executeScript(container, migration());

      assertThat(
              jdbc(container)
                  .queryForObject(
                      """
                      select count(*) from information_schema.columns where table_schema='public'
                        and table_name='mcp_compute_call_logs' and column_name='mcp_input_hash'
                      """,
                      Integer.class))
          .isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgis/postgis:16-3.4", "postgis/postgis:17-3.5"})
  void v2_revision_request_hash는_closed_command_hash와_다르면_거부한다(String image) throws Exception {
    try (var database = freshDatabase(image)) {
      var container = database.container();
      var jdbc = jdbc(container);
      var fixture = fixture(jdbc);
      PostgreSqlTestContainerFactory.executeScript(container, migration());
      UUID revision = UUID.randomUUID(), input = UUID.randomUUID();
      String structuredInput =
          "{\"targetDayId\":\""
              + fixture.day()
              + "\",\"affectedItemIds\":[],\"instructionCodes\":[]}";
      String commandHash =
          jdbc.queryForObject(
              """
              select public.compute_command_input_hash(
                'schedule_revision'::text,2::smallint,'fixture/v2'::text,'fixture/v2'::text,?::uuid,?::jsonb)
              """,
              String.class,
              fixture.base(),
              structuredInput);

      assertThatThrownBy(
              () ->
                  new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                      .executeWithoutResult(
                          status -> {
                            jdbc.update(
                                """
                                insert into public.schedule_revision_runs
                                  (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
                                   contract_version,algorithm_version,idempotency_key,request_hash)
                                values (?,?,?,?,?,'fixture/v2','fixture/v2',?,repeat('0',64))
                                """,
                                revision,
                                fixture.owner(),
                                fixture.trip(),
                                fixture.base(),
                                fixture.day(),
                                UUID.randomUUID());
                            jdbc.update(
                                """
                                insert into public.compute_run_inputs
                                  (id,schedule_revision_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
                                   run_type,schema_version,contract_version,algorithm_version,structured_input,
                                   command_input_hash)
                                values (?,?,?,?,?,'schedule_revision',2,'fixture/v2','fixture/v2',?::jsonb,?)
                                """,
                                input,
                                revision,
                                fixture.owner(),
                                fixture.trip(),
                                fixture.base(),
                                structuredInput,
                                commandHash);
                          }))
          .isInstanceOf(RuntimeException.class);

      new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
          .executeWithoutResult(
              status -> {
                jdbc.update(
                    """
                    insert into public.schedule_revision_runs
                      (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
                       contract_version,algorithm_version,idempotency_key,request_hash)
                    values (?,?,?,?,?,'fixture/v2','fixture/v2',?,?)
                    """,
                    revision,
                    fixture.owner(),
                    fixture.trip(),
                    fixture.base(),
                    fixture.day(),
                    UUID.randomUUID(),
                    commandHash);
                jdbc.update(
                    """
                    insert into public.compute_run_inputs
                      (id,schedule_revision_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,
                       run_type,schema_version,contract_version,algorithm_version,structured_input,
                       command_input_hash)
                    values (?,?,?,?,?,'schedule_revision',2,'fixture/v2','fixture/v2',?::jsonb,?)
                    """,
                    input,
                    revision,
                    fixture.owner(),
                    fixture.trip(),
                    fixture.base(),
                    structuredInput,
                    commandHash);
              });
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.schedule_revision_runs where id=? and request_hash=?",
                  Integer.class,
                  revision,
                  commandHash))
          .isEqualTo(1);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "postgis/postgis:16-3.4,running", "postgis/postgis:17-3.5,running",
    "postgis/postgis:16-3.4,wire_audit", "postgis/postgis:17-3.5,wire_audit",
    "postgis/postgis:16-3.4,unknown_dependency", "postgis/postgis:17-3.5,unknown_dependency",
    "postgis/postgis:16-3.4,unknown_function", "postgis/postgis:17-3.5,unknown_function",
    "postgis/postgis:16-3.4,uppercase_function", "postgis/postgis:17-3.5,uppercase_function",
    "postgis/postgis:16-3.4,event_function", "postgis/postgis:17-3.5,event_function",
    "postgis/postgis:16-3.4,event_trigger", "postgis/postgis:17-3.5,event_trigger",
    "postgis/postgis:16-3.4,event_wrapped_location",
        "postgis/postgis:17-3.5,event_wrapped_location",
    "postgis/postgis:16-3.4,unclassified_public_join",
        "postgis/postgis:17-3.5,unclassified_public_join",
    "postgis/postgis:16-3.4,changed_known_trigger", "postgis/postgis:17-3.5,changed_known_trigger"
  })
  void 실행중_worker_미분류_hash_추가_dependency는_값과_catalog를_보존하며_중단한다(String image, String blocker)
      throws Exception {
    try (var database = freshDatabase(image)) {
      var container = database.container();
      var jdbc = jdbc(container);
      var fixture = fixture(jdbc);
      switch (blocker) {
        case "running" ->
            jdbc.update(
                """
                update public.compute_runs set status='running', attempt_count=1, fencing_token=1,
                  started_at=statement_timestamp(), facts_snapshot_at=statement_timestamp(),
                  source_data_version='fixture', lease_owner='fixture-worker',
                  lease_expires_at=statement_timestamp()+interval '30 seconds', heartbeat_at=statement_timestamp()
                where id=?
                """,
                fixture.run());
        case "wire_audit" ->
            jdbc.update(
                """
                insert into public.mcp_compute_call_logs
                  (compute_run_id,request_id,tool_name,status,contract_version,command_input_hash,mcp_input_hash,
                   schema_checksum,request_fact_count,response_fact_count,attempt_no,latency_ms,error_code)
                values (?,?,'evaluate_jeju_day_trip','transport_error','0.7.0',?,repeat('a',64),
                  repeat('b',64),0,0,1,1,'MCP_TIMEOUT')
                """,
                fixture.run(),
                "fixture-call-" + fixture.run(),
                fixture.oldHash());
        case "unknown_dependency" ->
            jdbc.execute(
                """
                create view timing_jeju_planner_private.issue224_unexpected_dependency as
                  select coarse_location from public.compute_run_inputs
                """);
        case "unknown_function" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_unexpected_function()
                returns jsonb language plpgsql set search_path='' as $$
                declare result jsonb;
                begin select coarse_location into result from public.compute_run_inputs limit 1;
                  return result;
                end; $$;
                """);
        case "unknown_procedure" ->
            jdbc.execute(
                """
                create procedure timing_jeju_planner_private.issue224_unexpected_procedure()
                language plpgsql set search_path='' as $$
                begin perform coarse_location from public.compute_run_inputs; end; $$;
                """);
        case "event_procedure" ->
            jdbc.execute(
                """
                create procedure timing_jeju_planner_private.issue224_event_procedure()
                language plpgsql set search_path='' as $$
                begin perform location from public.trip_execution_events; end; $$;
                """);
        case "uppercase_function" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_uppercase_function()
                returns jsonb language plpgsql set search_path='' as $$
                declare result jsonb;
                begin SELECT COARSE_LOCATION INTO result FROM public.compute_run_inputs LIMIT 1;
                  return result;
                end; $$;
                """);
        case "event_function" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_event_function()
                returns text language plpgsql set search_path='' as $$
                declare result text;
                begin select event.location::text into result from public.trip_execution_events event limit 1;
                  return result;
                end; $$;
                """);
        case "event_trigger" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_event_trigger()
                returns trigger language plpgsql set search_path='' as $$
                begin perform NEW.location; return NEW; end; $$;
                create trigger issue224_event_trigger before insert on public.trip_execution_events
                  for each row execute function timing_jeju_planner_private.issue224_event_trigger();
                """);
        case "event_wrapped_location" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_wrapped_location()
                returns text language plpgsql set search_path='' as $$
                declare result text;
                begin select public.ST_AsText(location::public.geometry) into result
                  from public.trip_execution_events limit 1; return result;
                end; $$;
                """);
        case "unclassified_public_join" ->
            jdbc.execute(
                """
                create function timing_jeju_planner_private.issue224_public_join()
                returns text language plpgsql set search_path='' as $$
                declare result text;
                begin select place.location::text into result from public.tour_places place
                  cross join public.trip_execution_events event limit 1; return result;
                end; $$;
                """);
        case "changed_known_trigger" ->
            jdbc.execute(
                """
                create or replace function public.prevent_execution_event_mutation()
                returns trigger language plpgsql security invoker set search_path='' as $$
                begin perform NEW.location; return NEW; end; $$;
                """);
        default -> throw new IllegalArgumentException("알 수 없는 합성 fixture입니다.");
      }
      String fingerprint = fingerprint(jdbc);
      String inputBefore =
          jdbc.queryForObject(
              "select to_jsonb(input)::text from public.compute_run_inputs input where id=?",
              String.class,
              fixture.input());
      String runBefore =
          jdbc.queryForObject(
              "select to_jsonb(run)::text from public.compute_runs run where id=?",
              String.class,
              fixture.run());

      assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, migration()))
          .isInstanceOf(IllegalStateException.class);

      assertThat(fingerprint(jdbc)).isEqualTo(fingerprint);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(input)::text from public.compute_run_inputs input where id=?",
                  String.class,
                  fixture.input()))
          .isEqualTo(inputBefore);
      assertThat(
              jdbc.queryForObject(
                  "select to_jsonb(run)::text from public.compute_runs run where id=?",
                  String.class,
                  fixture.run()))
          .isEqualTo(runBefore);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "postgis/postgis:16-3.4,unknown_procedure", "postgis/postgis:17-3.5,unknown_procedure",
    "postgis/postgis:16-3.4,event_procedure", "postgis/postgis:17-3.5,event_procedure"
  })
  void 프로시저의_제거열_참조도_값과_catalog를_보존하며_중단한다(String image, String blocker) throws Exception {
    실행중_worker_미분류_hash_추가_dependency는_값과_catalog를_보존하며_중단한다(image, blocker);
  }

  private static Fixture fixture(JdbcTemplate jdbc) {
    UUID owner = UUID.randomUUID(),
        trip = UUID.randomUUID(),
        day = UUID.randomUUID(),
        base = UUID.randomUUID();
    UUID run = UUID.randomUUID(), input = UUID.randomUUID(), place = UUID.randomUUID();
    String email = owner + "@migration-v2.test";
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
    jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
    jdbc.update(
        """
        insert into public.trip_plans(id,user_id,public_token,title,start_date,end_date,source_mode,data_version)
        values (?,?,?,'전환 보존',current_date,current_date,'fixture','fixture')
        """,
        trip,
        owner,
        trip.toString());
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values"
            + " (?,?,1,current_date)",
        day,
        trip);
    jdbc.update(
        """
        insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type)
        values (?,?,1,'draft','initial')
        """,
        base,
        trip);
    jdbc.update(
        """
        insert into public.tour_places(id,name,normalized_name,category,location,source_provider)
        values (?,'공개 장소','공개 장소','ATTRACTION',ST_GeogFromText('SRID=4326;POINT(126.5 33.5)'),'fixture')
        """,
        place);
    String hash =
        jdbc.queryForObject(
            """
            select public.compute_command_input_hash('feasibility'::text,1::smallint,
              'fixture/v2'::text,'fixture/v2'::text,?::uuid,?::jsonb,false::boolean,null::jsonb)
            """,
            String.class,
            base,
            INPUT);
    new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  """
                  insert into public.compute_runs(id,trip_plan_id,trip_day_id,schedule_version_id,run_type,
                    status,input_hash,contract_version,algorithm_version)
                  values (?,?,?,?,'feasibility','queued',?,'fixture/v2','fixture/v2')
                  """,
                  run,
                  trip,
                  day,
                  base,
                  hash);
              jdbc.update(
                  """
                  insert into public.compute_run_inputs(id,compute_run_id,owner_user_id,trip_plan_id,
                    base_schedule_version_id,run_type,schema_version,contract_version,algorithm_version,
                    structured_input,command_input_hash,location_supplied)
                  values (?,?,?,?,?,'feasibility',1,'fixture/v2','fixture/v2',?::jsonb,?,false)
                  """,
                  input,
                  run,
                  owner,
                  trip,
                  base,
                  INPUT,
                  hash);
            });
    return new Fixture(owner, trip, day, base, run, input, place, hash);
  }

  private static String immutableInput(JdbcTemplate jdbc, UUID id) {
    return jdbc.queryForObject(
        """
        select (to_jsonb(input)-array['schema_version','command_input_hash','location_supplied',
          'coarse_location','location_precision_meters','location_policy_version','location_observed_at',
          'location_expires_at','location_redacted_at'])::text
        from public.compute_run_inputs input where id=?
        """,
        String.class,
        id);
  }

  private static String publicPlace(JdbcTemplate jdbc, UUID id) {
    return jdbc.queryForObject(
        "select to_jsonb(place)::text from public.tour_places place where id=?", String.class, id);
  }

  private static String fingerprint(JdbcTemplate jdbc) throws Exception {
    return jdbc.queryForObject(
        Files.readString(
            PostgreSqlTestContainerFactory.locateRepositoryRoot()
                .resolve("db/queries/canonical_migration_fingerprint.sql")),
        String.class);
  }

  private static Path migration() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("supabase/migrations")
        .resolve(TARGET);
  }

  private static JdbcTemplate jdbc(org.testcontainers.postgresql.PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private record Fixture(
      UUID owner,
      UUID trip,
      UUID day,
      UUID base,
      UUID run,
      UUID input,
      UUID place,
      String oldHash) {}
}
