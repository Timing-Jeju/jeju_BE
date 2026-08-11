package com.timingjeju.api.global.asyncrun;

import com.timingjeju.api.application.asyncrun.RunLease;
import com.timingjeju.api.application.asyncrun.RunLeaseRepository;
import com.timingjeju.api.application.asyncrun.RunResultSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRunLeaseRepository implements RunLeaseRepository {

  private static final String RETRY_EXHAUSTED = "ASYNC_RUN_RETRY_EXHAUSTED";

  private final JdbcTemplate jdbcTemplate;

  public JdbcRunLeaseRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit) {
    requireWorkerId(workerId);
    long leaseMillis = requirePositiveMillis(leaseDuration, "leaseDuration");
    if (limit <= 0 || limit > 50) {
      throw new IllegalArgumentException("claim limit은 1~50이어야 합니다.");
    }

    recoverExhaustedRuns();
    return jdbcTemplate.query(
        """
        with candidates as (
          select run.id, plan.data_version as source_data_version
          from public.compute_runs run
          join public.trip_plans plan on plan.id = run.trip_plan_id
          where run.attempt_count < 5
            and (
              (run.status = 'queued'
                and coalesce(run.next_attempt_at, run.created_at) <= statement_timestamp())
              or (run.status = 'running' and run.lease_expires_at <= statement_timestamp())
            )
          order by
            case when run.status = 'running' then 0 else 1 end,
            coalesce(run.lease_expires_at, run.next_attempt_at, run.created_at),
            run.created_at,
            run.id
          for update of run skip locked
          limit ?
        )
        update public.compute_runs run
        set status = 'running',
            started_at = coalesce(run.started_at, statement_timestamp()),
            facts_snapshot_at = coalesce(run.facts_snapshot_at, statement_timestamp()),
            source_data_version = coalesce(run.source_data_version, candidates.source_data_version),
            completed_at = null,
            attempt_count = run.attempt_count + 1,
            fencing_token = run.fencing_token + 1,
            lease_owner = ?,
            lease_expires_at = statement_timestamp() + (? * interval '1 millisecond'),
            heartbeat_at = statement_timestamp(),
            next_attempt_at = null,
            result_source = null,
            error_code = null,
            error_message = null
        from candidates
        where run.id = candidates.id
        returning run.id, run.fencing_token, run.attempt_count
        """,
        (resultSet, rowNumber) ->
            new RunLease(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getLong("fencing_token"),
                resultSet.getInt("attempt_count")),
        limit,
        workerId,
        leaseMillis);
  }

  @Override
  public boolean heartbeat(RunLease lease, Duration leaseDuration) {
    long leaseMillis = requirePositiveMillis(leaseDuration, "leaseDuration");
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set heartbeat_at = statement_timestamp(),
                lease_expires_at = statement_timestamp() + (? * interval '1 millisecond')
            where id = ? and status = 'running' and fencing_token = ?
              and lease_expires_at > statement_timestamp()
            """,
            leaseMillis,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean succeed(RunLease lease, RunResultSource resultSource) {
    Objects.requireNonNull(resultSource, "resultSource는 필수입니다.");
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'succeeded', result_source = ?, completed_at = statement_timestamp(),
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = null, error_code = null, error_message = null
            where id = ? and status = 'running' and fencing_token = ?
              and lease_expires_at > statement_timestamp()
            """,
            resultSource.databaseValue(),
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean retry(RunLease lease, Duration retryDelay, String stableErrorCode) {
    long retryMillis = requireNonNegativeMillis(retryDelay, "retryDelay");
    requireErrorCode(stableErrorCode);
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'queued', started_at = null, facts_snapshot_at = null,
                source_data_version = null, completed_at = null, result_source = null,
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = statement_timestamp() + (? * interval '1 millisecond'),
                error_code = ?, error_message = null
            where id = ? and status = 'running' and fencing_token = ? and attempt_count < 5
              and lease_expires_at > statement_timestamp()
            """,
            retryMillis,
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean fail(RunLease lease, String stableErrorCode) {
    requireErrorCode(stableErrorCode);
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'failed', completed_at = statement_timestamp(), result_source = null,
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = null, error_code = ?, error_message = null
            where id = ? and status = 'running' and fencing_token = ?
              and lease_expires_at > statement_timestamp()
            """,
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  private void recoverExhaustedRuns() {
    jdbcTemplate.update(
        """
        update public.compute_runs
        set status = 'failed', completed_at = statement_timestamp(), result_source = null,
            lease_owner = null, lease_expires_at = null, heartbeat_at = null,
            next_attempt_at = null, error_code = ?, error_message = null
        where status = 'running' and attempt_count >= 5
          and lease_expires_at <= statement_timestamp()
        """,
        RETRY_EXHAUSTED);
  }

  private static void requireWorkerId(String workerId) {
    if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
      throw new IllegalArgumentException("workerId는 1~100자의 비공백 값이어야 합니다.");
    }
  }

  private static void requireErrorCode(String stableErrorCode) {
    if (stableErrorCode == null || stableErrorCode.isBlank() || stableErrorCode.length() > 100) {
      throw new IllegalArgumentException("stableErrorCode는 1~100자의 비공백 값이어야 합니다.");
    }
  }

  private static long requirePositiveMillis(Duration duration, String name) {
    if (duration == null
        || duration.isZero()
        || duration.isNegative()
        || duration.toMillis() <= 0) {
      throw new IllegalArgumentException(name + "은 1ms 이상이어야 합니다.");
    }
    return duration.toMillis();
  }

  private static long requireNonNegativeMillis(Duration duration, String name) {
    if (duration == null || duration.isNegative()) {
      throw new IllegalArgumentException(name + "은 0 이상이어야 합니다.");
    }
    return duration.toMillis();
  }
}
