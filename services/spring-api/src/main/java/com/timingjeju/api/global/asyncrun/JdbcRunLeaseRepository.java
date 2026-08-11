package com.timingjeju.api.global.asyncrun;

import com.timingjeju.api.application.asyncrun.RunLease;
import com.timingjeju.api.application.asyncrun.RunLeaseRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
  public List<RunLease> claimAvailable(
      String workerId, Instant now, Instant leaseUntil, int limit) {
    requireWorkerId(workerId);
    requireFuture(leaseUntil, now, "leaseUntil");
    if (limit <= 0 || limit > 50) {
      throw new IllegalArgumentException("claim limit은 1~50이어야 합니다.");
    }

    recoverExhaustedRuns(now);
    return jdbcTemplate.query(
        """
        with candidates as (
          select id
          from public.compute_runs
          where attempt_count < 5
            and (
              (status = 'queued' and coalesce(next_attempt_at, created_at) <= ?)
              or (status = 'running' and lease_expires_at <= ?)
            )
          order by
            case when status = 'running' then 0 else 1 end,
            coalesce(lease_expires_at, next_attempt_at, created_at),
            created_at,
            id
          for update skip locked
          limit ?
        )
        update public.compute_runs run
        set status = 'running',
            started_at = coalesce(run.started_at, ?),
            completed_at = null,
            attempt_count = run.attempt_count + 1,
            fencing_token = run.fencing_token + 1,
            lease_owner = ?,
            lease_expires_at = ?,
            heartbeat_at = ?,
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
        timestamp(now),
        timestamp(now),
        limit,
        timestamp(now),
        workerId,
        timestamp(leaseUntil),
        timestamp(now));
  }

  @Override
  public boolean heartbeat(RunLease lease, Instant now, Instant leaseUntil) {
    requireFuture(leaseUntil, now, "leaseUntil");
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set heartbeat_at = ?, lease_expires_at = ?
            where id = ? and status = 'running' and fencing_token = ?
              and lease_expires_at > ?
            """,
            timestamp(now),
            timestamp(leaseUntil),
            lease.runId(),
            lease.fencingToken(),
            timestamp(now))
        == 1;
  }

  @Override
  public boolean succeed(RunLease lease, Instant completedAt) {
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'succeeded', result_source = 'computed', completed_at = ?,
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = null, error_code = null, error_message = null
            where id = ? and status = 'running' and fencing_token = ?
            """,
            timestamp(completedAt),
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean retry(RunLease lease, Instant nextAttemptAt, String stableErrorCode) {
    requireErrorCode(stableErrorCode);
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'queued', completed_at = null, result_source = null,
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = ?, error_code = ?, error_message = null
            where id = ? and status = 'running' and fencing_token = ? and attempt_count < 5
            """,
            timestamp(nextAttemptAt),
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  @Override
  public boolean fail(RunLease lease, Instant completedAt, String stableErrorCode) {
    requireErrorCode(stableErrorCode);
    return jdbcTemplate.update(
            """
            update public.compute_runs
            set status = 'failed', completed_at = ?, result_source = null,
                lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                next_attempt_at = null, error_code = ?, error_message = null
            where id = ? and status = 'running' and fencing_token = ?
            """,
            timestamp(completedAt),
            stableErrorCode,
            lease.runId(),
            lease.fencingToken())
        == 1;
  }

  private void recoverExhaustedRuns(Instant now) {
    jdbcTemplate.update(
        """
        update public.compute_runs
        set status = 'failed', completed_at = ?, result_source = null,
            lease_owner = null, lease_expires_at = null, heartbeat_at = null,
            next_attempt_at = null, error_code = ?, error_message = null
        where status = 'running' and attempt_count >= 5 and lease_expires_at <= ?
        """,
        timestamp(now),
        RETRY_EXHAUSTED,
        timestamp(now));
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

  private static void requireFuture(Instant future, Instant now, String name) {
    if (future == null || now == null || !future.isAfter(now)) {
      throw new IllegalArgumentException(name + "은 현재 시각보다 뒤여야 합니다.");
    }
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }
}
