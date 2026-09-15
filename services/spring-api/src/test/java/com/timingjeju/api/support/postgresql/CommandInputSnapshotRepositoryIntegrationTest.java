package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.application.commandinput.CommandInputSnapshot;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.commandinput.CommandInputStorageException;
import com.timingjeju.api.global.commandinput.JdbcCommandInputSnapshotRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 현재 v2 저장소 계약. 과거 위치 TTL SQL은 별도 migration 테스트에서 검증한다. */
class CommandInputSnapshotRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private CommandInputCanonicalizer canonicalizer;
  @Autowired private CommandInputSnapshotRepository repository;
  private UUID owner;
  private UUID trip;
  private UUID day;
  private UUID base;

  @BeforeEach
  void 위치없는_여행과_계획_버전을_준비한다() {
    owner = UUID.randomUUID();
    trip = UUID.randomUUID();
    day = UUID.randomUUID();
    base = UUID.randomUUID();
    String email = owner + "@command-v2.test";
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, email);
    jdbc.update("insert into public.user_profiles(id,email) values (?,?)", owner, email);
    jdbc.update(
        "insert into"
            + " public.trip_plans(id,user_id,public_token,title,start_date,end_date,source_mode,data_version)"
            + " values (?,?,?,'무위치 계약',current_date,current_date,'fixture','v2')",
        trip,
        owner,
        trip.toString());
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values"
            + " (?,?,1,current_date)",
        day,
        trip);
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type)"
            + " values (?,?,1,'draft','initial')",
        base,
        trip);
  }

  @ParameterizedTest
  @ValueSource(strings = {"feasibility", "itinerary_generation", "schedule_revision"})
  void 부모별_v2_input을_저장하고_새_repository에서_동일하게_복원한다(String type) throws Exception {
    var snapshot = parent(type);
    assertThat(repository.save(snapshot)).isEqualTo(snapshot);
    assertThat(new JdbcCommandInputSnapshotRepository(jdbc, mapper).find(snapshot.parent()))
        .contains(snapshot);
    assertThat(
            jdbc.queryForObject(
                "select"
                    + " public.compute_command_input_hash(?::text,2::smallint,?::text,?::text,?::uuid,?::jsonb)",
                String.class,
                snapshot.runType(),
                snapshot.contractVersion(),
                snapshot.algorithmVersion(),
                base,
                snapshot.canonicalStructuredInput()))
        .isEqualTo(snapshot.commandInputHash());
    jdbc.execute("set constraints all immediate");
  }

  @Test
  void 중복_부모_input은_안정적인_오류로_거부한다() throws Exception {
    var snapshot = parent("feasibility");
    repository.save(snapshot);
    assertThatThrownBy(() -> repository.save(snapshot))
        .isExactlyInstanceOf(CommandInputStorageException.class)
        .hasMessage("COMMAND_INPUT_REJECTED")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"hash", "owner", "base"})
  void hash와_owner와_base를_변조한_input은_저장하지_않는다(String changed) throws Exception {
    var original = parent("feasibility");
    var snapshot =
        new CommandInputSnapshot(
            original.parent(),
            original.runType(),
            2,
            original.contractVersion(),
            original.algorithmVersion(),
            original.canonicalStructuredInput(),
            changed.equals("hash") ? "b".repeat(64) : original.commandInputHash(),
            changed.equals("owner") ? UUID.randomUUID() : owner,
            trip,
            changed.equals("base") ? UUID.randomUUID() : base);
    assertThatThrownBy(() -> repository.save(snapshot))
        .isExactlyInstanceOf(CommandInputStorageException.class)
        .hasMessage("COMMAND_INPUT_REJECTED")
        .hasNoCause();
  }

  @Test
  void service_role은_저장과_조회만_가능하고_일반_UPDATE는_거부된다() throws Exception {
    var snapshot = parent("feasibility");
    jdbc.execute("set local role service_role");
    repository.save(snapshot);
    assertThat(repository.find(snapshot.parent())).contains(snapshot);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.compute_run_inputs set structured_input=structured_input where"
                        + " compute_run_id=?",
                    snapshot.parent().id()))
        .isInstanceOf(DataAccessException.class);
  }

  @ParameterizedTest(name = "닫힌 입력 경계 {index}")
  @ValueSource(
      strings = {
        "{}",
        "{\"refreshExternalFacts\":null}",
        "{\"refreshExternalFacts\":false,\"nested\":{\"latitude\":0}}"
      })
  void Java와_DB는_v2의_누락_null_임의필드를_동일하게_거부한다(String json) throws Exception {
    var request =
        new CommandInputRequest(
            new CommandInputParent.Compute(UUID.randomUUID()),
            "feasibility",
            2,
            "0.7.0",
            "fixture/v2",
            mapper.readTree(json),
            owner,
            trip,
            base);
    assertThatThrownBy(() -> canonicalizer.canonicalize(request))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                jdbc.queryForObject(
                    "select"
                        + " public.compute_command_input_hash('feasibility'::text,2::smallint,'0.7.0'::text,'fixture/v2'::text,?::uuid,?::jsonb)",
                    String.class,
                    base,
                    json))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void DB_hash_함수도_v1을_암묵적으로_변환하지_않는다() {
    assertThatThrownBy(
            () ->
                jdbc.queryForObject(
                    "select"
                        + " public.compute_command_input_hash('feasibility'::text,1::smallint,'0.7.0'::text,'fixture/v2'::text,?::uuid,'{\"refreshExternalFacts\":false}'::jsonb)",
                    String.class,
                    base))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "0001-01-01T00:00:00Z,0001-01-01T00:00:00Z,true",
    "2000-02-29T23:59:59.123456789+18:00,2000-02-29T23:59:59.123456789+18:00,true",
    "9999-12-31T23:59:59-18:00,9999-12-31T23:59:59-18:00,true",
    "0000-01-01T00:00:00Z,0000-01-01T00:00:00Z,false",
    "2025-02-29T00:00:00Z,2025-02-29T00:00:00Z,false",
    "2026-01-01T24:00:00Z,2026-01-01T24:00:00Z,false",
    "2026-01-01T00:00:00+18:01,2026-01-01T00:00:00+18:01,false",
    "2026-01-01T00:00:00-18:01,2026-01-01T00:00:00-18:01,false",
    "2026-01-01T00:00:00+18:00,2025-12-31T23:00:00Z,true",
    "2026-01-01T00:00:00-18:00,2026-01-01T23:00:00+18:00,false"
  })
  void v2도_Java와_DB의_RFC3339_경계와_다국어_hash가_일치한다(String start, String end, boolean valid) {
    var input =
        mapper
            .createObjectNode()
            .put("windowEnd", end)
            .put("targetDayId", day.toString())
            .put("windowStart", start);
    var request =
        new CommandInputRequest(
            new CommandInputParent.Compute(UUID.randomUUID()),
            "spare_time",
            2,
            "계약/v2",
            "fixture/v2",
            input,
            owner,
            trip,
            base);
    assertThat(
            jdbc.queryForObject(
                "select"
                    + " public.command_input_matches_schema('spare_time'::text,2::smallint,?::jsonb)",
                Boolean.class,
                input.toString()))
        .isEqualTo(valid);
    if (valid) {
      var snapshot = canonicalizer.canonicalize(request);
      assertThat(
              jdbc.queryForObject(
                  """
                  select public.compute_command_input_hash(
                    'spare_time'::text,2::smallint,?::text,?::text,?::uuid,?::jsonb)
                  """,
                  String.class,
                  snapshot.contractVersion(),
                  snapshot.algorithmVersion(),
                  base,
                  input.toString()))
          .isEqualTo(snapshot.commandInputHash());
    } else {
      assertThatThrownBy(() -> canonicalizer.canonicalize(request))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private CommandInputSnapshot parent(String type) throws Exception {
    UUID id = UUID.randomUUID();
    CommandInputParent parent =
        switch (type) {
          case "itinerary_generation" -> new CommandInputParent.Generation(id);
          case "schedule_revision" -> new CommandInputParent.ScheduleRevision(id);
          default -> new CommandInputParent.Compute(id);
        };
    var input = mapper.createObjectNode();
    switch (type) {
      case "itinerary_generation" ->
          input
              .put("targetDayId", day.toString())
              .put("candidateCount", 3)
              .put("refreshExternalFacts", false);
      case "schedule_revision" -> {
        input.put("targetDayId", day.toString());
        input.putArray("affectedItemIds");
        input.putArray("instructionCodes");
      }
      default -> input.put("refreshExternalFacts", false);
    }
    var snapshot =
        canonicalizer.canonicalize(
            new CommandInputRequest(
                parent, type, 2, "0.7.0", "fixture/v2", input, owner, trip, base));
    switch (parent) {
      case CommandInputParent.Compute ignored ->
          jdbc.update(
              "insert into"
                  + " public.compute_runs(id,trip_plan_id,trip_day_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version)"
                  + " values (?,?,?,?,'feasibility','queued',?,'0.7.0','fixture/v2')",
              id,
              trip,
              day,
              base,
              snapshot.commandInputHash());
      case CommandInputParent.Generation ignored ->
          jdbc.update(
              "insert into"
                  + " public.itinerary_generation_runs(id,trip_plan_id,trip_day_id,base_schedule_version_id,status,contract_version,algorithm_version,idempotency_key,requested_by_user_id,structured_input)"
                  + " values (?,?,?,?,'queued','0.7.0','fixture/v2',?,?,?::jsonb)",
              id,
              trip,
              day,
              base,
              id.toString(),
              owner,
              snapshot.canonicalStructuredInput());
      case CommandInputParent.ScheduleRevision ignored ->
          jdbc.update(
              "insert into"
                  + " public.schedule_revision_runs(id,owner_user_id,trip_plan_id,base_schedule_version_id,target_trip_day_id,contract_version,algorithm_version,idempotency_key,request_hash)"
                  + " values (?,?,?,?,?,'0.7.0','fixture/v2',?,?)",
              id,
              owner,
              trip,
              base,
              day,
              id,
              snapshot.commandInputHash());
    }
    return snapshot;
  }
}
