package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.application.trip.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGenerationApplyStore implements GenerationApplyStore {
  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator coordinator;

  public JdbcGenerationApplyStore(JdbcTemplate jdbc, TripAggregateMutationCoordinator coordinator) {
    this.jdbc = jdbc;
    this.coordinator = coordinator;
  }

  @Override
  public void requireOwned(UUID owner, UUID trip, UUID run, UUID candidate) {
    try {
      if (jdbc.queryForList(
              "select id from public.trip_plans where id=? and user_id=?", trip, owner)
          .isEmpty()) throw TripException.notFound();
      if (jdbc.queryForList(
              "select id from public.itinerary_generation_runs where id=? and trip_plan_id=? and requested_by_user_id=?",
              run,
              trip,
              owner)
          .isEmpty()) throw GenerationException.runNotFound();
      if (jdbc.queryForList(
              "select id from public.itinerary_generation_candidates where id=? and trip_plan_id=? and generation_run_id=?",
              candidate,
              trip,
              run)
          .isEmpty()) throw GenerationException.candidateNotFound();
    } catch (DataAccessException failure) {
      throw GenerationException.resultUnavailable();
    }
  }

  @Override
  @Transactional
  public Applied apply(
      UUID owner,
      UUID trip,
      UUID run,
      UUID candidate,
      long expectedRevision,
      UUID expectedActiveVersion,
      Instant requestedAt) {
    try {
      // Worker·수동 일정 편집과 같은 Trip→run/candidate/version 잠금 순서를 따른다.
      if (jdbc.queryForList(
              "select id from public.trip_plans where id=? and user_id=? for update", trip, owner)
          .isEmpty()) throw TripException.notFound();
      if (jdbc.queryForList(
              "select id from public.itinerary_generation_runs where id=? and trip_plan_id=? and requested_by_user_id=? for update",
              run,
              trip,
              owner)
          .isEmpty()) throw GenerationException.runNotFound();
      var rows =
          jdbc.query(
              """
          select c.schedule_version_id,c.selected_at,c.expires_at,r.status as run_status,r.outcome,
            r.base_schedule_version_id,s.trip_revision,v.status as version_status,v.source_type,
            (v.base_schedule_version_id is not distinct from r.base_schedule_version_id) as matching_base,
            exists (select 1 from timing_jeju_planner_private.generation_day_results h
              where h.schedule_version_id=v.id and h.trip_plan_id=r.trip_plan_id and h.trip_day_id=r.trip_day_id
                and timing_jeju_planner_private.generation_day_result_valid(h.history,h.evidence)) as evidence_available,
            c.expires_at<=clock_timestamp() as expired
          from public.itinerary_generation_candidates c
          join public.itinerary_generation_runs r on r.id=c.generation_run_id and r.trip_plan_id=c.trip_plan_id
          join public.trip_schedule_versions v on v.id=c.schedule_version_id and v.trip_plan_id=c.trip_plan_id
          join timing_jeju_planner_private.generation_trip_inputs s on s.run_id=r.id
            and s.trip_plan_id=r.trip_plan_id and s.owner_user_id=r.requested_by_user_id
            and s.target_day_id=r.trip_day_id and s.base_schedule_version_id is not distinct from r.base_schedule_version_id
          where c.id=? and c.generation_run_id=? and c.trip_plan_id=? and r.requested_by_user_id=?
            and r.contract_version='0.7.0' and r.algorithm_version='generation-v1'
          for update of c,v
          """,
              (row, index) ->
                  new Candidate(
                      row.getObject("schedule_version_id", UUID.class),
                      row.getTimestamp("selected_at") != null,
                      row.getTimestamp("expires_at") == null
                          ? null
                          : row.getTimestamp("expires_at").toInstant(),
                      row.getString("run_status"),
                      row.getString("outcome"),
                      row.getObject("base_schedule_version_id", UUID.class),
                      row.getLong("trip_revision"),
                      row.getString("version_status"),
                      row.getString("source_type"),
                      row.getBoolean("matching_base"),
                      row.getBoolean("evidence_available"),
                      row.getBoolean("expired")),
              candidate,
              run,
              trip,
              owner);
      if (rows.isEmpty()) throw GenerationException.candidateNotFound();
      var selected = rows.getFirst();
      if (selected.applied()) throw GenerationException.candidateAlreadyApplied();
      if (!"succeeded".equals(selected.runStatus()) || !"success".equals(selected.outcome()))
        throw GenerationException.candidateNotApplicable();
      if (selected.expiresAt() == null || selected.expired())
        throw GenerationException.candidateExpired();
      if (!selected.evidenceAvailable()) throw GenerationException.candidateEvidenceUnavailable();
      var applied =
          coordinator
              .executeMonotonic(
                  owner,
                  trip,
                  expectedRevision,
                  requestedAt,
                  (state, committedAt) -> {
                    if (!Objects.equals(expectedActiveVersion, state.activeScheduleVersionId()))
                      throw GenerationException.activeVersionConflict();
                    if (selected.revision() != state.revision()
                        || !Objects.equals(selected.base(), state.activeScheduleVersionId()))
                      throw GenerationException.candidateStale();
                    if (!selected.matchingBase()
                        || !"candidate".equals(selected.versionStatus())
                        || !"ai_generation".equals(selected.source()))
                      throw GenerationException.candidateNotApplicable();
                    if (!committedAt.isBefore(selected.expiresAt()))
                      throw GenerationException.candidateExpired();
                    jdbc.queryForObject(
                        "select public.assert_schedule_version_sealable(?,?)",
                        (row, index) -> 1,
                        selected.version(),
                        trip);
                    jdbc.queryForObject(
                        "select public.assert_schedule_item_required_references(?,?)",
                        (row, index) -> 1,
                        selected.version(),
                        trip);
                    if (state.activeScheduleVersionId() != null
                        && jdbc.update(
                                "update public.trip_schedule_versions set status='superseded' where id=? and trip_plan_id=? and status='active'",
                                state.activeScheduleVersionId(),
                                trip)
                            != 1) throw GenerationException.activeVersionConflict();
                    if (jdbc.update(
                            "update public.trip_schedule_versions set status='active',applied_at=? where id=? and trip_plan_id=? and status='candidate'",
                            Timestamp.from(committedAt),
                            selected.version(),
                            trip)
                        != 1) throw GenerationException.candidateNotApplicable();
                    var result =
                        new Applied(
                            trip,
                            run,
                            candidate,
                            state.activeScheduleVersionId(),
                            selected.version(),
                            state.revision() + 1,
                            committedAt);
                    return TripAggregateMutationPlan.maintain(
                        TripRootPatch.unchanged(),
                        () -> {
                          if (jdbc.update(
                                  """
              update public.trip_plans set active_schedule_version_id=?,stale=false,total_score=null,
                status=case when status in ('draft','generating') then 'planned' else status end
              where id=? and user_id=? and active_schedule_version_id is not distinct from ?::uuid
              """,
                                  selected.version(),
                                  trip,
                                  owner,
                                  expectedActiveVersion)
                              != 1) throw GenerationException.activeVersionConflict();
                          if (jdbc.update(
                                  """
              update public.itinerary_generation_candidates set selected_at=?
              where id=? and trip_plan_id=? and generation_run_id=? and selected_at is null
                and expires_at>clock_timestamp()
              """,
                                  Timestamp.from(committedAt),
                                  candidate,
                                  trip,
                                  run)
                              != 1) throw GenerationException.candidateExpired();
                        },
                        result);
                  })
              .payload();
      // UPDATE 조건 통과 뒤 trigger/coordinator 지연도 검사하고 모든 쓰기를 함께 롤백한다.
      if (!Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select clock_timestamp()<?::timestamptz",
              Boolean.class,
              selected.expiresAt().toString()))) throw GenerationException.candidateExpired();
      return applied;
    } catch (TripException failure) {
      if ("TRIP_DATA_UNAVAILABLE".equals(failure.code()))
        throw GenerationException.resultUnavailable();
      if (java.util.Set.of("TRIP_TERMINAL_STATE_CONFLICT", "TRIP_CONSTRAINT_VIOLATION")
          .contains(failure.code())) throw GenerationException.candidateNotApplicable();
      throw failure;
    } catch (DataAccessException failure) {
      throw GenerationException.resultUnavailable();
    }
  }

  private record Candidate(
      UUID version,
      boolean applied,
      Instant expiresAt,
      String runStatus,
      String outcome,
      UUID base,
      long revision,
      String versionStatus,
      String source,
      boolean matchingBase,
      boolean evidenceAvailable,
      boolean expired) {}
}
