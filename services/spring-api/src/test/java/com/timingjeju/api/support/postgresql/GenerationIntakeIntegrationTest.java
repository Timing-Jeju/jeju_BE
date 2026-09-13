package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.trip.TripException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.test.context.TestPropertySource(
    properties = {
      "app.schedule-generation.enabled=true",
      "app.schedule-generation.worker.enabled=false",
      "app.schedule-generation.approved-airport-place-id=53000000-0000-0000-0000-000000000099"
    })
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class GenerationIntakeIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private GenerationIntakeStore intake;
  @Autowired private GenerationApplyStore applications;

  @Autowired
  private com.timingjeju.api.application.transportevent.service.TransportEventService
      transportEvents;

  @Autowired
  private com.timingjeju.api.application.trip.TripAggregateMutationCoordinator tripCoordinator;

  @Autowired private com.timingjeju.api.application.schedule.ScheduleStore schedules;
  @Autowired private com.timingjeju.api.domain.schedule.adapter.JdbcScheduleMutationStore mutations;

  @Test
  void 항공_터미널은_FE_ID_없이_승인된_공항으로_저장하고_생성에_연결한다() {
    var f = seed();
    var original =
        jdbc.queryForObject(
            "select scheduled_at from public.trip_transport_events where trip_plan_id=? and event_type='arrival'",
            java.sql.Timestamp.class,
            f.trip());
    jdbc.update(
        "delete from public.trip_transport_events where trip_plan_id=? and event_type='arrival'",
        f.trip());
    var result =
        transportEvents.put(
            f.owner(),
            f.trip(),
            new com.timingjeju.api.application.trip.TripExpectedRevision(f.trip(), 1),
            new com.timingjeju.api.application.transportevent.PutTransportEventCommand(
                "arrival",
                "flight",
                null,
                null,
                original.toInstant().atOffset(java.time.ZoneOffset.ofHours(9)),
                null,
                null));
    assertThat(result.event().terminalPlaceId())
        .isEqualTo(UUID.fromString("53000000-0000-0000-0000-000000000099"));
    assertThat(result.event().customTerminalName()).isNull();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 2, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    assertThat(inputs.find(accepted.runId()).orElseThrow().input().airportPlaceId())
        .isEqualTo(result.event().terminalPlaceId());
  }

  @Test
  void 저장된_도보_우선순위는_생성_snapshot에서_다른_수단으로_대체하지_않는다() {
    var f = seed();
    jdbc.update(
        "update public.trip_transport_modes set transport_mode='walk' where trip_plan_id=?",
        f.trip());
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    assertThat(inputs.find(accepted.runId()).orElseThrow().input().transportModes())
        .containsExactly("walk");
  }

  @Test
  void 생성_조회는_소유한_작업만_읽고_반복해도_DB를_변경하지_않는다() {
    var f = seed();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    var reads = new com.timingjeju.api.domain.generation.adapter.JdbcGenerationRunReader(jdbc);
    var before =
        jdbc.queryForMap(
            "select to_jsonb(r)::text as data from public.itinerary_generation_runs r where id=?",
            accepted.runId());
    for (int attempt = 0; attempt < 2; attempt++) {
      var saved = reads.findOwned(f.owner(), f.trip(), accepted.runId()).orElseThrow();
      assertThat(saved.status()).isEqualTo("queued");
      assertThat(saved.commandInputHash()).isEqualTo(accepted.commandInputHash());
      assertThat(saved.baseScheduleVersionId()).isNull();
      assertThat(saved.completedAt()).isNull();
      assertThat(saved.retainedUntil()).isNull();
      assertThat(saved.failure()).isNull();
      assertThat(saved.factsAsOf()).isNull();
      assertThat(saved.candidates()).isEmpty();
      assertThat(reads.findOwned(UUID.randomUUID(), f.trip(), accepted.runId())).isEmpty();
      assertThat(reads.findOwned(f.owner(), UUID.randomUUID(), accepted.runId())).isEmpty();
      assertThat(reads.findOwned(f.owner(), f.trip(), UUID.randomUUID())).isEmpty();
    }
    assertThat(
            jdbc.queryForMap(
                "select to_jsonb(r)::text as data from public.itinerary_generation_runs r where id=?",
                accepted.runId()))
        .isEqualTo(before);
    jdbc.update(
        """
        update public.itinerary_generation_runs
        set status='failed', completed_at=statement_timestamp(),
            retained_until=statement_timestamp()+interval '7 days', error_code='MCP_TIMEOUT'
        where id=?
        """,
        accepted.runId());
    var failed = reads.findOwned(f.owner(), f.trip(), accepted.runId()).orElseThrow();
    assertThat(failed.failure().code()).isEqualTo("MCP_TIMEOUT");
    assertThat(failed.failure().retryable()).isTrue();
    assertThat(failed.retainedUntil())
        .isEqualTo(failed.completedAt().plus(java.time.Duration.ofDays(7)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"normal", "rollback", "concurrent_apply"})
  void 최초_성공완료는_채워진_후보_세개를_원자저장하고_중간실패는_전체롤백한다(String scenario) {
    boolean failSecond = scenario.equals("rollback");
    var f = seed(2);
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    var leases = new com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository(jdbc);
    var lease =
        leases
            .claimAvailable("candidate-writer-test", java.time.Duration.ofSeconds(180), 1)
            .getFirst();
    assertThat(lease.runId()).isEqualTo(accepted.runId());
    var candidates = new java.util.ArrayList<GenerationCandidateProjection.Candidate>();
    var strategies = java.util.List.of("balanced", "relaxed", "experience_max");
    // 후보 검증은 별도 MCP 회귀가 담당한다. 여기서는 이미 검증된 storage 입력을 고정한다.
    for (int rank = 1; rank <= 3; rank++) {
      var candidate = org.mockito.Mockito.mock(GenerationCandidateProjection.Candidate.class);
      var start = java.time.OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
      var visitStart = start.plusMinutes(15);
      var end = visitStart.plusMinutes(90);
      var buffer =
          new GenerationTimeline.Event(
              "buffer-" + rank,
              1,
              "buffer",
              start,
              visitStart,
              15,
              f.airport(),
              "test-airport-fact",
              null,
              null,
              java.util.List.of("test-buffer-policy"));
      var items =
          java.util.List.of(
              new GenerationScheduleDay.Item(
                  1, f.airport(), "custom", start, start, 0, "day_start", null),
              new GenerationScheduleDay.Item(
                  2, f.airport(), "place_visit", visitStart, end, 90, null, "visit-" + rank),
              new GenerationScheduleDay.Item(
                  3, f.airport(), "custom", end, end, 0, "day_end", null));
      org.mockito.Mockito.when(candidate.scheduleDay())
          .thenReturn(
              new GenerationScheduleDay(
                  f.day(),
                  items,
                  java.util.List.of(
                      new GenerationScheduleDay.Connection(1, 2, java.util.List.of(buffer)),
                      new GenerationScheduleDay.Connection(2, 3, java.util.List.of()))));
      org.mockito.Mockito.when(candidate.rank()).thenReturn(rank);
      org.mockito.Mockito.when(candidate.strategy()).thenReturn(strategies.get(rank - 1));
      org.mockito.Mockito.when(candidate.score()).thenReturn(new java.math.BigDecimal("80.25"));
      org.mockito.Mockito.when(candidate.feasibility()).thenReturn("feasible");
      org.mockito.Mockito.when(candidate.explanation()).thenReturn("검증된 후보 합계 " + rank);
      org.mockito.Mockito.when(candidate.transfers()).thenReturn(java.util.List.of());
      org.mockito.Mockito.when(candidate.timeline())
          .thenReturn(new GenerationTimeline(java.util.List.of(buffer), java.util.List.of()));
      var zero = new GenerationTotals.CostRange(0, 0, false);
      var totals =
          new GenerationTotals(
              105,
              90,
              0,
              0,
              0,
              15,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              zero,
              zero,
              zero,
              java.util.List.of("test-buffer-policy"));
      var history =
          new GenerationSelectedDay(
              f.day(),
              start.toLocalDate(),
              start,
              start.plusHours(11),
              start,
              end,
              java.util.List.of(
                  new GenerationSelectedDay.SelectedPlace(
                      f.airport(),
                      "tourapi.place:79000001",
                      "visit",
                      java.util.List.of("test-airport-fact"))),
              totals,
              java.util.List.of("test-airport-fact", "test-buffer-policy"));
      org.mockito.Mockito.when(candidate.totals()).thenReturn(totals);
      org.mockito.Mockito.when(candidate.history()).thenReturn(history);
      candidates.add(candidate);
    }
    var result =
        new GenerationCandidateProjection(
            Instant.parse("2026-09-13T00:00:00Z"),
            "success",
            candidates,
            new GenerationEvidence(
                java.util.Map.of(
                    "test-airport-fact",
                        new GenerationEvidence.Fact(
                            java.util.Set.of("tourapi.place"), java.util.Set.of()),
                    "test-buffer-policy",
                        new GenerationEvidence.Fact(
                            java.util.Set.of(), java.util.Set.of("test-airport-fact"))),
                java.util.Set.of("tourapi.place")));
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var completionJdbc =
        new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
          @Override
          public int update(String sql, Object... args) {
            if (sql.contains("insert into public.itinerary_generation_candidates")
                && attempts.incrementAndGet() == 2
                && failSecond) throw new IllegalStateException("synthetic_candidate_write_failure");
            return super.update(sql, args);
          }
        };
    var store =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore(
            completionJdbc, transactions, inputs);
    if (failSecond) {
      assertThatThrownBy(() -> store.complete(lease, result, Instant.now().plusSeconds(120)))
          .hasMessage("synthetic_candidate_write_failure");
      assertThat(attempts).hasValue(2);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from timing_jeju_planner_private.generation_day_results where trip_plan_id=?",
                  Integer.class,
                  f.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.itinerary_generation_candidates where generation_run_id=?",
                  Integer.class,
                  lease.runId()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                  Integer.class,
                  f.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_items where trip_plan_id=?",
                  Integer.class,
                  f.trip()))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select status='running' and outcome is null and facts_as_of is null from public.itinerary_generation_runs where id=?",
                  Boolean.class,
                  lease.runId()))
          .isTrue();
      return;
    }
    assertThat(store.complete(lease, result, Instant.now().plusSeconds(120))).isTrue();
    assertThat(
            jdbc.queryForObject(
                """
        select count(*) from timing_jeju_planner_private.generation_day_results
        where trip_plan_id=? and history->'totals'->>'totalMinutes'='105'
          and jsonb_exists(evidence->'facts','test-airport-fact')
        """,
                Integer.class,
                f.trip()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                """
                select bool_and(timing_jeju_planner_private.generation_day_result_valid(history,evidence))
                from timing_jeju_planner_private.generation_day_results where trip_plan_id=?
                """,
                Boolean.class,
                f.trip()))
        .isTrue();
    for (var mutation :
        java.util.List.of(
            "jsonb_set(history,'{selectedPlaces,0,raw}','{}'),evidence",
            "jsonb_set(history,'{selectedPlaces,0,role}','null'),evidence",
            "jsonb_set(history,'{totals,geometry}','[]'),evidence",
            "jsonb_set(history,'{totals,busCost,raw}','{}'),evidence",
            "jsonb_set(history,'{evidenceFactIds}','[\"missing\"]'),evidence",
            "history,jsonb_set(evidence,'{facts,test-airport-fact,value}','{}')",
            "history,jsonb_set(evidence,'{facts,test-buffer-policy,inputFactIds}','[\"missing\"]')",
            "history,jsonb_set(evidence,'{facts,test-buffer-policy,inputFactIds}','[\"test-buffer-policy\"]')",
            "history,jsonb_set(evidence,'{facts,test-airport-fact,sourceIds}','[\"missing\"]')")) {
      assertThat(
              jdbc.queryForObject(
                  "select bool_and(timing_jeju_planner_private.generation_day_result_valid("
                      + mutation
                      + ")) from timing_jeju_planner_private.generation_day_results where trip_plan_id=?",
                  Boolean.class,
                  f.trip()))
          .as("중첩 원본·미지 참조·순환 거부: %s", mutation)
          .isFalse();
    }
    for (int chainSize : new int[] {4094, 4095}) {
      assertThat(
              jdbc.queryForObject(
                  """
                  select timing_jeju_planner_private.generation_day_result_valid(history,
                    evidence || jsonb_build_object('facts',(evidence->'facts') || (
                      select jsonb_object_agg('chain-' || i,jsonb_build_object(
                        'sourceIds','[]'::jsonb,'inputFactIds',case when i=1 then '[]'::jsonb
                        else jsonb_build_array('chain-' || (i-1)) end))
                      from generate_series(1,?) i)))
                  from timing_jeju_planner_private.generation_day_results where trip_plan_id=? limit 1
                  """,
                  Boolean.class,
                  chainSize,
                  f.trip()))
          .as("긴 계보의 저장 상한: %s", chainSize)
          .isEqualTo(chainSize == 4094);
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update timing_jeju_planner_private.generation_day_results set history=history where trip_plan_id=?",
                    f.trip()))
        .rootCause()
        .hasMessageContaining("ERROR: generation day result is immutable");
    for (int parentCount : new int[] {81, 82}) {
      assertThat(
              jdbc.queryForObject(
                  """
          select timing_jeju_planner_private.generation_day_result_valid(history,
            evidence || jsonb_build_object('facts',(evidence->'facts') || (
              select jsonb_object_agg('fan-' || i,jsonb_build_object(
                'sourceIds','[]'::jsonb,'inputFactIds',case when i<=200 then '[]'::jsonb
                else (select jsonb_agg('fan-' || parent) from generate_series(1,?) parent) end))
              from generate_series(1,400) i)))
          from timing_jeju_planner_private.generation_day_results where trip_plan_id=? limit 1
          """,
                  Boolean.class,
                  parentCount,
                  f.trip()))
          .as("계보 edge 상한: %s", parentCount)
          .isEqualTo(parentCount == 81);
    }
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into timing_jeju_planner_private.generation_day_results
                      (schedule_version_id,trip_plan_id,trip_day_id,history,evidence)
                    select schedule_version_id,trip_plan_id,trip_day_id,history,evidence
                    from timing_jeju_planner_private.generation_day_results where trip_plan_id=? limit 1
                    """,
                    f.trip()))
        .rootCause()
        .hasMessageContaining("ERROR: generation day result lineage mismatch");
    assertThat(
            jdbc.queryForList(
                "select score from public.itinerary_generation_candidates where generation_run_id=? order by rank_no",
                java.math.BigDecimal.class,
                lease.runId()))
        .containsExactly(
            new java.math.BigDecimal("80.25"),
            new java.math.BigDecimal("80.25"),
            new java.math.BigDecimal("80.25"));
    assertThat(
            jdbc.queryForList(
                "select explanation from public.itinerary_generation_candidates where generation_run_id=? order by rank_no",
                String.class,
                lease.runId()))
        .containsExactly("검증된 후보 합계 1", "검증된 후보 합계 2", "검증된 후보 합계 3");
    var query =
        new com.timingjeju.api.application.generation.service.GenerationQueryService(
            new com.timingjeju.api.domain.generation.adapter.JdbcGenerationRunReader(jdbc),
            java.time.Clock.systemUTC());
    var polled = query.read(f.owner(), f.trip(), lease.runId());
    assertThat(polled.status()).isEqualTo("succeeded");
    assertThat(polled.outcome()).isEqualTo("success");
    assertThat(polled.factsAsOf()).isEqualTo(result.factsAsOf());
    assertThat(
            jdbc.queryForObject(
                    "select facts_as_of from public.itinerary_generation_runs where id=?",
                    java.sql.Timestamp.class,
                    lease.runId())
                .toInstant())
        .isEqualTo(result.factsAsOf());
    assertThat(polled.candidates()).hasSize(3);
    assertThat(polled.candidates())
        .extracting(GenerationRunReader.SavedCandidate::strategy)
        .containsExactly("balanced", "relaxed", "experience_max");
    assertThat(polled.candidates())
        .allSatisfy(
            candidate -> {
              assertThat(candidate.score()).isEqualByComparingTo("80.25");
              assertThat(candidate.expiresAt())
                  .isEqualTo(candidate.createdAt().plus(java.time.Duration.ofHours(24)));
            });
    assertThatThrownBy(() -> query.read(UUID.randomUUID(), f.trip(), lease.runId()))
        .hasMessage("ASYNC_RUN_NOT_FOUND");
    assertThat(
            jdbc.queryForObject(
                """
        select count(*) from public.trip_legs where trip_plan_id=? and duration_minutes=0 and buffer_minutes=0 and
        facts->'generation'->'events' @> '[{"type":"buffer","durationMinutes":15}]'::jsonb
        """,
                Integer.class,
                f.trip()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_candidates where generation_run_id=? and expires_at=created_at+interval '24 hours'",
                Integer.class,
                lease.runId()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=? and status='candidate' and base_schedule_version_id is null",
                Integer.class,
                f.trip()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_items where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id is null and revision=1 from public.trip_plans where id=?",
                Boolean.class,
                f.trip()))
        .isTrue();
    for (var version :
        jdbc.queryForList(
            "select id from public.trip_schedule_versions where trip_plan_id=?",
            UUID.class,
            f.trip())) {
      var expiry =
          jdbc.queryForObject(
                  "select expires_at from public.itinerary_generation_candidates where schedule_version_id=?",
                  java.sql.Timestamp.class,
                  version)
              .toInstant();
      assertThatThrownBy(() -> schedules.readOwned(f.owner(), f.trip(), version, expiry))
          .hasMessage("CANDIDATE_EXPIRED");
      assertThat(schedules.readOwned(UUID.randomUUID(), f.trip(), version, expiry).status())
          .isEqualTo(com.timingjeju.api.application.schedule.ScheduleLookup.Status.TRIP_NOT_FOUND);
      assertThat(
              schedules
                  .readOwned(f.owner(), f.trip(), version, Instant.now())
                  .schedule()
                  .days()
                  .getFirst()
                  .items())
          .hasSize(3);
    }
    assertThatThrownBy(() -> jdbc.update("delete from public.trip_days where id=?", f.day()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from timing_jeju_planner_private.generation_day_results where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(3);
    if (scenario.equals("concurrent_apply")) {
      verifyConcurrentApplyHasOneWinner(f, lease.runId());
      return;
    }
    verifyMissingActivityHistoryRejected(f);
    verifyNextDayHistoryCopied(f, candidates, result.evidence());
  }

  private void verifyConcurrentApplyHasOneWinner(Fixture f, UUID runId) {
    // 실제 독립 트랜잭션 두 개가 같은 revision으로 다른 후보를 적용해도 하나만 성공한다.
    var candidates =
        jdbc.queryForList(
            "select id from public.itinerary_generation_candidates where generation_run_id=? order by rank_no limit 2",
            UUID.class,
            runId);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (var candidate : candidates) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("동시 적용 시작 신호가 없습니다");
                  try {
                    applications.apply(
                        f.owner(), f.trip(), runId, candidate, 1, null, Instant.now());
                    return "applied";
                  } catch (TripException failure) {
                    return failure.code();
                  }
                }));
      }
      assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var outcomes = new java.util.ArrayList<String>();
      for (var future : futures)
        outcomes.add(future.get(20, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(outcomes).containsExactlyInAnyOrder("applied", "TRIP_VERSION_CONFLICT");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("동시 적용 검증이 중단됐습니다", failure);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      throw new AssertionError("동시 적용 검증이 완료되지 않았습니다", failure);
    } finally {
      start.countDown();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_candidates where generation_run_id=? and selected_at is not null",
                Integer.class,
                runId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=? and status='active'",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select revision=2 and active_schedule_version_id=(select schedule_version_id from public.itinerary_generation_candidates where generation_run_id=? and selected_at is not null) from public.trip_plans where id=?",
                Boolean.class,
                runId,
                f.trip()))
        .isTrue();
  }

  private void verifyMissingActivityHistoryRejected(Fixture f) {
    // 실제 방문+휴식 항목 중 방문만 이력에 남기는 누락을 DB guard에서 거부한다.
    var base =
        jdbc.queryForObject(
            "select schedule_version_id from public.itinerary_generation_candidates where trip_plan_id=? and rank_no=1",
            UUID.class,
            f.trip());
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              var draft = UUID.randomUUID();
              jdbc.update(
                  """
          insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type,created_by_user_id,coverage_through_day_no)
          select ?,?,max(version_no)+1,'draft','ai_generation',?,1 from public.trip_schedule_versions where trip_plan_id=?
          """,
                  draft,
                  f.trip(),
                  f.owner(),
                  f.trip());
              jdbc.update(
                  """
          insert into public.trip_items(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,
            planned_start_at,planned_end_at,stay_minutes,boundary_role,required,source)
          select trip_plan_id,trip_day_id,?,case when boundary_role='day_end' then 4 else sequence_no end,
            item_type,place_id,title,planned_start_at,
            case when item_type='place_visit' then planned_start_at+interval '45 minutes' else planned_end_at end,
            case when item_type='place_visit' then 45 else stay_minutes end,boundary_role,required,source
          from public.trip_items where schedule_version_id=?
          """,
                  draft,
                  base);
              jdbc.update(
                  """
          insert into public.trip_items(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,
            planned_start_at,planned_end_at,stay_minutes,source)
          select trip_plan_id,trip_day_id,?,3,'free_time',place_id,'휴식',
            planned_start_at+interval '45 minutes',planned_end_at,45,source
          from public.trip_items where schedule_version_id=? and item_type='place_visit'
          """,
                  draft,
                  base);
              assertThatThrownBy(
                      () ->
                          jdbc.update(
                              """
          insert into timing_jeju_planner_private.generation_day_results
            (schedule_version_id,trip_plan_id,trip_day_id,history,evidence)
          select ?,trip_plan_id,trip_day_id,
            jsonb_set(jsonb_set(history,'{totals,visitMinutes}','45'),'{totals,restMinutes}','45'),evidence
          from timing_jeju_planner_private.generation_day_results where schedule_version_id=?
          """,
                              draft,
                              base))
                  .rootCause()
                  .hasMessageContaining("generation day result lineage mismatch");
            });
  }

  private void verifyExpiredApplyRollsBack(Fixture f, UUID run, UUID candidate, UUID version) {
    var original =
        jdbc.queryForMap(
            "select created_at,expires_at from public.itinerary_generation_candidates where id=?",
            candidate);
    jdbc.update(
        """
        update public.itinerary_generation_candidates
        set created_at=statement_timestamp()-interval '24 hours'+interval '5 seconds',
            expires_at=statement_timestamp()+interval '5 seconds' where id=?
        """,
        candidate);
    var wrote = new java.util.concurrent.atomic.AtomicBoolean();
    var delayed =
        new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
          @Override
          public int update(String sql, Object... args) {
            int count = super.update(sql, args);
            if (sql.contains("update public.itinerary_generation_candidates set selected_at")
                && count == 1) {
              wrote.set(true);
              queryForObject("select true from pg_sleep(6)", Boolean.class);
            }
            return count;
          }
        };
    var store =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationApplyStore(
            delayed, tripCoordinator);
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.execute(
                    ignored ->
                        store.apply(f.owner(), f.trip(), run, candidate, 1, null, Instant.now())))
        .hasMessage("CANDIDATE_EXPIRED");
    assertThat(wrote).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id is null and revision=1 from public.trip_plans where id=?",
                Boolean.class,
                f.trip()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                version))
        .isEqualTo("candidate");
    assertThat(
            jdbc.queryForObject(
                "select selected_at is null from public.itinerary_generation_candidates where id=?",
                Boolean.class,
                candidate))
        .isTrue();
    jdbc.update(
        "update public.itinerary_generation_candidates set created_at=?,expires_at=? where id=?",
        original.get("created_at"),
        original.get("expires_at"),
        candidate);
  }

  private void verifyNextDayHistoryCopied(
      Fixture f,
      java.util.List<GenerationCandidateProjection.Candidate> previousCandidates,
      GenerationEvidence evidence) {
    // 실제 적용 저장소를 거쳐 Day 1을 활성화한 뒤 Day 2 생성 이력을 검증한다.
    var base =
        jdbc.queryForObject(
            "select schedule_version_id from public.itinerary_generation_candidates where trip_plan_id=? and rank_no=1",
            UUID.class,
            f.trip());
    var nextDay =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id=? and day_no=2",
            UUID.class,
            f.trip());
    var selected =
        jdbc.queryForMap(
            "select id,generation_run_id from public.itinerary_generation_candidates where schedule_version_id=?",
            base);
    var candidateId = (UUID) selected.get("id");
    var runId = (UUID) selected.get("generation_run_id");
    verifyExpiredApplyRollsBack(f, runId, candidateId, base);
    assertThatThrownBy(
            () ->
                applications.apply(
                    UUID.randomUUID(), f.trip(), runId, candidateId, 1, null, Instant.now()))
        .hasMessage("TRIP_NOT_FOUND");
    verifyDurableApplyReplay(f, runId, candidateId, base);
    assertThatThrownBy(
            () ->
                applications.apply(f.owner(), f.trip(), runId, candidateId, 1, null, Instant.now()))
        .hasMessage("CANDIDATE_ALREADY_APPLIED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_candidates where trip_plan_id=? and selected_at is not null",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update public.trip_place_preferences set target_day_no=2 where trip_plan_id=?",
                  f.trip());
            });
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    2,
                    new CreateGenerationCommand(nextDay, base, 3),
                    Instant.now()))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=? and trip_day_id=?",
                Integer.class,
                f.trip(),
                nextDay))
        .isZero();
    // 동일 장소 재방문 결과의 거부는 MCP 실행 회귀가 담당한다. 여기서는 저장 복사만 검증한다.
    jdbc.update(
        "update public.trip_place_preferences set preference_type='preferred' where trip_plan_id=?",
        f.trip());
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 2, new CreateGenerationCommand(nextDay, base, 3), Instant.now());
    var lease =
        new com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository(jdbc)
            .claimAvailable("next-day-copy-test", java.time.Duration.ofSeconds(180), 1)
            .getFirst();
    assertThat(lease.runId()).isEqualTo(accepted.runId());
    var input = inputs.find(lease.runId()).orElseThrow().input();
    var restoredHistory =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationDayHistoryRepository(
                jdbc, tools.jackson.databind.json.JsonMapper.builder().build())
            .findPrevious(input);
    assertThat(restoredHistory).containsExactly(previousCandidates.getFirst().history());
    var candidates = new java.util.ArrayList<GenerationCandidateProjection.Candidate>();
    for (var previous : previousCandidates) {
      var day = previous.scheduleDay();
      var items =
          day.items().stream()
              .map(
                  item ->
                      new GenerationScheduleDay.Item(
                          item.sequenceNo(),
                          item.placeId(),
                          item.itemType(),
                          item.startAt().plusDays(1),
                          item.endAt().plusDays(1),
                          item.stayMinutes(),
                          item.boundaryRole(),
                          item.eventId()))
              .toList();
      var connections =
          day.connections().stream()
              .map(
                  connection ->
                      new GenerationScheduleDay.Connection(
                          connection.fromSequenceNo(),
                          connection.toSequenceNo(),
                          connection.events().stream()
                              .map(
                                  event ->
                                      new GenerationTimeline.Event(
                                          event.eventId(),
                                          event.sequence(),
                                          event.type(),
                                          event.startAt().plusDays(1),
                                          event.endAt().plusDays(1),
                                          event.durationMinutes(),
                                          event.placeId(),
                                          event.placeFactId(),
                                          event.mode(),
                                          event.distanceMeters(),
                                          event.evidenceFactIds()))
                              .toList()))
              .toList();
      var history = previous.history();
      var nextHistory =
          new GenerationSelectedDay(
              nextDay,
              history.tripDate().plusDays(1),
              input.boundary().startAt(),
              input.boundary().endAt(),
              history.dayStartAt().plusDays(1),
              history.dayEndAt().plusDays(1),
              history.selectedPlaces(),
              history.totals(),
              history.evidenceFactIds());
      candidates.add(
          new GenerationCandidateProjection.Candidate(
              previous.routeId(),
              previous.rank(),
              previous.strategy(),
              previous.feasibility(),
              previous.score(),
              previous.timeline(),
              previous.totals(),
              previous.transfers(),
              new GenerationScheduleDay(nextDay, items, connections),
              nextHistory));
    }
    var completion =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore(
            jdbc, transactions, inputs);
    assertThat(
            completion.complete(
                lease,
                new GenerationCandidateProjection(
                    Instant.parse("2026-09-13T00:00:00Z"), "success", candidates, evidence),
                Instant.now().plusSeconds(120)))
        .isTrue();
    assertThat(
            jdbc.queryForList(
                """
        select count(r.trip_day_id)::integer from public.itinerary_generation_candidates c
        left join timing_jeju_planner_private.generation_day_results r on r.schedule_version_id=c.schedule_version_id
        where c.generation_run_id=? group by c.id order by c.rank_no
        """,
                Integer.class,
                lease.runId()))
        .containsExactly(2, 2, 2);
    assertThat(
            jdbc.queryForObject(
                """
        select bool_and(copied.history=original.history and copied.evidence=original.evidence)
        from public.itinerary_generation_candidates c
        join timing_jeju_planner_private.generation_day_results copied
          on copied.schedule_version_id=c.schedule_version_id and copied.trip_day_id=?
        join timing_jeju_planner_private.generation_day_results original
          on original.schedule_version_id=? and original.trip_day_id=copied.trip_day_id
        where c.generation_run_id=?
        """,
                Boolean.class,
                f.day(),
                base,
                lease.runId()))
        .isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"deadline", "fence", "revision", "lease", "late_lease"})
  void 권한이_만료된_생성완료는_run과_일정을_변경하지_않는다(String invalidation) {
    var f = seed();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    var leases = new com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository(jdbc);
    var lease =
        leases
            .claimAvailable("completion-expiry-test", java.time.Duration.ofSeconds(180), 1)
            .getFirst();
    assertThat(lease.runId()).isEqualTo(accepted.runId());
    var deadline = Instant.now().plusSeconds(120);
    switch (invalidation) {
      case "deadline" -> deadline = Instant.now().minusSeconds(1);
      case "fence" ->
          lease =
              new com.timingjeju.api.application.asyncrun.RunLease(
                  lease.runId(), lease.fencingToken() + 1, lease.attempt());
      case "revision" ->
          jdbc.update("update public.trip_plans set revision=revision+1 where id=?", f.trip());
      case "lease" ->
          jdbc.update(
              "update public.itinerary_generation_runs set lease_expires_at=clock_timestamp()-interval '1 second' where id=?",
              lease.runId());
      case "late_lease" ->
          jdbc.update(
              "update public.itinerary_generation_runs set lease_expires_at=clock_timestamp()+interval '3 seconds' where id=?",
              lease.runId());
      default -> throw new AssertionError("알 수 없는 시나리오");
    }
    var wrote = new java.util.concurrent.atomic.AtomicBoolean();
    var completionJdbc =
        new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
          @Override
          public int update(String sql, Object... args) {
            int count = super.update(sql, args);
            if (invalidation.equals("late_lease")
                && count == 1
                && sql.contains("status='succeeded'")) {
              wrote.set(true);
              queryForObject("select true from pg_sleep(4)", Boolean.class);
            }
            return count;
          }
        };
    var store =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore(
            completionJdbc, transactions, inputs);
    var result =
        new GenerationCandidateProjection(
            Instant.parse("2026-09-13T00:00:00Z"),
            "insufficient_feasible_routes",
            java.util.List.of(),
            new GenerationEvidence(java.util.Map.of(), java.util.Set.of()));
    assertThat(store.complete(lease, result, deadline)).isFalse();
    if (invalidation.equals("late_lease")) assertThat(wrote).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status='running' and outcome is null and completed_at is null and facts_as_of is null from public.itinerary_generation_runs where id=?",
                Boolean.class,
                lease.runId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 생성불가_완료는_현재_lease에서만_후보없이_원자저장하고_중복완료를_거부한다() {
    var f = seed();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    var leases = new com.timingjeju.api.global.generation.JdbcGenerationLeaseRepository(jdbc);
    var lease =
        leases.claimAvailable("completion-test", java.time.Duration.ofSeconds(180), 1).getFirst();
    assertThat(lease.runId()).isEqualTo(accepted.runId());
    var store =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore(
            jdbc, transactions, inputs);
    var result =
        new GenerationCandidateProjection(
            Instant.parse("2026-09-13T00:00:00Z"),
            "insufficient_feasible_routes",
            java.util.List.of(),
            new GenerationEvidence(java.util.Map.of(), java.util.Set.of()));
    assertThat(store.complete(lease, result, Instant.now().plusSeconds(120))).isTrue();
    assertThat(store.complete(lease, result, Instant.now().plusSeconds(120))).isFalse();
    assertThat(
            jdbc.queryForObject(
                    "select facts_as_of from public.itinerary_generation_runs where id=?",
                    java.sql.Timestamp.class,
                    lease.runId())
                .toInstant())
        .isEqualTo(result.factsAsOf());
    assertThat(
            jdbc.queryForObject(
                "select status || ':' || outcome from public.itinerary_generation_runs where id=?",
                String.class,
                lease.runId()))
        .isEqualTo("succeeded:insufficient_feasible_routes");
    assertThat(
            jdbc.queryForObject(
                "select retained_until=completed_at+interval '7 days' and lease_owner is null and lease_expires_at is null from public.itinerary_generation_runs where id=?",
                Boolean.class,
                lease.runId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_candidates where generation_run_id=?",
                Integer.class,
                lease.runId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id is null and revision=1 from public.trip_plans where id=?",
                Boolean.class,
                f.trip()))
        .isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 생성_경계점은_임의체류분_없이_방문과_이동을_포함한_후보로_봉인한다(boolean samePlaceContinuity) {
    var f = seed();
    var version = UUID.randomUUID();
    var start = UUID.randomUUID();
    var visit = UUID.randomUUID();
    var end = UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type,coverage_through_day_no) values (?,?,1,'draft','ai_generation',1)",
                  version,
                  f.trip());
              jdbc.update(
                  "insert into public.trip_items(id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,planned_start_at,planned_end_at,stay_minutes,boundary_role,source) values (?,?,?,?,1,'custom',?,'일정 시작','2026-10-01T10:00:00+09:00','2026-10-01T10:00:00+09:00',0,'day_start','ai_generated')",
                  start,
                  f.trip(),
                  f.day(),
                  version,
                  f.airport());
              jdbc.update(
                  "insert into public.trip_items(id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,planned_start_at,planned_end_at,stay_minutes,source) values (?,?,?,?,2,'place_visit',?,'합성 방문','2026-10-01T10:10:00+09:00','2026-10-01T11:10:00+09:00',60,'ai_generated')",
                  visit,
                  f.trip(),
                  f.day(),
                  version,
                  f.airport());
              jdbc.update(
                  "insert into public.trip_items(id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,title,planned_start_at,planned_end_at,stay_minutes,boundary_role,source) values (?,?,?,?,3,'custom',?,'일정 종료','2026-10-01T11:20:00+09:00','2026-10-01T11:20:00+09:00',0,'day_end','ai_generated')",
                  end,
                  f.trip(),
                  f.day(),
                  version,
                  f.airport());
              for (int index = 0; index < 2; index++) {
                jdbc.update(
                    "insert into public.trip_legs(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,transport_mode,planned_departure_at,planned_arrival_at,walk_minutes,duration_minutes) values (?,?,?,?,?,?,'walk',?::timestamptz,?::timestamptz,10,10)",
                    f.trip(),
                    f.day(),
                    version,
                    index + 1,
                    index == 0 ? start : visit,
                    index == 0 ? visit : end,
                    index == 0 ? "2026-10-01T10:00:00+09:00" : "2026-10-01T11:10:00+09:00",
                    index == 0 ? "2026-10-01T10:10:00+09:00" : "2026-10-01T11:20:00+09:00");
              }
              if (samePlaceContinuity) {
                jdbc.update(
                    "update public.trip_items set planned_start_at='2026-10-01T10:00:00+09:00',planned_end_at='2026-10-01T11:00:00+09:00' where id=?",
                    visit);
                jdbc.update(
                    "update public.trip_items set planned_start_at='2026-10-01T11:00:00+09:00',planned_end_at='2026-10-01T11:00:00+09:00' where id=?",
                    end);
                jdbc.update(
                    "update public.trip_legs set walk_minutes=0,duration_minutes=0,distance_meters=0,estimated_fare=0,planned_departure_at=case when sequence_no=1 then '2026-10-01T10:00:00+09:00'::timestamptz else '2026-10-01T11:00:00+09:00'::timestamptz end,planned_arrival_at=case when sequence_no=1 then '2026-10-01T10:00:00+09:00'::timestamptz else '2026-10-01T11:00:00+09:00'::timestamptz end where schedule_version_id=?",
                    version);
              }
              if (samePlaceContinuity) assertInvalidZeroLegsRejected(version, f.trip());
              jdbc.update(
                  "update public.trip_schedule_versions set status='candidate' where id=?",
                  version);
            });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_items where schedule_version_id=? and boundary_role is not null and stay_minutes=0",
                Integer.class,
                version))
        .isEqualTo(2);
    // 이 봉인 fixture는 생성 metadata가 없으므로 미적용 후보 본문을 공개하지 않는다.
    assertThatThrownBy(() -> schedules.readOwned(f.owner(), f.trip(), version, Instant.now()))
        .hasMessage("CANDIDATE_EVIDENCE_UNAVAILABLE");
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update public.trip_schedule_versions set status='active',applied_at=now() where id=?",
                  version);
              jdbc.update(
                  "update public.trip_plans set active_schedule_version_id=?,status='planned' where id=?",
                  version,
                  f.trip());
            });
    var schedule = schedules.readOwned(f.owner(), f.trip(), version, Instant.now()).schedule();
    assertThat(schedule.days().getFirst().items())
        .extracting(com.timingjeju.api.application.schedule.ScheduleItemSnapshot::stayMinutes)
        .containsExactly(0, 60, 0);
    assertThat(schedule.days().getFirst().items())
        .extracting(com.timingjeju.api.application.schedule.ScheduleItemSnapshot::boundaryRole)
        .containsExactly("day_start", null, "day_end");
    var edited =
        mutations.patchItem(
            new com.timingjeju.api.application.schedule.ScheduleEditRecord<>(
                f.owner(),
                f.trip(),
                visit,
                new com.timingjeju.api.application.trip.TripExpectedRevision(f.trip(), 1),
                new com.timingjeju.api.application.schedule.PatchScheduleItemCommand(
                    version,
                    java.util.Set.of("memo"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "방문 메모 수정"),
                Instant.now()));
    var copied =
        schedules
            .readOwned(f.owner(), f.trip(), edited.activeScheduleVersionId(), Instant.now())
            .schedule();
    assertThat(copied.days().getFirst().items())
        .extracting(com.timingjeju.api.application.schedule.ScheduleItemSnapshot::boundaryRole)
        .containsExactly("day_start", null, "day_end");
    var copiedStart = copied.days().getFirst().items().getFirst();
    assertThatThrownBy(
            () ->
                mutations.moveItem(
                    new com.timingjeju.api.application.schedule.ScheduleEditRecord<>(
                        f.owner(),
                        f.trip(),
                        copiedStart.itemId(),
                        new com.timingjeju.api.application.trip.TripExpectedRevision(
                            f.trip(), edited.tripRevision()),
                        new com.timingjeju.api.application.schedule.MoveScheduleItemCommand(
                            edited.activeScheduleVersionId(),
                            1,
                            1,
                            copiedStart.plannedStartAt().atOffset(java.time.ZoneOffset.ofHours(9))),
                        Instant.now())))
        .hasMessage("SCHEDULE_ITEM_INVALID");
    assertThatThrownBy(
            () ->
                mutations.deleteItem(
                    new com.timingjeju.api.application.schedule.ScheduleEditRecord<>(
                        f.owner(),
                        f.trip(),
                        copiedStart.itemId(),
                        new com.timingjeju.api.application.trip.TripExpectedRevision(
                            f.trip(), edited.tripRevision()),
                        new com.timingjeju.api.application.schedule.DeleteScheduleItemCommand(
                            edited.activeScheduleVersionId()),
                        Instant.now())))
        .hasMessage("SCHEDULE_ITEM_INVALID");
    assertThatThrownBy(
            () ->
                mutations.patchItem(
                    new com.timingjeju.api.application.schedule.ScheduleEditRecord<>(
                        f.owner(),
                        f.trip(),
                        copiedStart.itemId(),
                        new com.timingjeju.api.application.trip.TripExpectedRevision(
                            f.trip(), edited.tripRevision()),
                        new com.timingjeju.api.application.schedule.PatchScheduleItemCommand(
                            edited.activeScheduleVersionId(),
                            java.util.Set.of("memo"),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "경계점 수정 금지"),
                        Instant.now())))
        .hasMessage("SCHEDULE_ITEM_INVALID");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(2);
  }

  private void assertInvalidZeroLegsRejected(UUID version, UUID trip) {
    for (String mutation :
        java.util.List.of(
            "distance_meters=null",
            "distance_meters=1",
            "estimated_fare=1",
            "buffer_minutes=1",
            "transport_mode='taxi'",
            "walk_minutes=1",
            "wait_minutes=1",
            "ride_minutes=1",
            "transfer_minutes=1",
            "planned_arrival_at=planned_departure_at+interval '30 seconds'")) {
      jdbc.execute("savepoint invalid_zero_leg");
      jdbc.update(
          "update public.trip_legs set "
              + mutation
              + " where schedule_version_id=? and sequence_no=1",
          version);
      assertThatThrownBy(
              () ->
                  jdbc.queryForObject(
                      "select public.assert_schedule_version_core_sealable(?,?)",
                      (rs, row) -> 1,
                      version,
                      trip))
          .hasMessageContaining("sealed schedule legs require consistent component durations");
      jdbc.execute("rollback to savepoint invalid_zero_leg");
      jdbc.execute("release savepoint invalid_zero_leg");
    }
  }

  private void verifyDurableApplyReplay(Fixture f, UUID runId, UUID candidateId, UUID base) {
    // 응답 유실 뒤 새 Controller에서도 DB 영수증을 재생하며 중복 적용하지 않는다.
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    java.util.function.Supplier<
            com.timingjeju.api.domain.generation.controller.GenerationApplyController>
        controller =
            () ->
                new com.timingjeju.api.domain.generation.controller.GenerationApplyController(
                    new com.timingjeju.api.application.generation.service.GenerationApplyService(
                        applications, java.time.Clock.systemUTC()),
                    () ->
                        java.util.Optional.of(
                            new com.timingjeju.api.application.security.CurrentUser(
                                f.owner(),
                                com.timingjeju.api.application.security.AuthenticatedRole
                                    .AUTHENTICATED,
                                null)),
                    receipts,
                    mapper);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setContentType("application/json");
    request.addHeader("Idempotency-Key", "durable-first-apply");
    request.addHeader(
        "If-Match", com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 1));
    byte[] body =
        "{\"expectedActiveScheduleVersionId\":null}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    request.setContent(body);
    var first =
        controller
            .get()
            .apply(f.trip().toString(), runId.toString(), candidateId.toString(), request);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
    assertThat(first.getHeaders().getFirst("ETag"))
        .isEqualTo(com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 2));
    var applied = mapper.readTree(first.getBody());
    assertThat(applied.path("previousScheduleVersionId").isNull()).isTrue();
    assertThat(applied.path("activeScheduleVersionId").asString()).isEqualTo(base.toString());
    // 이미 선택되고 만료된 후보도 같은 멱등 키는 최초 성공 영수증을 반환한다.
    jdbc.update(
        "update public.itinerary_generation_candidates set created_at=statement_timestamp()-interval '25 hours', expires_at=statement_timestamp()-interval '1 hour' where id=?",
        candidateId);
    request.setContent(body);
    var replay =
        controller
            .get()
            .apply(f.trip().toString(), runId.toString(), candidateId.toString(), request);
    assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThat(schedules.readOwned(f.owner(), f.trip(), base, Instant.now()).status())
        .isEqualTo(com.timingjeju.api.application.schedule.ScheduleLookup.Status.FOUND);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("ETag")).isEqualTo(first.getHeaders().getFirst("ETag"));
    assertThat(replay.getHeaders().getFirst("Location"))
        .isEqualTo(first.getHeaders().getFirst("Location"));
    assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    request.setContent(
        ("{\"expectedActiveScheduleVersionId\":\"" + base + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                controller
                    .get()
                    .apply(f.trip().toString(), runId.toString(), candidateId.toString(), request))
        .isInstanceOfSatisfying(
            com.timingjeju.api.application.idempotency.IdempotencyException.class,
            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private GenerationPlaceResolver placeResolver;

  @Test
  void AI_장소_변환_실패는_queued_run을_남기지_않는다() {
    var f = seed();
    org.mockito.Mockito.doThrow(GenerationException.inputUnavailable())
        .when(placeResolver)
        .resolve(org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 생성된_장소_fact_ID는_승인된_canonical_장소로_역매핑하고_미지_ID는_거부한다() {
    var f = seed();
    var resolver =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationPlaceResolver(jdbc);
    String factId =
        resolver.resolve(java.util.Set.of(f.airport()), Instant.now()).factId(f.airport());
    assertThat(resolver.resolveFactIds(java.util.Set.of(factId), Instant.now()).canonicalId(factId))
        .isEqualTo(f.airport());
    assertThat(resolver.resolveFactIds(java.util.Set.of(), Instant.now()).factIds()).isEmpty();
    for (var invalid :
        java.util.List.of(
            "tourapi.place:99999999999999999999999999999999",
            "unknown:1",
            f.airport().toString(),
            "tourapi.place:1' OR true--")) {
      assertThatThrownBy(
              () -> resolver.resolveFactIds(java.util.Set.of(factId, invalid), Instant.now()))
          .hasMessage("GENERATION_INPUT_UNAVAILABLE")
          .hasNoCause();
    }
  }

  @Test
  void worker_장소_매핑은_승인된_TourAPI_계보와_전체_ID_존재를_확인한다() {
    var f = seed();
    var resolver =
        new com.timingjeju.api.domain.generation.adapter.JdbcGenerationPlaceResolver(jdbc);
    var bindings = resolver.resolve(java.util.Set.of(f.airport()), Instant.now());
    String contentId =
        jdbc.queryForObject(
            "select content_id from public.tour_places where id=?", String.class, f.airport());
    assertThat(bindings.factId(f.airport())).isEqualTo("tourapi.place:" + contentId);
    assertThatThrownBy(
            () -> resolver.resolve(java.util.Set.of(f.airport(), UUID.randomUUID()), Instant.now()))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThat(resolver.resolve(java.util.Set.of(), Instant.now()).factIds()).isEmpty();
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private GenerationTripInputRepository inputs;

  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired private com.timingjeju.api.application.idempotency.IdempotencyUseCase receipts;
  private final java.util.List<Fixture> fixtures = new java.util.ArrayList<>();

  @Test
  void HTTP_완료_응답은_같은_키로_재생하고_다른_body는_409이다() throws Exception {
    var f = seed();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var controller =
        new com.timingjeju.api.domain.generation.controller.GenerationController(
            new com.timingjeju.api.application.generation.service.GenerationIntakeService(
                intake, java.time.Clock.systemUTC()),
            () ->
                java.util.Optional.of(
                    new com.timingjeju.api.application.security.CurrentUser(
                        f.owner(),
                        com.timingjeju.api.application.security.AuthenticatedRole.AUTHENTICATED,
                        null)),
            receipts,
            mapper);
    var path = "/api/v1/trips/" + f.trip() + "/schedule-generations";
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setContentType("application/json");
    request.addHeader("Idempotency-Key", "restart-safe,key");
    request.addHeader(
        "If-Match", com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 1));
    byte[] body = mapper.writeValueAsBytes(new CreateGenerationCommand(f.day(), null, 3));
    request.setContent(body);
    var first = controller.create(f.trip().toString(), request);
    assertThat(first.getStatusCode().value()).isEqualTo(202);
    request.setContent(body);
    request.removeHeader("If-Match");
    request.addHeader(
        "If-Match", com.timingjeju.api.application.trip.TripEntityTag.strong(f.trip(), 999));
    var replay = controller.create(f.trip().toString(), request);
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getHeaders().getFirst("Location"))
        .isEqualTo(first.getHeaders().getFirst("Location"));
    request.setContent(
        mapper.writeValueAsBytes(new CreateGenerationCommand(UUID.randomUUID(), null, 3)));
    assertThatThrownBy(() -> controller.create(f.trip().toString(), request))
        .isInstanceOf(com.timingjeju.api.application.idempotency.IdempotencyException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
  }

  @Test
  void snapshot_저장_실패는_run과_command도_롤백한다() {
    var f = seed();
    org.mockito.Mockito.doThrow(GenerationException.inputUnavailable())
        .when(inputs)
        .save(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .isInstanceOf(GenerationException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.compute_run_inputs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @org.junit.jupiter.api.AfterEach
  void 전용_fixture를_정리한다() {
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              for (var f : fixtures) {
                jdbc.update("delete from public.trip_plans where id=?", f.trip());
                jdbc.update("delete from auth.users where id=?", f.owner());
                jdbc.update("delete from public.tour_places where id=?", f.airport());
                jdbc.update(
                    "delete from public.external_api_snapshots where import_run_id=?",
                    f.imported());
                jdbc.update("delete from public.data_import_runs where id=?", f.imported());
              }
            });
  }

  @Test
  void 진행중인_같은_Day는_새_key라도_추가_접수하지_않는다() {
    var f = seed();
    var command = new CreateGenerationCommand(f.day(), null, 3);
    intake.accept(f.owner(), f.trip(), 1, command, Instant.now());
    assertThatThrownBy(() -> intake.accept(f.owner(), f.trip(), 1, command, Instant.now()))
        .isInstanceOf(GenerationException.class)
        .hasMessage("ACTIVE_RUN_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
  }

  @Test
  void 선박_여행은_작업을_접수하지_않는다() {
    var f = seed();
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored ->
                jdbc.update(
                    "update public.trip_transport_events set transport_type='ferry', terminal_place_id=null, terminal_name=null where trip_plan_id=?",
                    f.trip()));
    assertThatThrownBy(
            () ->
                intake.accept(
                    f.owner(),
                    f.trip(),
                    1,
                    new CreateGenerationCommand(f.day(), null, 3),
                    Instant.now()))
        .isInstanceOf(GenerationException.class)
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 최초_접수는_저장_조건에서_snapshot과_queued만_만들고_active는_변경하지_않는다() {
    var f = seed();
    var accepted =
        intake.accept(
            f.owner(), f.trip(), 1, new CreateGenerationCommand(f.day(), null, 3), Instant.now());
    assertThat(accepted.status()).isEqualTo("queued");
    assertThat(accepted.pollUrl())
        .isEqualTo("/api/v1/trips/" + f.trip() + "/schedule-generations/" + accepted.runId());
    assertThat(inputs.find(accepted.runId()).orElseThrow().input().places()).hasSize(1);
    assertThat(inputs.find(accepted.runId()).orElseThrow().canonicalInput())
        .doesNotContain("비공개 여행 제목");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=? and status='queued'",
                Integer.class,
                f.trip()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id from public.trip_plans where id=?",
                UUID.class,
                f.trip()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  @Test
  void 소유권과_ETag_실패는_run을_남기지_않는다() {
    var f = seed();
    var command = new CreateGenerationCommand(f.day(), null, 3);
    assertThatThrownBy(() -> intake.accept(UUID.randomUUID(), f.trip(), 1, command, Instant.now()))
        .isInstanceOf(TripException.class)
        .hasMessage("TRIP_NOT_FOUND");
    assertThatThrownBy(() -> intake.accept(f.owner(), f.trip(), 2, command, Instant.now()))
        .isInstanceOf(TripException.class)
        .hasMessage("TRIP_VERSION_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.itinerary_generation_runs where trip_plan_id=?",
                Integer.class,
                f.trip()))
        .isZero();
  }

  private Fixture seed() {
    return seed(1);
  }

  private Fixture seed(int dayCount) {
    var fixture =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(ignored -> seedRows(dayCount));
    fixtures.add(fixture);
    return fixture;
  }

  private Fixture seedRows(int dayCount) {
    var lastDate = java.time.LocalDate.of(2026, 10, 1).plusDays(dayCount - 1);
    UUID owner = UUID.randomUUID(), trip = UUID.randomUUID(), day = UUID.randomUUID();
    UUID airport = UUID.fromString("53000000-0000-0000-0000-000000000099");
    jdbc.update("insert into auth.users(id,email) values (?,?)", owner, owner + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", owner, owner + "@example.test");
    UUID imported = UUID.randomUUID(), snapshot = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.data_import_runs(id,source_kind,source_name,source_operation,data_version,status,
          parser_version,schema_version,sync_mode,scope_key,request_fingerprint,idempotency_key,source_provider,source_service)
        values (?,'tour_api','fixture','areaBasedList2','53','running','test-v1','test-v1','full','fixture-airport',?,?, 'tour-api','KorService2')
        """,
        imported,
        "a".repeat(64),
        imported.toString());
    jdbc.update(
        """
        insert into public.external_api_snapshots(id,import_run_id,source_provider,source_service,source_operation,
          scope_key,request_hash,page_key,fetched_at,parser_version,payload_hash,request_metadata_redacted,
          raw_payload,payload_size_bytes,redaction_version,payload_format,initial_parse_status,parse_status,parsed_at)
        values (?,?,'tour-api','KorService2','areaBasedList2','fixture-airport',?,'1',now(),'test-v1',?,
          '{}'::jsonb,'{}'::jsonb,2,'test-v1','JSON','parsed','parsed',now())
        """,
        snapshot,
        imported,
        "a".repeat(64),
        "f".repeat(64));
    jdbc.update(
        """
      insert into public.tour_places(id,content_id,name,normalized_name,category,region_code,location,source_provider,source_service,import_run_id,source_snapshot_id)
      values (?,'79000001','제주국제공항','제주국제공항','transport','39',ST_SetSRID(ST_MakePoint(126.49,33.50),4326),'tour-api','KorService2',?,?)
      """,
        airport,
        imported,
        snapshot);
    jdbc.update(
        "update public.data_import_runs set status='succeeded',finished_at=now() where id=?",
        imported);
    jdbc.update(
        """
      insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version,revision)
      values (?,?,?,'비공개 여행 제목','draft','2026-10-01',?::date,'Asia/Seoul','normal','fixture','53',1)
      """,
        trip,
        owner,
        trip.toString(),
        lastDate.toString());
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date,start_time,end_time) values (?,?,1,'2026-10-01','09:00','21:00')",
        day,
        trip);
    if (dayCount == 2) {
      jdbc.update("update public.trip_days set lodging_place_id=? where id=?", airport, day);
      jdbc.update(
          "insert into public.trip_days(id,trip_plan_id,day_no,trip_date,start_time,end_time) values (?,?,2,'2026-10-02','09:00','21:00')",
          UUID.randomUUID(),
          trip);
    }
    jdbc.update(
        "insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary) values (?,'taxi',1,true)",
        trip);
    jdbc.update(
        "insert into public.trip_place_preferences(trip_plan_id,place_id,preference_type,target_day_no,priority,requested_stay_minutes) values (?,?,'must_visit',1,100,90)",
        trip,
        airport);
    for (String type : new String[] {"arrival", "departure"}) {
      jdbc.update(
          "insert into public.trip_transport_events(trip_plan_id,event_type,transport_type,terminal_place_id,scheduled_at) values (?,?,'flight',?,?::timestamptz)",
          trip,
          type,
          airport,
          (type.equals("arrival") ? "2026-10-01T10:00" : lastDate + "T18:00") + ":00+09:00");
    }
    return new Fixture(owner, trip, day, airport, imported);
  }

  private record Fixture(UUID owner, UUID trip, UUID day, UUID airport, UUID imported) {}
}
