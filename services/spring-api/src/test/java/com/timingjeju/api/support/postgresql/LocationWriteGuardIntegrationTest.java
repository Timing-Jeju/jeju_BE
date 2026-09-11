package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.global.asyncrun.JdbcRunLeaseRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class LocationWriteGuardIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

  @Autowired
  private com.timingjeju.api.application.commandinput.CommandInputCanonicalizer canonicalizer;

  @Autowired
  private com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository inputs;

  private UUID owner;
  private UUID trip;
  private UUID version;
  private UUID place;
  private UUID stop;

  @BeforeEach
  void 위치없는_여행과_공개_장소를_준비한다() {
    owner = UUID.randomUUID();
    trip = UUID.randomUUID();
    version = UUID.randomUUID();
    place = UUID.randomUUID();
    stop = UUID.randomUUID();
    String email = owner + "@issue223.test";
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
    jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
    jdbc.update(
        "insert into public.trip_plans"
            + " (id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision)"
            + " values (?,?,?,'위치 비수집','draft','2026-09-01','2026-09-01','fixture','issue223',1)",
        trip,
        owner,
        "issue223-" + trip);
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) "
            + "values (?,?,1,'draft','initial')",
        version,
        trip);
    jdbc.update(
        "insert into public.tour_places(id,name,normalized_name,category,location,source_provider)"
            + " values (?,'공개 장소','공개"
            + " 장소','ATTRACTION',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture')",
        place);
    jdbc.update(
        "insert into public.bus_stops(id,node_id,node_name,location,source_provider) values"
            + " (?,?,'공개 정류장',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture')",
        stop,
        "issue223-" + stop);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "event_direct",
        "event_nested",
        "event_grid",
        "live_direct",
        "live_place",
        "live_nested"
      })
  void service_role도_현재_간접_위치를_신규_저장할_수_없다(String kind) {
    jdbc.execute("set local role service_role");
    boolean removedColumn =
        kind.equals("event_direct") || kind.equals("live_direct") || kind.equals("live_place");
    assertThatThrownBy(() -> insertLocationRow(kind))
        .isInstanceOf(
            removedColumn
                ? org.springframework.jdbc.BadSqlGrammarException.class
                : DataIntegrityViolationException.class)
        .satisfies(
            error -> {
              if (removedColumn) {
                assertThat(error.getCause()).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                    .isEqualTo("42703");
              } else {
                assertThat(error).hasMessageContaining("user location storage is disabled");
              }
            })
        .hasMessageNotContaining("126.51")
        .hasMessageNotContaining("33.51")
        .hasMessageNotContaining("private-location-marker")
        .hasMessageNotContaining("Failing row");
  }

  @Test
  void service_role의_위치없는_수동_event와_진행_projection은_유지된다() {
    jdbc.execute("set local role service_role");
    assertThat(
            jdbc.update(
                "insert into public.trip_execution_events"
                    + " (trip_plan_id,schedule_version_id,event_type,client_event_id,occurred_at,metadata)"
                    + " values (?,?,'trip_started','manual-event',now(),'{}'::jsonb)",
                trip,
                version))
        .isEqualTo(1);
    assertThat(
            jdbc.update(
                "insert into"
                    + " public.live_state_snapshots(trip_plan_id,schedule_version_id,status,facts)"
                    + " values (?,?,'green','{}'::jsonb)",
                trip,
                version))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"GRID_100M", "PLACE", "STOP"})
  void service_role도_compute_input의_간접_위치를_저장할_수_없다(String kind) {
    UUID run = createComputeParent();
    String coarse =
        kind.equals("GRID_100M")
            ? "{\"type\":\"GRID_100M\",\"gridX\":53,\"gridY\":38}"
            : "{\"type\":\""
                + kind
                + "\",\""
                + (kind.equals("PLACE") ? "placeId" : "stopId")
                + "\":\""
                + (kind.equals("PLACE") ? place : stop)
                + "\"}";
    String hash = "a".repeat(64);
    jdbc.execute("set local role service_role");
    assertThatThrownBy(
            () -> insertCommandInput(run, coarse, kind.equals("GRID_100M") ? 100 : null, hash))
        .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class)
        .satisfies(
            error -> {
              assertThat(error.getCause()).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                  .isEqualTo("42703");
            })
        .hasMessageNotContaining("Failing row")
        .hasMessageNotContaining(place.toString())
        .hasMessageNotContaining(stop.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"event", "live"})
  void 기존_위치없는_행에_대한_직접_좌표_UPDATE도_값을_반사하지_않고_거부한다(String kind) {
    UUID id = UUID.randomUUID();
    if (kind.equals("event")) {
      jdbc.update(
          "insert into public.trip_execution_events"
              + " (id,trip_plan_id,schedule_version_id,event_type,client_event_id,occurred_at,metadata)"
              + " values (?,?,?,'trip_started','clean-update-event',now(),'{}'::jsonb)",
          id,
          trip,
          version);
    } else {
      jdbc.update(
          "insert into"
              + " public.live_state_snapshots(id,trip_plan_id,schedule_version_id,status,facts)"
              + " values (?,?,?,'green','{}'::jsonb)",
          id,
          trip,
          version);
    }
    jdbc.execute("set local role service_role");
    String sql =
        kind.equals("event")
            ? "update public.trip_execution_events set location=ST_GeogFromText(?) where id=?"
            : "update public.live_state_snapshots set current_location=ST_GeogFromText(?) where"
                + " id=?";
    assertThatThrownBy(() -> jdbc.update(sql, "SRID=4326;POINT(126.51 33.51)", id))
        .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class)
        .satisfies(
            error -> {
              assertThat(error.getCause()).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) error.getCause()).getSQLState())
                  .isEqualTo("42703");
            })
        .hasMessageNotContaining("126.51")
        .hasMessageNotContaining("33.51")
        .hasMessageNotContaining(id.toString())
        .hasMessageNotContaining("Failing row");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "trip_preferences,raw_answers",
    "trip_legs,facts",
    "itinerary_generation_runs,structured_input",
    "ai_messages,structured_payload",
    "compute_runs,result_summary",
    "risk_events,computed_facts",
    "trip_weather_impacts,computed_facts",
    "recommendation_candidates,facts",
    "recovery_options,change_summary",
    "recovery_option_changes,before_value",
    "recovery_option_changes,after_value"
  })
  void generic_JSON_위치는_다른_제약_오류보다_먼저_값을_반사하지_않고_차단한다(String table, String column) {
    jdbc.execute("set local role service_role");
    // Incomplete required fields deliberately exercise privacy before NOT NULL/FK error details.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into public." + table + "(" + column + ") values (?::jsonb)",
                    "{\"nested\":{\"currentLocation\":{\"lat\":33.51,\"lng\":126.51},\"locationHash\":\"private-location-marker\"}}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("user location storage is disabled")
        .hasMessageNotContaining("33.51")
        .hasMessageNotContaining("126.51")
        .hasMessageNotContaining("private-location-marker")
        .hasMessageNotContaining("Failing row");
  }

  @ParameterizedTest
  @ValueSource(strings = {"geohash", "Geo-Hash", "geo_hash", "GEOHASH"})
  void 중첩_배열의_geohash도_표기와_무관하게_저장하거나_오류에_반사하지_않는다(String key) {
    jdbc.execute("set local role service_role");
    String payload = "{\"nested\":[{\"" + key + "\":\"private-derived-marker\"}]}";
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " public.trip_preferences(trip_plan_id,arrival_region_code,departure_region_code,raw_answers)"
                        + " values (?,'JEJU','JEJU',?::jsonb)",
                    trip,
                    payload))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("user location storage is disabled")
        .hasMessageNotContaining("private-derived-marker")
        .hasMessageNotContaining("Failing row");
  }

  @Test
  void 위치없는_선호_JSON과_명시_선택한_공개_장소는_service_role도_저장한다() {
    jdbc.execute("set local role service_role");
    jdbc.update(
        "insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary)"
            + " values (?,'public_transit',1,true)",
        trip);
    assertThat(
            jdbc.update(
                """
                insert into public.trip_preferences(trip_plan_id,start_place_id,end_place_id,arrival_region_code,departure_region_code,raw_answers)
                values (?,?,?,'JEJU','JEJU','{"pace":"normal","partySize":2}'::jsonb)
                """,
                trip,
                place,
                place))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select raw_answers->>'pace' from public.trip_preferences where trip_plan_id=?",
                String.class,
                trip))
        .isEqualTo("normal");
    jdbc.execute("set constraints all immediate");
    assertThat(
            jdbc.queryForObject(
                "select start_place_id=end_place_id from public.trip_preferences where"
                    + " trip_plan_id=?",
                Boolean.class,
                trip))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"anon", "authenticated", "service_role"})
  void API_역할은_위치_guard_함수를_직접_실행할_권한이_없다(String role) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where"
                    + " n.nspname='timing_jeju_private' and p.proname in "
                    + "('reject_execution_event_location','reject_live_state_location','reject_command_input_location','reject_user_json_location')",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where"
                    + " n.nspname='timing_jeju_private' and p.proname in"
                    + " ('reject_execution_event_location','reject_live_state_location','reject_command_input_location','reject_user_json_location')"
                    + " and has_function_privilege(?,p.oid,'EXECUTE')",
                Integer.class,
                role))
        .isZero();
  }

  @Test
  void 위치없는_compute_input은_v2_canonical_hash로_저장된다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, run);
    jdbc.execute("set local role service_role");
    assertThat(insertCommandInput(run, null, null, hash)).isEqualTo(1);
    jdbc.execute("set constraints all immediate");
  }

  private UUID createComputeParent() {
    UUID run = UUID.randomUUID();
    jdbc.update(
        "insert into public.compute_runs"
            + " (id,trip_plan_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version)"
            + " values (?,?,?,'feasibility','queued','fixture-parent-hash','fixture','fixture')",
        run,
        trip,
        version);
    return run;
  }

  @ParameterizedTest
  @ValueSource(strings = {"generation", "revision"})
  void 생성과_수정도_입력_없는_parent의_commit을_거부한다(String kind) {
    UUID run = UUID.randomUUID(), day = UUID.randomUUID();
    insertPlannerParent(kind, run, day);
    jdbc.execute("set local role service_role");
    assertThatThrownBy(() -> jdbc.execute("set constraints all immediate"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(run.toString());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "generation,delete",
    "revision,delete",
    "generation,mismatch"
  })
  void 생성과_수정의_입력_삭제와_parent_변조는_지연_검증에서_거부한다(String kind, String mutation) throws Exception {
    UUID run = UUID.randomUUID(), day = UUID.randomUUID();
    insertPlannerParent(kind, run, day);
    savePlannerInput(kind, run, day);
    jdbc.execute("set constraints all immediate");
    jdbc.execute("set constraints all deferred");
    if (mutation.equals("delete")) {
      jdbc.update(
          "delete from public.compute_run_inputs where "
              + (kind.equals("generation") ? "generation_run_id" : "schedule_revision_run_id")
              + "=?",
          run);
    } else {
      jdbc.execute("set local role service_role");
      jdbc.update(
          "update public."
              + (kind.equals("generation") ? "itinerary_generation_runs" : "schedule_revision_runs")
              + " set algorithm_version='changed' where id=?",
          run);
    }
    assertThatThrownBy(() -> jdbc.execute("set constraints all immediate"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(run.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"generation", "revision"})
  void 생성과_수정의_실제_repository_입력은_같은_transaction에서_검증되고_parent_삭제가_가능하다(String kind)
      throws Exception {
    UUID run = UUID.randomUUID(), day = UUID.randomUUID();
    insertPlannerParent(kind, run, day);
    savePlannerInput(kind, run, day);
    jdbc.execute("set local role service_role");
    jdbc.execute("set constraints all immediate");
    jdbc.update(
        "delete from public."
            + (kind.equals("generation") ? "itinerary_generation_runs" : "schedule_revision_runs")
            + " where id=?",
        run);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where trip_plan_id=?",
                Integer.class,
                trip))
        .isZero();
  }

  @Test
  void 수정_parent의_메타데이터는_기존_불변_제약에서_즉시_거부된다() throws Exception {
    UUID run = UUID.randomUUID(), day = UUID.randomUUID();
    insertPlannerParent("revision", run, day);
    savePlannerInput("revision", run, day);
    jdbc.execute("set constraints all immediate");
    jdbc.execute("set local role service_role");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.schedule_revision_runs set algorithm_version='changed' where"
                        + " id=?",
                    run))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("schedule revision run identity is immutable")
        .hasMessageNotContaining(run.toString());
  }

  private void insertPlannerParent(String kind, UUID run, UUID day) {
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values"
            + " (?,?,1,'2026-09-01')",
        day,
        trip);
    if (kind.equals("generation")) {
      jdbc.update(
          """
          insert into public.itinerary_generation_runs
            (id,trip_plan_id,trip_day_id,base_schedule_version_id,structured_input,
             contract_version,algorithm_version,idempotency_key,requested_by_user_id,status)
          values (?,?,?,?,?::jsonb,'fixture','fixture',?,?,'queued')
          """,
          run,
          trip,
          day,
          version,
          plannerInput(kind, day),
          run.toString(),
          owner);
    } else {
      String requestHash =
          jdbc.queryForObject(
              """
              select public.compute_command_input_hash(
                'schedule_revision'::text,2::smallint,'fixture'::text,'fixture'::text,
                ?::uuid,?::jsonb)
              """,
              String.class,
              version,
              plannerInput(kind, day));
      jdbc.update(
          """
          insert into public.schedule_revision_runs
            (id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,
             contract_version,algorithm_version,idempotency_key,request_hash)
          values (?,?,?,?,?,'fixture','fixture',?,?)
          """,
          run,
          owner,
          trip,
          version,
          day,
          UUID.randomUUID(),
          requestHash);
    }
  }

  private String plannerInput(String kind, UUID day) {
    return "{\"targetDayId\":\""
        + day
        + "\","
        + (kind.equals("generation")
            ? "\"candidateCount\":3,\"refreshExternalFacts\":false}"
            : "\"affectedItemIds\":[],\"instructionCodes\":[]}");
  }

  private void savePlannerInput(String kind, UUID run, UUID day) throws Exception {
    com.timingjeju.api.application.commandinput.CommandInputParent parent =
        kind.equals("generation")
            ? new com.timingjeju.api.application.commandinput.CommandInputParent.Generation(run)
            : new com.timingjeju.api.application.commandinput.CommandInputParent.ScheduleRevision(
                run);
    var snapshot =
        canonicalizer.canonicalize(
            new com.timingjeju.api.application.commandinput.CommandInputRequest(
                parent,
                kind.equals("generation") ? "itinerary_generation" : "schedule_revision",
                2,
                "fixture",
                "fixture",
                objectMapper.readTree(plannerInput(kind, day)),
                owner,
                trip,
                version));
    assertThat(inputs.save(snapshot)).isEqualTo(snapshot);
    assertThat(inputs.find(parent)).contains(snapshot);
  }

  @Test
  void 입력_출처가_없는_작업은_worker가_claim하지_않고_queued로_보존한다() {
    UUID run = createComputeParent();
    var leases =
        new JdbcRunLeaseRepository(jdbc)
            .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10);
    assertThat(leases).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_runs "
                    + "where id=? and status='queued' and fencing_token=0 and lease_owner is null",
                Integer.class,
                run))
        .isEqualTo(1);
  }

  @Test
  void 입력_없는_parent_hash는_지연_제약_검증에서_반사없이_거부된다() {
    UUID run = createComputeParent();
    assertThatThrownBy(() -> jdbc.execute("set constraints all immediate"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(run.toString())
        .hasMessageNotContaining("fixture-parent-hash");
  }

  @Test
  void 입력_없는_parent에_MCP_hash를_추가할_수_없다() {
    UUID run = createComputeParent();
    jdbc.execute("set local role service_role");
    assertThatThrownBy(() -> insertMcpLog(run, "c".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(run.toString())
        .hasMessageNotContaining("c".repeat(64));
  }

  @Test
  void 동일_transaction의_무위치_input과_parent는_claim과_최소_MCP_audit가_가능하다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, run);
    insertCommandInput(run, null, null, hash);
    jdbc.execute("set constraints all immediate");
    var leases =
        new JdbcRunLeaseRepository(jdbc)
            .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10);
    assertThat(leases).hasSize(1);
    assertThat(leases.getFirst().runId()).isEqualTo(run);
    jdbc.execute("set local role service_role");
    assertThat(insertMcpLog(run, hash)).isEqualTo(1);
  }

  @Test
  void 실제_Java_repository의_무위치_input은_재조회와_worker_claim에서도_같은_hash를_유지한다() throws Exception {
    UUID run = createComputeParent();
    var parent = new com.timingjeju.api.application.commandinput.CommandInputParent.Compute(run);
    var snapshot =
        canonicalizer.canonicalize(
            new com.timingjeju.api.application.commandinput.CommandInputRequest(
                parent,
                "feasibility",
                2,
                "fixture",
                "fixture",
                objectMapper.readTree("{\"refreshExternalFacts\":false}"),
                owner,
                trip,
                version));
    jdbc.update(
        "update public.compute_runs set input_hash=? where id=?", snapshot.commandInputHash(), run);
    assertThat(inputs.save(snapshot)).isEqualTo(snapshot);
    jdbc.execute("set constraints all immediate");
    assertThat(inputs.find(parent)).contains(snapshot);
    assertThat(
            jdbc.queryForObject(
                "select command_input_hash from public.compute_run_inputs where compute_run_id=?",
                String.class,
                run))
        .isEqualTo(snapshot.commandInputHash());
    var claimed =
        new JdbcRunLeaseRepository(jdbc)
            .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10);
    assertThat(claimed).hasSize(1);
    assertThat(claimed.getFirst().runId()).isEqualTo(run);
    assertThat(inputs.find(parent)).contains(snapshot);
  }

  @Test
  void 입력없는_만료된_마지막_시도는_보존하고_정상_queued만_claim한다() {
    UUID run = createComputeParent();
    jdbc.update(
        "update public.compute_runs set"
            + " status='running',attempt_count=5,fencing_token=5,started_at=now()-interval '1"
            + " minute',facts_snapshot_at=now()-interval '1"
            + " minute',source_data_version='fixture',lease_owner='legacy-worker',heartbeat_at=now()-interval"
            + " '1 minute',lease_expires_at=now()-interval '1"
            + " second',next_attempt_at=null,input_hash=? where id=?",
        "legacy-orphan-hash",
        run);
    UUID validRun = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, validRun);
    insertCommandInput(validRun, null, null, hash);
    var leases =
        new JdbcRunLeaseRepository(jdbc)
            .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10);
    assertThat(leases).hasSize(1);
    assertThat(leases.getFirst().runId()).isEqualTo(validRun);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_runs where id=? and status='running' and"
                    + " attempt_count=5 and fencing_token=5 and lease_owner='legacy-worker'",
                Integer.class,
                run))
        .isEqualTo(1);
  }

  @Test
  void 정상_input의_만료된_마지막_시도는_재시도없이_실패로_종료한다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, run);
    insertCommandInput(run, null, null, hash);
    jdbc.execute("set constraints all immediate");
    jdbc.update(
        "update public.compute_runs set"
            + " status='running',attempt_count=5,fencing_token=5,started_at=now()-interval '1"
            + " minute',facts_snapshot_at=now()-interval '1"
            + " minute',source_data_version='fixture',lease_owner='fixture-worker',heartbeat_at=now()-interval"
            + " '1 minute',lease_expires_at=now()-interval '1 second',next_attempt_at=null where"
            + " id=?",
        run);
    assertThat(
            new JdbcRunLeaseRepository(jdbc)
                .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_runs where id=? and status='failed' and"
                    + " error_code='ASYNC_RUN_RETRY_EXHAUSTED' and attempt_count=5 and"
                    + " fencing_token=5 and lease_owner is null and completed_at is not null",
                Integer.class,
                run))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where compute_run_id=?",
                Integer.class,
                run))
        .isEqualTo(1);
  }

  @Test
  void input을_삭제하고_parent_hash만_남길_수_없다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, run);
    insertCommandInput(run, null, null, hash);
    jdbc.execute("set constraints all immediate");
    assertThatThrownBy(
            () -> jdbc.update("delete from public.compute_run_inputs where compute_run_id=?", run))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(hash)
        .hasMessageNotContaining(run.toString());
  }

  @Test
  void 부모_hash가_input과_달라진_작업은_claim하지_않는다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    insertCommandInput(run, null, null, hash);
    assertThat(
            new JdbcRunLeaseRepository(jdbc)
                .claimAvailable("fixture-worker", Duration.ofSeconds(30), 10))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select fencing_token from public.compute_runs where id=?", Long.class, run))
        .isZero();
  }

  @Test
  void 유효한_input이_있어도_다른_command_hash의_MCP_log는_거부한다() {
    UUID run = createComputeParent();
    String hash = commandHash(null);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, run);
    insertCommandInput(run, null, null, hash);
    jdbc.execute("set constraints all immediate");
    jdbc.execute("set local role service_role");
    assertThatThrownBy(() -> insertMcpLog(run, "c".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("compute input lineage required")
        .hasMessageNotContaining(hash)
        .hasMessageNotContaining("c".repeat(64));
  }

  private int insertMcpLog(UUID run, String hash) {
    return jdbc.update(
        "insert into public.mcp_compute_call_logs "
            + "(compute_run_id,request_id,tool_name,status,contract_version,command_input_hash,schema_checksum,request_fact_count,response_fact_count,attempt_no,latency_ms,error_code)"
            + " values (?,?,'evaluate_jeju_day_trip','transport_error','0.7.0',?,"
            + "repeat('b',64),0,0,1,1,'MCP_TIMEOUT')",
        run,
        "fixture-call-" + run,
        hash);
  }

  private String commandHash(String coarse) {
    if (coarse != null) throw new IllegalArgumentException("위치 입력은 hash fixture에서 제외합니다.");
    return jdbc.queryForObject(
        """
        select public.compute_command_input_hash('feasibility'::text,2::smallint,
          'fixture'::text,'fixture'::text,?::uuid,'{"refreshExternalFacts":false}'::jsonb)
        """,
        String.class,
        version);
  }

  private int insertCommandInput(UUID run, String coarse, Integer precision, String hash) {
    if (coarse == null) {
      return jdbc.update(
          """
          insert into public.compute_run_inputs
            (compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,schema_version,
             contract_version,algorithm_version,structured_input,command_input_hash)
          values (?,?,?,?,'feasibility',2,'fixture','fixture','{"refreshExternalFacts":false}'::jsonb,?)
          """,
          run,
          owner,
          trip,
          version,
          hash);
    }
    // 구버전 client의 제거된 열 쓰기가 DB에서 거부되는지 확인하는 부정 fixture다.
    return jdbc.update(
        """
        with command as (select ?::jsonb as coarse, '{"refreshExternalFacts":false}'::jsonb as input)
        insert into public.compute_run_inputs
          (compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,schema_version,
           contract_version,algorithm_version,structured_input,command_input_hash,location_supplied,
           coarse_location,location_precision_meters,location_policy_version,location_observed_at)
        select ?,?,?,?,'feasibility',1,'fixture','fixture',input,
          ?,
          coarse is not null,coarse,?,case when coarse is not null then '1.0.0' end,
          case when coarse is not null then now() end
        from command
        """,
        coarse,
        run,
        owner,
        trip,
        version,
        hash,
        precision);
  }

  private void insertLocationRow(String kind) {
    if (kind.equals("event_nested") || kind.equals("event_grid")) {
      jdbc.update(
          """
          insert into public.trip_execution_events
            (trip_plan_id,schedule_version_id,event_type,client_event_id,occurred_at,metadata)
          values (?,?,'trip_started','location-event',now(),?::jsonb)
          """,
          trip,
          version,
          kind.equals("event_nested")
              ? "{\"nested\":[{\"currentLocation\":{\"latitude\":33.51}}]}"
              : "{\"gridX\":53,\"locationDigest\":\"private-location-marker\"}");
      return;
    }
    if (kind.equals("live_nested")) {
      jdbc.update(
          """
          insert into public.live_state_snapshots(trip_plan_id,schedule_version_id,status,facts)
          values (?,?,'green',?::jsonb)
          """,
          trip,
          version,
          "{\"nested\":{\"nearestPlaceId\":\"private-location-marker\"}}");
      return;
    }
    if (kind.startsWith("event_")) {
      String location = kind.equals("event_direct") ? "SRID=4326;POINT(126.51 33.51)" : null;
      String metadata =
          kind.equals("event_nested")
              ? "{\"nested\":[{\"currentLocation\":{\"latitude\":33.51}}]}"
              : kind.equals("event_grid")
                  ? "{\"gridX\":53,\"locationDigest\":\"private-location-marker\"}"
                  : "{}";
      jdbc.update(
          "insert into public.trip_execution_events"
              + " (trip_plan_id,schedule_version_id,event_type,client_event_id,occurred_at,location,metadata)"
              + " values (?,?,'trip_started','location-event',now(),ST_GeogFromText(?),?::jsonb)",
          trip,
          version,
          location,
          metadata);
    } else {
      String location = kind.equals("live_direct") ? "SRID=4326;POINT(126.51 33.51)" : null;
      jdbc.update(
          "insert into public.live_state_snapshots "
              + "(trip_plan_id,schedule_version_id,status,current_location,current_place_id,facts) "
              + "values (?,?,'green',ST_GeogFromText(?),?,?::jsonb)",
          trip,
          version,
          location,
          kind.equals("live_place") ? place : null,
          kind.equals("live_nested")
              ? "{\"nested\":{\"nearestPlaceId\":\"private-location-marker\"}}"
              : "{}");
    }
  }
}
