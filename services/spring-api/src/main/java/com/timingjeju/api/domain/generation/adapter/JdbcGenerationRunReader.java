package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.GenerationFailure;
import com.timingjeju.api.application.generation.GenerationRunReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGenerationRunReader implements GenerationRunReader {
  private final JdbcTemplate jdbc;

  public JdbcGenerationRunReader(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
  }

  @Override
  public Optional<SavedRun> findOwned(UUID owner, UUID trip, UUID run) {
    try {
      var rows =
          jdbc.query(
              """
          select r.id,r.trip_plan_id,r.trip_day_id,r.base_schedule_version_id,
            r.status,r.outcome,c.command_input_hash,r.created_at,r.started_at,
            r.completed_at,r.retained_until,r.error_code,r.facts_as_of,
            candidate.id as candidate_id,candidate.schedule_version_id,candidate.rank_no,
            candidate.strategy,candidate.score,candidate.feasibility,candidate.explanation,
            candidate.created_at as candidate_created_at,candidate.expires_at,
            (t.revision<>s.trip_revision or
             t.active_schedule_version_id is distinct from r.base_schedule_version_id) as stale
          from public.itinerary_generation_runs r
          join public.trip_plans t on t.id=r.trip_plan_id and t.user_id=r.requested_by_user_id
          join public.compute_run_inputs c on c.generation_run_id=r.id
            and c.trip_plan_id=r.trip_plan_id and c.owner_user_id=r.requested_by_user_id
            and c.run_type='itinerary_generation' and c.schema_version=2
            and c.contract_version=r.contract_version and c.algorithm_version=r.algorithm_version
            and c.base_schedule_version_id is not distinct from r.base_schedule_version_id
          join timing_jeju_planner_private.generation_trip_inputs s on s.run_id=r.id
            and s.trip_plan_id=r.trip_plan_id and s.owner_user_id=r.requested_by_user_id
            and s.target_day_id=r.trip_day_id and s.schema_version=1
            and s.base_schedule_version_id is not distinct from r.base_schedule_version_id
          left join public.itinerary_generation_candidates candidate
            on candidate.generation_run_id=r.id and candidate.trip_plan_id=r.trip_plan_id
          where r.id=? and r.trip_plan_id=? and r.requested_by_user_id=?
            and r.contract_version='0.7.0' and r.algorithm_version='generation-v1'
          order by candidate.rank_no,candidate.id
          """,
              (row, index) ->
                  new ReadRow(
                      new SavedRun(
                          row.getObject("id", UUID.class),
                          row.getObject("trip_plan_id", UUID.class),
                          row.getObject("trip_day_id", UUID.class),
                          row.getObject("base_schedule_version_id", UUID.class),
                          row.getString("status"),
                          row.getString("outcome"),
                          row.getString("command_input_hash"),
                          instant(row, "created_at"),
                          instant(row, "started_at"),
                          instant(row, "completed_at"),
                          instant(row, "retained_until"),
                          instant(row, "facts_as_of"),
                          row.getBoolean("stale"),
                          GenerationFailure.from(
                              row.getString("status"), row.getString("error_code")),
                          java.util.List.of()),
                      candidate(row)),
              run,
              trip,
              owner);
      if (rows.isEmpty()) return Optional.empty();
      return Optional.of(
          rows.getFirst()
              .run()
              .withCandidates(
                  rows.stream()
                      .map(ReadRow::candidate)
                      .filter(java.util.Objects::nonNull)
                      .toList()));
    } catch (DataAccessException failure) {
      throw GenerationException.resultUnavailable();
    }
  }

  private record ReadRow(SavedRun run, SavedCandidate candidate) {}

  private static SavedCandidate candidate(ResultSet row) throws SQLException {
    UUID id = row.getObject("candidate_id", UUID.class);
    if (id == null) return null;
    return new SavedCandidate(
        id,
        row.getObject("schedule_version_id", UUID.class),
        row.getInt("rank_no"),
        row.getString("strategy"),
        row.getBigDecimal("score"),
        row.getString("feasibility"),
        row.getString("explanation"),
        instant(row, "candidate_created_at"),
        instant(row, "expires_at"));
  }

  private static Instant instant(ResultSet row, String field) throws SQLException {
    var value = row.getTimestamp(field);
    return value == null ? null : value.toInstant();
  }
}
