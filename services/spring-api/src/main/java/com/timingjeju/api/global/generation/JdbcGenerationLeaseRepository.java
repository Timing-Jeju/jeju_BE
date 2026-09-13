package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.asyncrun.RunLease;
import com.timingjeju.api.application.generation.GenerationRunLeases;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGenerationLeaseRepository implements GenerationRunLeases {
  private final JdbcTemplate jdbc;

  public JdbcGenerationLeaseRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit) {
    if (workerId == null || !workerId.matches("[A-Za-z0-9._:-]{1,100}") || limit != 1) {
      throw new IllegalArgumentException("생성 worker identity와 단일 claim이 필요합니다.");
    }
    long millis = leaseMillis(leaseDuration);
    jdbc.update(
        """
        update public.itinerary_generation_runs
        set status='failed', completed_at=statement_timestamp(),
            retained_until=statement_timestamp()+interval '7 days',
            lease_owner=null, lease_expires_at=null, heartbeat_at=null, next_attempt_at=null,
            error_code='GENERATION_RETRY_EXHAUSTED', error_message=null
        where status='running' and attempt_count=3 and lease_expires_at<=statement_timestamp()
        """);
    return jdbc.query(
        """
        with available as (
          select run.id
          from public.itinerary_generation_runs run
          join public.trip_plans plan on plan.id=run.trip_plan_id
            and plan.user_id=run.requested_by_user_id
          join public.compute_run_inputs input on input.generation_run_id=run.id
            and input.owner_user_id=plan.user_id and input.trip_plan_id=run.trip_plan_id
            and input.base_schedule_version_id is not distinct from run.base_schedule_version_id
            and input.run_type='itinerary_generation' and input.schema_version=2
            and input.contract_version=run.contract_version and input.algorithm_version=run.algorithm_version
            and input.structured_input=run.structured_input
          join timing_jeju_planner_private.generation_trip_inputs trip_input on trip_input.run_id=run.id
            and trip_input.trip_plan_id=run.trip_plan_id and trip_input.owner_user_id=plan.user_id
            and trip_input.target_day_id=run.trip_day_id and trip_input.schema_version=1
            and trip_input.base_schedule_version_id is not distinct from run.base_schedule_version_id
          where run.attempt_count<3 and (
            (run.status='queued' and coalesce(run.next_attempt_at,run.created_at)<=statement_timestamp())
            or (run.status='running' and run.lease_expires_at<=statement_timestamp()))
          order by coalesce(run.lease_expires_at,run.next_attempt_at,run.created_at),run.id
          for update of run skip locked limit 1
        )
        update public.itinerary_generation_runs run
        set status='running', started_at=coalesce(run.started_at,statement_timestamp()),
            attempt_count=run.attempt_count+1, fencing_token=run.fencing_token+1,
            lease_owner=?, lease_expires_at=statement_timestamp()+(? * interval '1 millisecond'),
            heartbeat_at=statement_timestamp(), next_attempt_at=null,
            error_code=null,error_message=null
        from available where run.id=available.id
        returning run.id,run.fencing_token,run.attempt_count
        """,
        (row, index) ->
            new RunLease(
                row.getObject("id", UUID.class),
                row.getLong("fencing_token"),
                row.getInt("attempt_count")),
        workerId,
        millis);
  }

  @Override
  public boolean heartbeat(RunLease lease, Duration leaseDuration) {
    Objects.requireNonNull(lease);
    return jdbc.update(
            """
        update public.itinerary_generation_runs
        set heartbeat_at=statement_timestamp(),lease_expires_at=statement_timestamp()+(? * interval '1 millisecond')
        where id=? and status='running' and fencing_token=? and lease_expires_at>statement_timestamp()
        """,
            leaseMillis(leaseDuration),
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean fail(RunLease lease, String stableErrorCode) {
    Objects.requireNonNull(lease);
    if (stableErrorCode == null || !stableErrorCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
      throw new IllegalArgumentException("정형 오류 코드만 저장할 수 있습니다.");
    }
    return jdbc.update(
            """
        update public.itinerary_generation_runs
        set status='failed',completed_at=statement_timestamp(),retained_until=statement_timestamp()+interval '7 days',
            lease_owner=null,lease_expires_at=null,heartbeat_at=null,next_attempt_at=null,
            error_code=?,error_message=null
        where id=? and status='running' and fencing_token=? and lease_expires_at>statement_timestamp()
        """,
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  private static long leaseMillis(Duration duration) {
    if (duration == null || duration.compareTo(Duration.ofSeconds(165)) < 0) {
      throw new IllegalArgumentException("생성 lease는 165초 이상이어야 합니다.");
    }
    return duration.toMillis();
  }

  @Override
  public boolean retry(RunLease lease, Duration delay, String stableErrorCode) {
    Objects.requireNonNull(lease);
    if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalArgumentException("생성 재시도 지연은 0~60초여야 합니다.");
    if (stableErrorCode == null || !stableErrorCode.matches("[A-Z][A-Z0-9_]{0,99}"))
      throw new IllegalArgumentException("정형 오류 코드만 저장할 수 있습니다.");
    return jdbc.update(
            """
        update public.itinerary_generation_runs
        set status='queued',next_attempt_at=statement_timestamp()+(? * interval '1 millisecond'),
            lease_owner=null,lease_expires_at=null,heartbeat_at=null,
            error_code=?,error_message=null
        where id=? and status='running' and fencing_token=? and attempt_count<3
          and lease_expires_at>statement_timestamp()
        """,
            delay.toMillis(),
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }
}
