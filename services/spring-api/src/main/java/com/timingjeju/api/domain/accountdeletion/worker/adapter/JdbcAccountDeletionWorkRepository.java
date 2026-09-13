package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AccountDeletionWorkRepository;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionLease;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionStep;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionWork;
import com.timingjeju.api.domain.accountdeletion.worker.EncryptedAuthSubject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAccountDeletionWorkRepository implements AccountDeletionWorkRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcAccountDeletionWorkRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<DeletionLease> claimAvailable(
      String owner, Instant now, Duration leaseDuration, int limit) {
    return jdbc.query(
        """
        with candidate as (
          select id
          from public.account_deletion_requests
          where status = 'queued'
             or (status = 'running'
                 and (lease_expires_at is null or lease_expires_at <= :now)
                 and (next_retry_at is null or next_retry_at <= :now))
          order by requested_at, id
          for update skip locked
          limit :limit
        )
        update public.account_deletion_requests request
        set status = 'running',
            lease_owner = :owner,
            lease_expires_at = :leaseExpiresAt,
            fencing_token = fencing_token + 1,
            attempt = attempt + 1,
            next_retry_at = null,
            failure_code = null
        from candidate
        where request.id = candidate.id
        returning request.id, request.lease_owner, request.fencing_token, request.attempt
        """,
        new MapSqlParameterSource()
            .addValue("owner", owner)
            .addValue("now", now)
            .addValue("leaseExpiresAt", now.plus(leaseDuration))
            .addValue("limit", limit),
        (rs, rowNumber) ->
            new DeletionLease(
                rs.getString("id"),
                rs.getString("lease_owner"),
                rs.getLong("fencing_token"),
                rs.getInt("attempt")));
  }

  @Override
  public Optional<DeletionWork> load(DeletionLease lease) {
    List<WorkRow> rows =
        jdbc.query(
            """
            select request.id, request.cancellation_requested,
                   request.auth_subject_ciphertext,
                   request.auth_subject_key_version, step.step
            from public.account_deletion_requests request
            left join public.account_deletion_steps step
              on step.request_id = request.id and step.completed_at is not null
            where request.id = :requestId
              and request.lease_owner = :owner
              and request.fencing_token = :fence
              and request.status = 'running'
              and request.lease_expires_at > now()
            order by step.step
            """,
            leaseParameters(lease),
            this::mapWorkRow);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    WorkRow first = rows.getFirst();
    EnumSet<DeletionStep> completed = EnumSet.noneOf(DeletionStep.class);
    rows.stream().map(WorkRow::step).filter(java.util.Objects::nonNull).forEach(completed::add);
    EncryptedAuthSubject encrypted =
        first.ciphertext() == null
            ? null
            : new EncryptedAuthSubject(first.ciphertext(), first.keyVersion());
    return Optional.of(
        new DeletionWork(first.requestId(), first.cancellationRequested(), completed, encrypted));
  }

  @Override
  public boolean heartbeat(DeletionLease lease, Instant now, Duration leaseDuration) {
    return update(
        """
        update public.account_deletion_requests
        set lease_expires_at = :leaseExpiresAt
        where id = :requestId and lease_owner = :owner and fencing_token = :fence
          and status = 'running' and lease_expires_at > :now
        """,
        leaseParameters(lease)
            .addValue("now", now)
            .addValue("leaseExpiresAt", now.plus(leaseDuration)));
  }

  @Override
  public boolean startStep(DeletionLease lease, DeletionStep step, Instant startedAt) {
    return update(
        """
        with fenced as (
          update public.account_deletion_requests
          set current_step = :step
          where id = :requestId and lease_owner = :owner and fencing_token = :fence
            and status = 'running' and lease_expires_at > :startedAt
          returning id
        )
        insert into public.account_deletion_steps(
          request_id, step, attempt, idempotency_key, started_at)
        select id, :step, :attempt, id || ':' || :step || ':' || :attempt, :startedAt
        from fenced
        on conflict (request_id, step, attempt) do update
          set started_at = public.account_deletion_steps.started_at
        """,
        stepParameters(lease, step).addValue("startedAt", startedAt));
  }

  @Override
  public boolean completeStep(DeletionLease lease, DeletionStep step, Instant completedAt) {
    return update(
        """
        update public.account_deletion_steps step_record
        set completed_at = coalesce(step_record.completed_at, :completedAt), failure_code = null
        where step_record.request_id = :requestId and step_record.step = :step
          and step_record.attempt = :attempt
          and exists (
            select 1 from public.account_deletion_requests request
            where request.id = :requestId and request.lease_owner = :owner
              and request.fencing_token = :fence and request.status = 'running'
              and request.lease_expires_at > :completedAt)
        """,
        stepParameters(lease, step).addValue("completedAt", completedAt));
  }

  @Override
  public boolean completeAuthDeletionAndClearSubject(DeletionLease lease, Instant completedAt) {
    return update(
        """
        with completed_step as (
        update public.account_deletion_steps step_record
          set completed_at = coalesce(step_record.completed_at, :completedAt), failure_code = null
          where step_record.request_id = :requestId
            and step_record.step = 'AUTH_USER_DELETED' and step_record.attempt = :attempt
            and exists (
              select 1 from public.account_deletion_requests request
              where request.id = :requestId and request.lease_owner = :owner
                and request.fencing_token = :fence and request.status = 'running'
                and request.lease_expires_at > :completedAt)
          returning request_id
        )
        update public.account_deletion_requests request
        set auth_subject_ciphertext = null, auth_subject_key_version = null,
            current_step = 'AUTH_USER_DELETED'
        where request.id = :requestId and request.lease_owner = :owner
          and request.fencing_token = :fence and request.status = 'running'
          and request.id in (select request_id from completed_step)
        """,
        leaseParameters(lease)
            .addValue("attempt", lease.attempt())
            .addValue("completedAt", completedAt));
  }

  @Override
  public boolean succeed(DeletionLease lease, Instant completedAt) {
    return terminal(lease, "succeeded", null, completedAt);
  }

  @Override
  public boolean confirmCancelled(DeletionLease lease, Instant completedAt) {
    return terminal(lease, "cancelled", null, completedAt);
  }

  @Override
  public boolean retry(
      DeletionLease lease, String failureCode, Instant nextRetryAt, Instant failedAt) {
    return update(
        """
        with fenced as (
          select id from public.account_deletion_requests
          where id = :requestId and lease_owner = :owner and fencing_token = :fence
            and status = 'running'
        ), step_failure as (
          update public.account_deletion_steps step_record
          set failure_code = :failureCode
          from fenced
          where step_record.request_id = fenced.id and step_record.attempt = :attempt
            and step_record.completed_at is null
        )
        update public.account_deletion_requests request
        set next_retry_at = :nextRetryAt, failure_code = :failureCode,
            lease_owner = null, lease_expires_at = null
        where request.id in (select id from fenced)
        """,
        leaseParameters(lease)
            .addValue("attempt", lease.attempt())
            .addValue("failureCode", failureCode)
            .addValue("nextRetryAt", nextRetryAt)
            .addValue("failedAt", failedAt));
  }

  @Override
  public boolean fail(DeletionLease lease, String failureCode, Instant failedAt) {
    return terminal(lease, "failed", failureCode, failedAt);
  }

  private boolean terminal(
      DeletionLease lease, String status, String failureCode, Instant completedAt) {
    return update(
        """
        update public.account_deletion_requests
        set status = :status, failure_code = :failureCode, completed_at = :completedAt,
            next_retry_at = null, lease_owner = null, lease_expires_at = null
        where id = :requestId and lease_owner = :owner and fencing_token = :fence
          and status = 'running'
        """,
        leaseParameters(lease)
            .addValue("status", status)
            .addValue("failureCode", failureCode)
            .addValue("completedAt", completedAt));
  }

  private boolean update(String sql, MapSqlParameterSource parameters) {
    return jdbc.update(sql, parameters) == 1;
  }

  private static MapSqlParameterSource leaseParameters(DeletionLease lease) {
    return new MapSqlParameterSource()
        .addValue("requestId", lease.requestId())
        .addValue("owner", lease.owner())
        .addValue("fence", lease.fencingToken());
  }

  private static MapSqlParameterSource stepParameters(DeletionLease lease, DeletionStep step) {
    return leaseParameters(lease)
        .addValue("step", step.name())
        .addValue("attempt", lease.attempt());
  }

  private WorkRow mapWorkRow(ResultSet resultSet, int rowNumber) throws SQLException {
    String rawStep = resultSet.getString("step");
    return new WorkRow(
        resultSet.getString("id"),
        resultSet.getBoolean("cancellation_requested"),
        resultSet.getString("auth_subject_ciphertext"),
        resultSet.getString("auth_subject_key_version"),
        rawStep == null ? null : DeletionStep.valueOf(rawStep));
  }

  private record WorkRow(
      String requestId,
      boolean cancellationRequested,
      String ciphertext,
      String keyVersion,
      DeletionStep step) {}
}
