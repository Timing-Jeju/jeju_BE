package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.CoarseLocation;
import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.CommandInputStorageException;
import com.timingjeju.api.application.commandinput.CommandLocation;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCommand;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupException;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupPort;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@SpringBootTest
@Import(CommandInputSnapshotRepositoryIntegrationTest.LegacyInputConfiguration.class)
@ActiveProfiles("postgresql-integration")
class CommandInputSnapshotRepositoryIntegrationTest {
  private static final UUID OWNER = UUID.fromString("10820000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("10820000-0000-0000-0000-000000000002");
  private static final UUID DAY = UUID.fromString("10820000-0000-0000-0000-000000000003");
  private static final UUID BASE = UUID.fromString("10820000-0000-0000-0000-000000000004");
  private static final UUID COMPUTE = UUID.fromString("10820000-0000-0000-0000-000000000005");
  private static final UUID GENERATION = UUID.fromString("10820000-0000-0000-0000-000000000006");
  private static final UUID REVISION = UUID.fromString("10820000-0000-0000-0000-000000000007");
  private static final Instant EVALUATED_AT = Instant.parse("2026-08-24T12:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CommandInputCanonicalizer canonicalizer;
  @Autowired private CommandInputSnapshotRepository repository;
  @Autowired private CommandLocationCleanupPort cleanupPort;

  @BeforeEach
  void setUpParentMatrix() {
    cleanUp();
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", OWNER, "command-input@test.invalid");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        "command-input@test.invalid");
    jdbc.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, start_date, end_date, source_mode, data_version
        ) values (?, ?, 'command-input-integration', current_date, current_date, 'fixture', 'v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, current_date)",
        DAY,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_schedule_versions (
          id, trip_plan_id, version_no, status, source_type, created_by_user_id
        ) values (?, ?, 1, 'draft', 'initial', ?)
        """,
        BASE,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_items (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
          item_type, title, planned_start_at, planned_end_at, stay_minutes, source, facts
        ) values (
          '10820000-0000-0000-0000-000000000009', ?, ?, ?, 1,
          'custom', 'sealable fixture',
          (current_date + time '01:00') at time zone 'Asia/Seoul',
          (current_date + time '02:00') at time zone 'Asia/Seoul',
          60, 'system', '{}'::jsonb
        )
        """,
        TRIP,
        DAY,
        BASE);
    jdbc.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version
        ) values (?, ?, ?, ?, 'feasibility', 'queued', 'input-v1', 'compute/v1', 'algorithm/v1')
        """,
        COMPUTE,
        TRIP,
        DAY,
        BASE);
    jdbc.update(
        """
        insert into public.itinerary_generation_runs (
          id, trip_plan_id, trip_day_id, base_schedule_version_id, status,
          contract_version, algorithm_version, idempotency_key, requested_by_user_id
        ) values (?, ?, ?, ?, 'queued', 'generation/v1', 'algorithm/v1', 'command-input-generation', ?)
        """,
        GENERATION,
        TRIP,
        DAY,
        BASE,
        OWNER);
    jdbc.update(
        """
        insert into public.schedule_revision_runs (
          id, owner_user_id, trip_plan_id, base_schedule_version_id,
          target_trip_day_id, contract_version, algorithm_version,
          idempotency_key, request_hash
        ) values (?, ?, ?, ?, ?, 'revision/v1', 'algorithm/v1', ?, repeat('a', 64))
        """,
        REVISION,
        OWNER,
        TRIP,
        BASE,
        DAY,
        UUID.fromString("10820000-0000-0000-0000-000000000008"));
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.user_profiles where id = ?", OWNER);
    jdbc.update("delete from auth.users where id = ?", OWNER);
  }

  @Test
  void compute_generation_revision_parent를_각각_한번_저장하고_restart에서_동일_hash를_복원한다() throws Exception {
    for (var scenario :
        List.of(
            new Scenario(new CommandInputParent.Compute(COMPUTE), "feasibility"),
            new Scenario(new CommandInputParent.Generation(GENERATION), "itinerary_generation"),
            new Scenario(new CommandInputParent.ScheduleRevision(REVISION), "schedule_revision"))) {
      var snapshot =
          canonicalizer.canonicalize(request(scenario.parent(), scenario.runType(), null));
      assertThat(repository.save(snapshot)).isEqualTo(snapshot);
      assertThat(repository.find(scenario.parent())).contains(snapshot);
    }
    assertThat(jdbc.queryForObject("select count(*) from public.compute_run_inputs", Integer.class))
        .isEqualTo(3);
  }

  @Test
  void Java와_PostgreSQL_canonical_hash가_key_order와_다국어에서_exactly_일치한다() throws Exception {
    var snapshot =
        canonicalizer.canonicalize(
            request(
                new CommandInputParent.Generation(GENERATION),
                "itinerary_generation",
                objectMapper.readTree(
                    "{\"refreshExternalFacts\":true,\"candidateCount\":3,\"targetDayId\":\"10820000-0000-0000-0000-000000000003\"}")));

    repository.save(snapshot);

    assertThat(
            jdbc.queryForObject(
                "select command_input_hash from public.compute_run_inputs where generation_run_id = ?",
                String.class,
                GENERATION))
        .isEqualTo(snapshot.commandInputHash());
  }

  @Test
  void duplicate_parent_hash_mismatch_sensitive_key와_parent_lineage_mismatch를_DB가_거부한다()
      throws Exception {
    var snapshot =
        canonicalizer.canonicalize(
            request(new CommandInputParent.Compute(COMPUTE), "feasibility", null));
    repository.save(snapshot);
    assertThatThrownBy(() -> repository.save(snapshot))
        .isExactlyInstanceOf(CommandInputStorageException.class)
        .hasMessage("COMMAND_INPUT_REJECTED");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.compute_run_inputs (
                      generation_run_id, owner_user_id, trip_plan_id, base_schedule_version_id,
                      run_type, schema_version, contract_version, algorithm_version,
                      structured_input, command_input_hash
                    ) values (?, ?, ?, ?, 'itinerary_generation', 1, 'command/v1', 'algorithm/v1',
                              '{"nested":{"jwt":"forbidden"}}'::jsonb, repeat('b', 64))
                    """,
                    GENERATION,
                    OWNER,
                    TRIP,
                    BASE))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.compute_run_inputs (
                      schedule_revision_run_id, owner_user_id, trip_plan_id, base_schedule_version_id,
                      run_type, schema_version, contract_version, algorithm_version,
                      structured_input, command_input_hash
                    ) values (?, ?, ?, null, 'schedule_revision', 1, 'command/v1', 'algorithm/v1',
                              '{}'::jsonb, repeat('c', 64))
                    """,
                    REVISION,
                    OWNER,
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void Java와_DB는_closed_schema_alias_unknown_nested_object를_동일하게_거부한다() throws Exception {
    for (String json :
        List.of(
            "{\"raw_body\":\"x\"}",
            "{\"requestBody\":\"x\"}",
            "{\"providerResponse\":\"x\"}",
            "{\"access\":{\"token\":\"x\"}}",
            "{\"phone\":\"x\"}",
            "{\"refreshExternalFacts\":false,\"unknown\":true}")) {
      var input = objectMapper.readTree(json);
      assertThatThrownBy(
              () ->
                  canonicalizer.canonicalize(
                      request(new CommandInputParent.Compute(COMPUTE), "feasibility", input)))
          .as(json)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(
              jdbc.queryForObject(
                  "select public.command_input_matches_schema('feasibility'::text, 1::smallint, ?::jsonb)",
                  Boolean.class,
                  json))
          .as(json)
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select public.command_input_matches_schema('feasibility'::text, 1::smallint, ?::jsonb)",
                Boolean.class,
                "{\"refreshExternalFacts\":false}"))
        .isTrue();
  }

  @Test
  void Java와_DB는_spare_time_canonical_RFC3339_boundary를_동일하게_판정한다() throws Exception {
    for (var scenario :
        List.of(
            new TimestampScenario("0001-01-01T00:00:00Z", true),
            new TimestampScenario("2000-02-29T23:59:59.123456789+18:00", true),
            new TimestampScenario("9999-12-31T23:59:59-18:00", true),
            new TimestampScenario("0000-01-01T00:00:00Z", false),
            new TimestampScenario("2025-02-29T00:00:00Z", false),
            new TimestampScenario("2026-01-01T24:00:00Z", false),
            new TimestampScenario("2026-01-01T00:00:00+18:01", false),
            new TimestampScenario("2026-01-01T00:00:00-18:01", false))) {
      String json =
          "{\"targetDayId\":\"10820000-0000-0000-0000-000000000003\",\"windowStart\":\""
              + scenario.value()
              + "\",\"windowEnd\":\""
              + scenario.value()
              + "\"}";
      var request =
          request(
              new CommandInputParent.Compute(COMPUTE), "spare_time", objectMapper.readTree(json));
      if (scenario.valid()) {
        assertThat(canonicalizer.canonicalize(request).runType()).isEqualTo("spare_time");
      } else {
        assertThatThrownBy(() -> canonicalizer.canonicalize(request))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(
              jdbc.queryForObject(
                  "select public.command_input_matches_schema('spare_time'::text, 1::smallint, ?::jsonb)",
                  Boolean.class,
                  json))
          .as(scenario.value())
          .isEqualTo(scenario.valid());
    }
    for (var scenario :
        List.of(
            new WindowScenario("2026-01-01T00:00:00+18:00", "2025-12-31T23:00:00Z", true),
            new WindowScenario("2026-01-01T00:00:00-18:00", "2026-01-01T23:00:00+18:00", false))) {
      String json =
          "{\"targetDayId\":\"10820000-0000-0000-0000-000000000003\",\"windowStart\":\""
              + scenario.start()
              + "\",\"windowEnd\":\""
              + scenario.end()
              + "\"}";
      assertThat(
              jdbc.queryForObject(
                  "select public.command_input_matches_schema('spare_time'::text, 1::smallint, ?::jsonb)",
                  Boolean.class,
                  json))
          .isEqualTo(scenario.valid());
    }
  }

  @Test
  void object_size_helper는_non_object에서_total이고_invalid_coarse_location은_23514다() {
    assertThat(
            jdbc.queryForList(
                """
                select public.command_jsonb_object_size(value)
                from (values
                  (null::jsonb), ('[]'::jsonb), ('1'::jsonb), ('null'::jsonb),
                  ('{\"a\":1,\"b\":2}'::jsonb)
                ) inputs(value)
                """,
                Integer.class))
        .containsExactly(null, null, null, null, 2);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.compute_run_inputs (
                      compute_run_id, owner_user_id, trip_plan_id, base_schedule_version_id,
                      run_type, schema_version, contract_version, algorithm_version,
                      structured_input, command_input_hash, location_supplied, coarse_location,
                      location_policy_version, location_observed_at
                    ) values (?, ?, ?, ?, 'feasibility', 1, 'command/v1', 'algorithm/v1',
                              '{\"refreshExternalFacts\":false}'::jsonb,
                              public.compute_command_input_hash(
                                'feasibility'::text, 1::smallint,
                                'command/v1'::text, 'algorithm/v1'::text, ?::uuid,
                                '{\"refreshExternalFacts\":false}'::jsonb, true::boolean, '[]'::jsonb
                              ), true, '[]'::jsonb, '1.0.0', now())
                    """,
                    COMPUTE,
                    OWNER,
                    TRIP,
                    BASE,
                    BASE))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .extracting(failure -> ((java.sql.SQLException) failure).getSQLState())
        .isEqualTo("23514");
  }

  @Test
  void service_role_일반_UPDATE는_금지되고_제한함수만_expiry를_earliest로_단축하며_equality가_due다() throws Exception {
    var location =
        new CommandLocation(
            new CoarseLocation.Grid100m(333, 777), "1.0.0", EVALUATED_AT, EVALUATED_AT, null, null);
    var snapshot =
        canonicalizer.canonicalize(
            request(
                new CommandInputParent.Compute(COMPUTE),
                "feasibility",
                objectMapper.readTree("{\"refreshExternalFacts\":false}"),
                location));
    repository.save(snapshot);
    UUID inputId =
        jdbc.queryForObject(
            "select id from public.compute_run_inputs where compute_run_id = ?",
            UUID.class,
            COMPUTE);
    activateScheduleVersion();
    jdbc.update("update public.trip_plans set status = 'completed' where id = ?", TRIP);
    OffsetDateTime tripEndedAt =
        jdbc.queryForObject(
            "select trip_ended_at from public.trip_plans where id = ?", OffsetDateTime.class, TRIP);
    jdbc.update(
        "update public.trip_plans set title = 'immutable anchor', updated_at = now() + interval '1 hour' where id = ?",
        TRIP);
    assertThat(
            jdbc.queryForObject(
                "select trip_ended_at from public.trip_plans where id = ?",
                OffsetDateTime.class,
                TRIP))
        .isEqualTo(tripEndedAt);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_plans set trip_ended_at = trip_ended_at + interval '1 second' where id = ?",
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);

    OffsetDateTime terminalAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
    jdbc.update(
        """
        update public.compute_runs
        set status = 'failed', completed_at = ?, next_attempt_at = null,
            error_code = 'TEST_TERMINAL'
        where id = ?
        """,
        terminalAt,
        COMPUTE);
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("set role service_role");
              }
              try {
                assertThatThrownBy(
                        () -> {
                          try (var update =
                              connection.prepareStatement(
                                  "update public.compute_run_inputs set location_expires_at = ? where id = ?")) {
                            update.setObject(
                                1,
                                OffsetDateTime.ofInstant(
                                    EVALUATED_AT.plusSeconds(86_400), ZoneOffset.UTC));
                            update.setObject(2, inputId);
                            update.executeUpdate();
                          }
                        })
                    .isInstanceOf(java.sql.SQLException.class);
                OffsetDateTime first =
                    shortenExpiry(connection, inputId, terminalAt.plusSeconds(1));
                OffsetDateTime unchanged =
                    shortenExpiry(connection, inputId, terminalAt.plusSeconds(2));
                assertThat(first).isEqualTo(tripEndedAt.plusHours(24));
                assertThat(unchanged).isEqualTo(first);
                try (var due =
                    connection.prepareStatement(
                        "select location_expires_at <= ? from public.compute_run_inputs where id = ?")) {
                  due.setObject(1, first);
                  due.setObject(2, inputId);
                  try (var result = due.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                  }
                }
              } finally {
                try (var statement = connection.createStatement()) {
                  statement.execute("reset role");
                }
              }
              return null;
            });
  }

  @Test
  void service_role은_SELECT와_INSERT만_가능하고_모든_schema_mutation은_금지된다() {
    for (String allowed : List.of("SELECT", "INSERT")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('service_role', 'public.compute_run_inputs', ?)",
                  Boolean.class,
                  allowed))
          .as(allowed)
          .isTrue();
    }
    for (String denied : List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('service_role', 'public.compute_run_inputs', ?)",
                  Boolean.class,
                  denied))
          .as(denied)
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select has_function_privilege('service_role', 'public.shorten_compute_run_input_location_expiry(uuid,timestamptz)', 'EXECUTE')",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select has_function_privilege('service_role', 'public.redact_due_compute_run_input_locations(timestamptz,integer)', 'EXECUTE')",
                Boolean.class))
        .isTrue();
    for (String role : List.of("anon", "authenticated")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_function_privilege(?, 'public.redact_due_compute_run_input_locations(timestamptz,integer)', 'EXECUTE')",
                  Boolean.class,
                  role))
          .as(role)
          .isFalse();
    }
  }

  @Test
  void due_정확경계는_위치5필드만_원자_redact하고_계보는_보존하며_반복은_noop다() throws Exception {
    Instant dueAt =
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID inputId = saveDueComputeInput(COMPUTE, 109, dueAt);
    Map<String, Object> before = immutableAudit(inputId);

    var first = cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500));
    var second = cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500));

    assertThat(first.redactedCount()).isOne();
    assertThat(second.redactedCount()).isZero();
    Map<String, Object> row = locationAudit(inputId);
    assertThat(row)
        .containsEntry("coarse_location", null)
        .containsEntry("location_precision_meters", null)
        .containsEntry("location_policy_version", null)
        .containsEntry("location_observed_at", null)
        .containsEntry("location_expires_at", null);
    assertThat(jdbcTimestampAtUtc(row.get("location_redacted_at")))
        .isEqualTo(OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC));
    assertThat(immutableAudit(inputId)).isEqualTo(before);
    assertThat(repository.findUsableLocation(new CommandInputParent.Compute(COMPUTE), dueAt))
        .isEmpty();
    assertThat(repository.find(new CommandInputParent.Compute(COMPUTE)))
        .get()
        .extracting(snapshot -> snapshot.location().isEmpty())
        .isEqualTo(true);
  }

  @Test
  void 미래_미제공_이미정리_row는_noop이고_DB_function은_stable_order_SKIPLLOCKED다() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID inputId = saveDueComputeInput(COMPUTE, 110, now.plusSeconds(1));

    assertThat(cleanupPort.execute(new CommandLocationCleanupCommand(now, 500)).redactedCount())
        .isZero();
    assertThat(locationAudit(inputId).get("coarse_location")).isNotNull();

    UUID unsuppliedRun = UUID.fromString("10910000-0000-0000-0000-000000000001");
    UUID noAnchorRun = UUID.fromString("10910000-0000-0000-0000-000000000002");
    for (UUID runId : List.of(unsuppliedRun, noAnchorRun)) {
      jdbc.update(
          """
          insert into public.compute_runs (
            id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
            input_hash, contract_version, algorithm_version
          ) values (?, ?, ?, ?, 'feasibility', 'queued', ?, 'compute/v1', 'algorithm/v1')
          """,
          runId,
          TRIP,
          DAY,
          BASE,
          "input-" + runId);
    }
    repository.save(
        canonicalizer.canonicalize(
            request(
                new CommandInputParent.Compute(unsuppliedRun),
                "feasibility",
                objectMapper.readTree("{\"refreshExternalFacts\":false}"))));
    repository.save(
        canonicalizer.canonicalize(
            request(
                new CommandInputParent.Compute(noAnchorRun),
                "feasibility",
                objectMapper.readTree("{\"refreshExternalFacts\":false}"),
                new CommandLocation(
                    new CoarseLocation.Grid100m(109, 109),
                    "2026-08-11.v1",
                    now,
                    now,
                    null,
                    null))));
    assertThat(cleanupPort.execute(new CommandLocationCleanupCommand(now, 500)).redactedCount())
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select location_redacted_at is null from public.compute_run_inputs where compute_run_id = ?",
                Boolean.class,
                unsuppliedRun))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select location_expires_at is null and coarse_location is not null from public.compute_run_inputs where compute_run_id = ?",
                Boolean.class,
                noAnchorRun))
        .isTrue();
    String definition =
        jdbc.queryForObject(
            "select pg_get_functiondef('public.redact_due_compute_run_input_locations(timestamptz,integer)'::regprocedure)",
            String.class);
    assertThat(definition.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))
        .contains(
            "location_expires_at <= evaluated_at",
            "order by location_expires_at, id",
            "limit batch_size",
            "for update skip locked");
  }

  @Test
  void batch_statement_중_한_row라도_실패하면_어떤_row도_부분_redact되지_않고_재시도된다() throws Exception {
    Instant dueAt =
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID firstRun = UUID.fromString("10920000-0000-0000-0000-000000000011");
    UUID secondRun = UUID.fromString("10920000-0000-0000-0000-000000000012");
    UUID first = saveDueComputeInput(firstRun, 111, dueAt);
    UUID second = saveDueComputeInput(secondRun, 112, dueAt);
    jdbc.execute(
        """
        create function public.issue109_fail_second_redaction() returns trigger
        language plpgsql as $$ begin
          if old.compute_run_id = '10920000-0000-0000-0000-000000000012'::uuid then
            raise exception 'sanitized injected failure';
          end if;
          return new;
        end $$;
        create trigger trg_issue109_fail_second_redaction
        before update on public.compute_run_inputs
        for each row execute function public.issue109_fail_second_redaction();
        """);
    try {
      assertThatThrownBy(() -> cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500)))
          .isExactlyInstanceOf(CommandLocationCleanupException.class)
          .hasMessage("COMMAND_LOCATION_CLEANUP_UNAVAILABLE")
          .hasNoCause();
      assertThat(locationAudit(first).get("coarse_location")).isNotNull();
      assertThat(locationAudit(second).get("coarse_location")).isNotNull();
    } finally {
      jdbc.execute(
          "drop trigger if exists trg_issue109_fail_second_redaction on public.compute_run_inputs");
      jdbc.execute("drop function if exists public.issue109_fail_second_redaction()");
    }

    assertThat(cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500)).redactedCount())
        .isEqualTo(2);
  }

  @Test
  void 부분_NULL_임의_redaction_재복원과_잘못된_함수_signature는_fail_closed다() throws Exception {
    Instant dueAt =
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID inputId = saveDueComputeInput(COMPUTE, 119, dueAt);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.compute_run_inputs set coarse_location=null, location_redacted_at=? where id=?",
                    java.sql.Timestamp.from(dueAt),
                    inputId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500)).redactedCount())
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.compute_run_inputs set coarse_location='{\"type\":\"GRID_100M\",\"gridX\":119,\"gridY\":119}'::jsonb where id=?",
                    inputId))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String invalidCall :
        List.of(
            "select public.redact_due_compute_run_input_locations(now(), true)",
            "select public.redact_due_compute_run_input_locations(now(), 1.5::numeric)",
            "select public.redact_due_compute_run_input_locations(null, 1)",
            "select public.redact_due_compute_run_input_locations(now(), 0)",
            "select public.redact_due_compute_run_input_locations(now(), 501)")) {
      assertThatThrownBy(() -> jdbc.queryForObject(invalidCall, Integer.class))
          .as(invalidCall)
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void 실제_501_due_row는_첫_batch_500과_다음_batch_1로_안정적으로_정리된다() throws Exception {
    Instant dueAt =
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    insertDeterministicallyOrderedDueInputs(dueAt);
    UUID expectedRemainingInputId =
        jdbc.queryForObject(
            """
            select id from public.compute_run_inputs
            where location_expires_at <= ?
            order by location_expires_at, id
            offset 500 limit 1
            """,
            UUID.class,
            java.sql.Timestamp.from(dueAt));

    assertThat(
            cleanupPort
                .execute(new CommandLocationCleanupCommand(dueAt, 500, Duration.ofSeconds(30)))
                .redactedCount())
        .isEqualTo(500);
    assertThat(
            jdbc.queryForObject(
                "select id from public.compute_run_inputs where location_expires_at <= ?",
                UUID.class,
                java.sql.Timestamp.from(dueAt)))
        .isEqualTo(expectedRemainingInputId)
        .isEqualTo(UUID.fromString("10931000-0000-0000-0000-000000000501"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where location_expires_at <= ?",
                Integer.class,
                java.sql.Timestamp.from(dueAt)))
        .isOne();
    assertThat(cleanupPort.execute(new CommandLocationCleanupCommand(dueAt, 500)).redactedCount())
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where location_expires_at <= ?",
                Integer.class,
                java.sql.Timestamp.from(dueAt)))
        .isZero();
  }

  private void insertDeterministicallyOrderedDueInputs(Instant dueAt) {
    Instant terminalAt = dueAt.minus(Duration.ofHours(24));
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
        TRIP,
        DAY,
        BASE,
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
        OWNER,
        TRIP,
        BASE,
        BASE,
        java.sql.Timestamp.from(terminalAt),
        java.sql.Timestamp.from(dueAt));
  }

  @Test
  void 다른_connection이_선두_row를_lock해도_SKIP_LOCKED로_다음_row를_정리하고_중복하지_않는다() throws Exception {
    Instant evaluatedAt =
        Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    UUID first =
        saveDueComputeInput(
            UUID.fromString("10940000-0000-0000-0000-000000000001"),
            401,
            evaluatedAt.minusSeconds(1));
    UUID second =
        saveDueComputeInput(
            UUID.fromString("10940000-0000-0000-0000-000000000002"), 402, evaluatedAt);

    try (var locker = dataSource.getConnection()) {
      locker.setAutoCommit(false);
      try (var lock =
          locker.prepareStatement(
              "select id from public.compute_run_inputs where id = ? for update")) {
        lock.setObject(1, first);
        try (var locked = lock.executeQuery()) {
          assertThat(locked.next()).isTrue();
        }
      }

      try (var worker = dataSource.getConnection();
          var cleanup =
              worker.prepareStatement(
                  "select public.redact_due_compute_run_input_locations(?, 1)")) {
        cleanup.setObject(1, OffsetDateTime.ofInstant(evaluatedAt, ZoneOffset.UTC));
        try (var result = cleanup.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getInt(1)).isOne();
        }
      }

      assertThat(locationAudit(first).get("coarse_location")).isNotNull();
      assertThat(locationAudit(second).get("coarse_location")).isNull();
      locker.rollback();
    }

    assertThat(
            cleanupPort
                .execute(new CommandLocationCleanupCommand(evaluatedAt, 500))
                .redactedCount())
        .isOne();
  }

  private static OffsetDateTime jdbcTimestampAtUtc(Object value) {
    assertThat(value).isInstanceOf(java.sql.Timestamp.class);
    return ((java.sql.Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
  }

  private UUID saveDueComputeInput(UUID runId, int grid, Instant dueAt) throws Exception {
    if (!runId.equals(COMPUTE)) {
      jdbc.update(
          """
          insert into public.compute_runs (
            id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
            input_hash, contract_version, algorithm_version
          ) values (?, ?, ?, ?, 'feasibility', 'queued', ?, 'compute/v1', 'algorithm/v1')
          """,
          runId,
          TRIP,
          DAY,
          BASE,
          "input-" + runId);
    }
    Instant terminalAt = dueAt.minus(Duration.ofHours(24));
    jdbc.update(
        """
        update public.compute_runs
        set status='failed', completed_at=?, next_attempt_at=null, error_code='TEST_TERMINAL'
        where id=?
        """,
        java.sql.Timestamp.from(terminalAt),
        runId);
    var location =
        new CommandLocation(
            new CoarseLocation.Grid100m(grid, grid),
            "2026-08-11.v1",
            terminalAt,
            dueAt,
            terminalAt,
            null);
    repository.save(
        canonicalizer.canonicalize(
            request(
                new CommandInputParent.Compute(runId),
                "feasibility",
                objectMapper.readTree("{\"refreshExternalFacts\":false}"),
                location)));
    return jdbc.queryForObject(
        "select id from public.compute_run_inputs where compute_run_id=?", UUID.class, runId);
  }

  private Map<String, Object> locationAudit(UUID inputId) {
    return jdbc.queryForMap(
        """
        select coarse_location, location_precision_meters, location_policy_version,
               location_observed_at, location_expires_at, location_redacted_at
        from public.compute_run_inputs where id=?
        """,
        inputId);
  }

  private Map<String, Object> immutableAudit(UUID inputId) {
    return jdbc.queryForMap(
        """
        select compute_run_id, generation_run_id, schedule_revision_run_id,
               owner_user_id, trip_plan_id, base_schedule_version_id,
               run_type, schema_version, contract_version, algorithm_version,
               structured_input::text, command_input_hash, location_supplied, created_at
        from public.compute_run_inputs where id=?
        """,
        inputId);
  }

  private void activateScheduleVersion() {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              boolean originalAutoCommit = connection.getAutoCommit();
              connection.setAutoCommit(false);
              try {
                try (var updatePlan =
                    connection.prepareStatement(
                        "update public.trip_plans set active_schedule_version_id = ? where id = ?")) {
                  updatePlan.setObject(1, BASE);
                  updatePlan.setObject(2, TRIP);
                  if (updatePlan.executeUpdate() != 1) {
                    throw new java.sql.SQLException("trip plan activation pointer was not updated");
                  }
                }
                try (var updateVersion =
                    connection.prepareStatement(
                        """
                        update public.trip_schedule_versions
                        set status = 'active', applied_at = now()
                        where id = ? and trip_plan_id = ?
                        """)) {
                  updateVersion.setObject(1, BASE);
                  updateVersion.setObject(2, TRIP);
                  if (updateVersion.executeUpdate() != 1) {
                    throw new java.sql.SQLException("schedule version was not activated");
                  }
                }
                try (var constraints = connection.createStatement()) {
                  constraints.execute("set constraints all immediate");
                }
                connection.commit();
              } catch (java.sql.SQLException failure) {
                connection.rollback();
                throw failure;
              } finally {
                connection.setAutoCommit(originalAutoCommit);
              }
              return null;
            });

    assertThat(
            jdbc.queryForObject(
                """
                select p.active_schedule_version_id = ?
                       and v.status = 'active'
                       and v.applied_at is not null
                from public.trip_plans p
                join public.trip_schedule_versions v
                  on v.id = p.active_schedule_version_id and v.trip_plan_id = p.id
                where p.id = ?
                """,
                Boolean.class,
                BASE,
                TRIP))
        .isTrue();
  }

  private static OffsetDateTime shortenExpiry(
      java.sql.Connection connection, UUID inputId, OffsetDateTime evaluatedAt)
      throws java.sql.SQLException {
    try (var call =
        connection.prepareStatement(
            "select public.shorten_compute_run_input_location_expiry(?, ?)")) {
      call.setObject(1, inputId);
      call.setObject(2, evaluatedAt);
      try (var result = call.executeQuery()) {
        if (!result.next()) throw new java.sql.SQLException("expiry transition returned no row");
        return result.getObject(1, OffsetDateTime.class);
      }
    }
  }

  private CommandInputRequest request(
      CommandInputParent parent, String runType, tools.jackson.databind.JsonNode input)
      throws Exception {
    return request(parent, runType, input == null ? defaultInput(runType) : input, null);
  }

  private tools.jackson.databind.JsonNode defaultInput(String runType) throws Exception {
    return switch (runType) {
      case "feasibility" -> objectMapper.readTree("{\"refreshExternalFacts\":false}");
      case "itinerary_generation" ->
          objectMapper.readTree(
              "{\"targetDayId\":\"10820000-0000-0000-0000-000000000003\",\"candidateCount\":3,\"refreshExternalFacts\":false}");
      case "schedule_revision" ->
          objectMapper.readTree(
              "{\"targetDayId\":\"10820000-0000-0000-0000-000000000003\",\"affectedItemIds\":[],\"instructionCodes\":[]}");
      default -> throw new IllegalArgumentException("unsupported test run type");
    };
  }

  private CommandInputRequest request(
      CommandInputParent parent,
      String runType,
      tools.jackson.databind.JsonNode input,
      CommandLocation location) {
    return new CommandInputRequest(
        parent, runType, 1, "command/v1", "algorithm/v1", input, OWNER, TRIP, BASE, location);
  }

  // #108/#109의 과거 위치 보존·redaction 계약은 017 이전 DB에서만 검증한다.
  // 현재 DB의 비수집/실제 repository/lease 동작은 LocationWriteGuardIntegrationTest가 검증한다.
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class LegacyInputConfiguration {
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    org.testcontainers.postgresql.PostgreSQLContainer postgresqlContainer() {
      return PostgreSqlTestContainerFactory.createBefore(
          "20260918000017_user_location_write_guard_purge.sql");
    }
  }

  private record Scenario(CommandInputParent parent, String runType) {}

  private record TimestampScenario(String value, boolean valid) {}

  private record WindowScenario(String start, String end, boolean valid) {}
}
