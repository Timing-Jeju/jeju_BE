package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.asyncrun.RunLease;
import com.timingjeju.api.application.generation.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 생성 완료의 DB 경계. feature flag가 활성화된 worker 구성에서만 등록한다. */
public final class JdbcGenerationCompletionStore implements GenerationCompletionStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final GenerationTripInputRepository inputs;

  public JdbcGenerationCompletionStore(
      JdbcTemplate jdbc, PlatformTransactionManager manager, GenerationTripInputRepository inputs) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.inputs = Objects.requireNonNull(inputs);
    transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public boolean complete(RunLease lease, GenerationCandidateProjection result, Instant deadline) {
    Objects.requireNonNull(lease);
    Objects.requireNonNull(result);
    Objects.requireNonNull(deadline);
    if ("success".equals(result.outcome())
        && (!result.candidates().stream()
                .map(GenerationCandidateProjection.Candidate::strategy)
                .collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of("balanced", "relaxed", "experience_max"))
            || !result.candidates().stream()
                .map(GenerationCandidateProjection.Candidate::rank)
                .collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of(1, 2, 3)))) throw GenerationException.invalidResult();
    var snapshot = inputs.find(lease.runId()).orElseThrow(GenerationException::inputUnavailable);
    var input = snapshot.input();
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              var owned =
                  jdbc.queryForList(
                      """
          select id from public.trip_plans
          where id=? and user_id=? and revision=?
            and active_schedule_version_id is not distinct from ?::uuid
          for update
          """,
                      input.tripId(),
                      snapshot.ownerId(),
                      input.tripRevision(),
                      input.baseScheduleVersionId());
              if (owned.isEmpty()) return false;
              var leaseExpiries =
                  jdbc.query(
                      """
                  select lease_expires_at from public.itinerary_generation_runs
                  where id=? and status='running' and fencing_token=? and attempt_count=?
                    and trip_plan_id=? and requested_by_user_id=? and trip_day_id=?
                    and base_schedule_version_id is not distinct from ?::uuid
                    and lease_expires_at>clock_timestamp() and clock_timestamp()<?::timestamptz
                    and not exists (select 1 from public.itinerary_generation_candidates c
                                    where c.generation_run_id=itinerary_generation_runs.id)
                  for update
                  """,
                      (row, index) -> row.getTimestamp("lease_expires_at").toInstant(),
                      lease.runId(),
                      lease.fencingToken(),
                      lease.attempt(),
                      input.tripId(),
                      snapshot.ownerId(),
                      input.boundary().dayId(),
                      input.baseScheduleVersionId(),
                      deadline.toString());
              if (leaseExpiries.isEmpty()) return false;
              var leaseExpiry = leaseExpiries.getFirst();
              var writer = new JdbcGenerationCandidateWriter(jdbc);
              for (var candidate : result.candidates())
                writer.write(lease.runId(), snapshot, candidate, result.evidence());
              int changed =
                  jdbc.update(
                      """
          update public.itinerary_generation_runs
          set status='succeeded',outcome=?,facts_as_of=?::timestamptz,
              completed_at=statement_timestamp(),retained_until=statement_timestamp()+interval '7 days',
              lease_owner=null,lease_expires_at=null,heartbeat_at=null,next_attempt_at=null,
              error_code=null,error_message=null
          where id=? and trip_plan_id=? and requested_by_user_id=? and trip_day_id=?
            and base_schedule_version_id is not distinct from ?::uuid
            and status='running' and fencing_token=? and attempt_count=?
            and lease_expires_at>clock_timestamp() and clock_timestamp()<?::timestamptz
            and (select count(*) from public.itinerary_generation_candidates c
                 where c.generation_run_id=itinerary_generation_runs.id)=?
          """,
                      result.outcome(),
                      result.factsAsOf().toString(),
                      lease.runId(),
                      input.tripId(),
                      snapshot.ownerId(),
                      input.boundary().dayId(),
                      input.baseScheduleVersionId(),
                      lease.fencingToken(),
                      lease.attempt(),
                      deadline.toString(),
                      result.candidates().size());
              if (changed == 0) {
                status.setRollbackOnly();
                return false;
              }
              if (!Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "select clock_timestamp()<?::timestamptz and clock_timestamp()<?::timestamptz",
                      Boolean.class,
                      deadline.toString(),
                      leaseExpiry.toString()))) {
                status.setRollbackOnly();
                return false;
              }
              return true;
            }));
  }
}
