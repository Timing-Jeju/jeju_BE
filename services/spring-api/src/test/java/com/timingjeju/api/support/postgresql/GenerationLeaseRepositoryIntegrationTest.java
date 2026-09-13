package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.global.commandinput.JdbcCommandInputSnapshotRepository;
import com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class GenerationLeaseRepositoryIntegrationTest {
  @Test
  void 재시작_후_만료lease를_회수하고_이전fence의_완료를_거부한다() {
    try (var container = PostgreSqlTestContainerFactory.create()) {
      container.start();
      var dataSource =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      var jdbc = new JdbcTemplate(dataSource);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      UUID runId = seed(jdbc, transaction);
      var firstStore = new JdbcGenerationLeaseRepository(jdbc);
      var first = firstStore.claimAvailable("worker-1", Duration.ofSeconds(180), 1).getFirst();
      assertThat(first.runId()).isEqualTo(runId);
      assertThat(first.attempt()).isEqualTo(1);
      assertThat(firstStore.claimAvailable("worker-2", Duration.ofSeconds(180), 1)).isEmpty();
      assertThat(firstStore.heartbeat(first, Duration.ofSeconds(180))).isTrue();
      jdbc.update(
          "update public.itinerary_generation_runs set lease_expires_at=statement_timestamp()-interval '1 second' where id=?",
          runId);
      var restarted = new JdbcGenerationLeaseRepository(jdbc);
      var second = restarted.claimAvailable("worker-2", Duration.ofSeconds(180), 1).getFirst();
      assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
      assertThat(second.attempt()).isEqualTo(2);
      assertThat(firstStore.heartbeat(first, Duration.ofSeconds(180))).isFalse();
      assertThat(firstStore.fail(first, "STALE_WORKER")).isFalse();
      assertThat(restarted.fail(second, "GENERATION_INPUT_UNAVAILABLE")).isTrue();
      assertThat(restarted.fail(second, "SECOND_COMPLETION")).isFalse();
      assertThat(
              jdbc.queryForObject(
                  "select retained_until=completed_at+interval '7 days' from public.itinerary_generation_runs where id=?",
                  Boolean.class,
                  runId))
          .isTrue();
      assertThat(restarted.claimAvailable("worker-3", Duration.ofSeconds(180), 1)).isEmpty();
    }
  }

  private static UUID seed(JdbcTemplate jdbc, TransactionTemplate transaction) {
    UUID owner = UUID.randomUUID();
    UUID trip = UUID.randomUUID();
    UUID day = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    var mapper = JsonMapper.builder().build();
    var input =
        mapper
            .createObjectNode()
            .put("targetDayId", day.toString())
            .put("candidateCount", 3)
            .put("refreshExternalFacts", false);
    var snapshot =
        new CommandInputCanonicalizer(mapper)
            .canonicalize(
                new CommandInputRequest(
                    new CommandInputParent.Generation(run),
                    "itinerary_generation",
                    2,
                    "0.7.0",
                    "fixture/v2",
                    input,
                    owner,
                    trip,
                    null));
    transaction.executeWithoutResult(
        ignored -> {
          jdbc.update(
              "insert into auth.users(id,email) values (?, 'generation53@example.test')", owner);
          jdbc.update(
              "insert into public.user_profiles(id,email) values (?, 'generation53@example.test')",
              owner);
          jdbc.update(
              "insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version) values (?,?,'generation53-token','fixture','draft','2026-09-01','2026-09-01','Asia/Seoul','normal','fixture','53')",
              trip,
              owner);
          jdbc.update(
              "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
              day,
              trip);
          jdbc.update(
              "insert into public.itinerary_generation_runs(id,trip_plan_id,trip_day_id,status,contract_version,algorithm_version,idempotency_key,requested_by_user_id,structured_input) values (?,?,?,'queued','0.7.0','fixture/v2',?,?,?::jsonb)",
              run,
              trip,
              day,
              run.toString(),
              owner,
              snapshot.canonicalStructuredInput());
          new JdbcCommandInputSnapshotRepository(jdbc, mapper).save(snapshot);
          var date = java.time.LocalDate.of(2026, 9, 1);
          var start = date.atTime(10, 0).atOffset(java.time.ZoneOffset.ofHours(9));
          var place = UUID.randomUUID();
          var tripInput =
              new com.timingjeju.api.application.generation.GenerationTripInput(
                  trip,
                  1,
                  null,
                  new com.timingjeju.api.application.generation.GenerationDayBoundary(
                      day, 1, place, place, start, start.plusHours(8)),
                  place,
                  java.util.List.of(
                      new com.timingjeju.api.application.trip.TripDay(
                          day,
                          1,
                          date,
                          java.time.LocalTime.of(9, 0),
                          java.time.LocalTime.of(21, 0))),
                  java.util.List.of(),
                  java.util.List.of(
                      new com.timingjeju.api.application.trip.TripPlacePreference(
                          place, "must_visit", 1, 100, 90)),
                  java.util.List.of("bus"),
                  java.util.List.of(),
                  false,
                  java.util.List.of(
                      new com.timingjeju.api.application.generation.GenerationTripInput.PlaceInput(
                          place, "must_visit", 100, 90, "user_requested", null, null)));
          new com.timingjeju.api.global.generation.JdbcGenerationTripInputRepository(jdbc, mapper)
              .save(
                  com.timingjeju.api.application.generation.GenerationTripSnapshot.create(
                      run, owner, tripInput, mapper));
        });
    return run;
  }
}
