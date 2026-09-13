package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.*;
import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.trip.*;
import com.timingjeju.api.global.commandinput.JdbcCommandInputSnapshotRepository;
import com.timingjeju.api.global.generation.JdbcGenerationTripInputRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class GenerationTripSnapshotIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void command만_있고_여행_snapshot이_없으면_worker는_claim하지_않는다() {
    seed();
    assertThat(
            new com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository(jdbc)
                .claimAvailable("snapshot-test", Duration.ofSeconds(180), 1))
        .isEmpty();
  }

  @Test
  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void 입력_INSERT는_다른_세션의_run_상태_변경을_직렬화한다() {
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    var snapshot = tx.execute(ignored -> seed());
    try {
      tx.executeWithoutResult(
          ignored -> {
            new JdbcGenerationTripInputRepository(jdbc, JsonMapper.builder().build())
                .save(snapshot);
            try (var connection = jdbc.getDataSource().getConnection()) {
              connection.setAutoCommit(false);
              try (var statement =
                  connection.prepareStatement(
                      "select id from public.itinerary_generation_runs where id=? for update nowait")) {
                statement.setObject(1, snapshot.runId());
                assertThatThrownBy(statement::executeQuery)
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(
                        failure ->
                            assertThat(((java.sql.SQLException) failure).getSQLState())
                                .isEqualTo("55P03"));
              } finally {
                connection.rollback();
              }
            } catch (java.sql.SQLException failure) {
              throw new AssertionError(failure);
            }
          });
    } finally {
      tx.executeWithoutResult(
          ignored -> {
            jdbc.update("delete from public.trip_plans where id=?", snapshot.input().tripId());
            jdbc.update("delete from auth.users where id=?", snapshot.ownerId());
          });
    }
  }

  @Test
  void service_role은_입력을_저장하고_복원할_수_있다() {
    var snapshot = seed();
    jdbc.execute("set local role service_role");
    var repository = new JdbcGenerationTripInputRepository(jdbc, JsonMapper.builder().build());
    repository.save(snapshot);
    assertThat(repository.find(snapshot.runId()).orElseThrow().input()).isEqualTo(snapshot.input());
  }

  @Test
  void 새_repository에서_동일_입력과_해시를_복원한다() {
    var snapshot = seed();
    new JdbcGenerationTripInputRepository(jdbc, JsonMapper.builder().build()).save(snapshot);
    var restored =
        new JdbcGenerationTripInputRepository(jdbc, JsonMapper.builder().build())
            .find(snapshot.runId())
            .orElseThrow();
    assertThat(restored.input()).isEqualTo(snapshot.input());
    assertThat(restored.inputHash()).isEqualTo(snapshot.inputHash());
    assertThat(restored.ownerId()).isEqualTo(snapshot.ownerId());
  }

  @Test
  void 저장한_입력은_관리자_연결에서도_UPDATE로_바꿀_수_없다() {
    var snapshot = seed();
    new JdbcGenerationTripInputRepository(jdbc, JsonMapper.builder().build()).save(snapshot);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        update timing_jeju_planner_private.generation_trip_inputs set trip_revision=trip_revision+1 where run_id=?
        """,
                    snapshot.runId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void 잘못된_hash는_DB에서도_거부한다() {
    var snapshot = seed();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        insert into timing_jeju_planner_private.generation_trip_inputs
        (run_id,trip_plan_id,owner_user_id,trip_revision,target_day_id,structured_input,input_hash)
        values (?,?,?,?,?,?::jsonb,?)
        """,
                    snapshot.runId(),
                    snapshot.input().tripId(),
                    snapshot.ownerId(),
                    7,
                    snapshot.input().boundary().dayId(),
                    snapshot.canonicalInput(),
                    "0".repeat(64)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void 닫힌_DB_projection은_중첩_원문과_좌표를_허용하지_않는다() {
    var snapshot = seed();
    var mapper = JsonMapper.builder().build();
    for (String field : List.of("originalText", "coordinates")) {
      var json = mapper.readTree(snapshot.canonicalInput());
      ((tools.jackson.databind.node.ObjectNode) json.get("boundary")).put(field, "비공개 원문");
      assertThat(
              jdbc.queryForObject(
                  "select timing_jeju_planner_private.generation_trip_input_valid(?::jsonb)",
                  Boolean.class,
                  mapper.writeValueAsString(json)))
          .isFalse();
    }
  }

  @Test
  void DB도_경계와_Day_번호의_불일치를_거부한다() {
    var snapshot = seed();
    var mapper = JsonMapper.builder().build();
    var json = mapper.readTree(snapshot.canonicalInput());
    ((tools.jackson.databind.node.ObjectNode) json.get("boundary")).put("dayNo", 2);
    assertThat(
            jdbc.queryForObject(
                "select timing_jeju_planner_private.generation_trip_input_valid(?::jsonb)",
                Boolean.class,
                mapper.writeValueAsString(json)))
        .isFalse();
  }

  @Test
  void DB는_활동창과_장소_선호의_변조도_거부한다() {
    var snapshot = seed();
    var mapper = JsonMapper.builder().build();
    for (String mismatch : List.of("window", "stay", "type", "airport")) {
      var json = mapper.readTree(snapshot.canonicalInput());
      var boundary = (tools.jackson.databind.node.ObjectNode) json.get("boundary");
      var place = (tools.jackson.databind.node.ObjectNode) json.get("places").get(0);
      switch (mismatch) {
        case "window" -> boundary.put("startAt", "2026-10-01T08:00:00+09:00");
        case "stay" -> place.put("stayMinutes", 91);
        case "type" -> place.put("type", "preferred");
        case "airport" -> boundary.put("startPlaceId", UUID.randomUUID().toString());
        default -> throw new AssertionError(mismatch);
      }
      assertThat(
              jdbc.queryForObject(
                  "select timing_jeju_planner_private.generation_trip_input_valid(?::jsonb)",
                  Boolean.class,
                  mapper.writeValueAsString(json)))
          .as(mismatch)
          .isFalse();
    }
  }

  private GenerationTripSnapshot seed() {
    var mapper = JsonMapper.builder().build();
    UUID owner = UUID.randomUUID(),
        trip = UUID.randomUUID(),
        day = UUID.randomUUID(),
        run = UUID.randomUUID();
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, owner + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", owner, owner + "@example.test");
    jdbc.update(
        """
        insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version,revision)
        values (?, ?, ?, 'snapshot fixture', 'draft', '2026-10-01','2026-10-01','Asia/Seoul','normal','fixture','53',7)
        """,
        trip,
        owner,
        trip.toString());
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-10-01')",
        day,
        trip);
    var command =
        new CommandInputCanonicalizer(mapper)
            .canonicalize(
                new CommandInputRequest(
                    new CommandInputParent.Generation(run),
                    "itinerary_generation",
                    2,
                    "0.7.0",
                    "fixture/v2",
                    mapper
                        .createObjectNode()
                        .put("targetDayId", day.toString())
                        .put("candidateCount", 3)
                        .put("refreshExternalFacts", false),
                    owner,
                    trip,
                    null));
    jdbc.update(
        """
        insert into public.itinerary_generation_runs(id,trip_plan_id,trip_day_id,status,contract_version,algorithm_version,idempotency_key,requested_by_user_id,structured_input)
        values (?,?,?,'queued','0.7.0','fixture/v2',?,?,?::jsonb)
        """,
        run,
        trip,
        day,
        run.toString(),
        owner,
        command.canonicalStructuredInput());
    new JdbcCommandInputSnapshotRepository(jdbc, mapper).save(command);
    var date = LocalDate.of(2026, 10, 1);
    var start = date.atTime(10, 0).atOffset(ZoneOffset.ofHours(9));
    var place = UUID.randomUUID();
    var input =
        new GenerationTripInput(
            trip,
            7,
            null,
            new GenerationDayBoundary(day, 1, place, place, start, start.plusHours(8)),
            place,
            List.of(new TripDay(day, 1, date, LocalTime.of(9, 0), LocalTime.of(21, 0))),
            List.of(),
            List.of(new TripPlacePreference(place, "must_visit", 1, 100, 90)),
            List.of("bus"),
            List.of(),
            false,
            List.of(
                new GenerationTripInput.PlaceInput(
                    place, "must_visit", 100, 90, "user_requested", null, null)));
    return GenerationTripSnapshot.create(run, owner, input, mapper);
  }

  @Test
  void 생성_여행입력은_비공개_스키마에_보존하며_클라이언트_권한을_주지_않는다() {
    assertThat(
            jdbc.queryForObject(
                """
        select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='timing_jeju_planner_private' and c.relname='generation_trip_inputs'
          and c.relrowsecurity
        """,
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
        select has_table_privilege('authenticated',
          'timing_jeju_planner_private.generation_trip_inputs','SELECT')
        """,
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                """
        select has_table_privilege('service_role',
          'timing_jeju_planner_private.generation_trip_inputs','UPDATE')
        """,
                Boolean.class))
        .isFalse();
  }
}
