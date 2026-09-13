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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAccountDeletionWorkRepository implements AccountDeletionWorkRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionOperations transaction;

  public JdbcAccountDeletionWorkRepository(
      NamedParameterJdbcTemplate jdbc, TransactionOperations transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
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
            .addValue("now", utc(now))
            .addValue("leaseExpiresAt", utc(now.plus(leaseDuration)))
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
                   request.destructive_started_at is not null as destructive_step_started,
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
        new DeletionWork(
            first.requestId(),
            first.cancellationRequested(),
            first.destructiveStepStarted(),
            completed,
            encrypted));
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
            .addValue("now", utc(now))
            .addValue("leaseExpiresAt", utc(now.plus(leaseDuration))));
  }

  @Override
  public boolean startStep(DeletionLease lease, DeletionStep step, Instant startedAt) {
    return fenced(
        lease,
        startedAt,
        step == DeletionStep.PROFILE_IMAGES_DELETED,
        parameters -> {
          int parent =
              jdbc.update(
                  """
                  update public.account_deletion_requests
                  set current_step = :step,
                      destructive_started_at = case when :destructive
                        then coalesce(destructive_started_at, :startedAt)
                        else destructive_started_at end
                  where id = :requestId and lease_owner = :owner and fencing_token = :fence
                    and status = 'running' and lease_expires_at > clock_timestamp()
                    and (not :destructive or cancellation_requested = false)
                  """,
                  parameters
                      .addValue("step", step.name())
                      .addValue("attempt", lease.attempt())
                      .addValue("destructive", step == DeletionStep.PROFILE_IMAGES_DELETED));
          requireOne(parent);
          return jdbc.update(
                  """
                  insert into public.account_deletion_steps(
                    request_id, step, attempt, idempotency_key, started_at)
                  values (:requestId, :step, :attempt,
                          :requestId || ':' || :step || ':' || :attempt, :startedAt)
                  on conflict (request_id, step, attempt) do update
                    set started_at = public.account_deletion_steps.started_at
                  """,
                  parameters)
              == 1;
        });
  }

  @Override
  public boolean completeStep(DeletionLease lease, DeletionStep step, Instant completedAt) {
    return fenced(
        lease,
        completedAt,
        false,
        parameters ->
            jdbc.update(
                    """
                    update public.account_deletion_steps
                    set completed_at = coalesce(completed_at, :completedAt), failure_code = null
                    where request_id = :requestId and step = :step and attempt = :attempt
                    """,
                    parameters.addValue("step", step.name()).addValue("attempt", lease.attempt()))
                == 1);
  }

  @Override
  public boolean completeAuthDeletionAndClearSubject(DeletionLease lease, Instant completedAt) {
    return fenced(
        lease,
        completedAt,
        false,
        parameters -> {
          requireOne(
              jdbc.update(
                  """
                  update public.account_deletion_steps
                  set completed_at = coalesce(completed_at, :completedAt), failure_code = null
                  where request_id = :requestId and step = 'AUTH_USER_DELETED'
                    and attempt = :attempt
                  """,
                  parameters.addValue("attempt", lease.attempt())));
          return jdbc.update(
                  """
                  update public.account_deletion_requests
                  set auth_subject_ciphertext = null, auth_subject_key_version = null,
                      current_step = 'AUTH_USER_DELETED'
                  where id = :requestId and lease_owner = :owner and fencing_token = :fence
                    and status = 'running' and lease_expires_at > clock_timestamp()
                  """,
                  parameters)
              == 1;
        });
  }

  @Override
  public boolean succeed(DeletionLease lease, Instant completedAt) {
    return terminal(lease, "succeeded", null, completedAt);
  }

  @Override
  public boolean confirmCancelled(DeletionLease lease, Instant completedAt) {
    return fenced(
        lease,
        completedAt,
        false,
        parameters ->
            jdbc.update(
                    """
                    update public.account_deletion_requests
                    set status = 'cancelled', failure_code = null, completed_at = :completedAt,
                        next_retry_at = null, lease_owner = null, lease_expires_at = null
                    where id = :requestId and lease_owner = :owner and fencing_token = :fence
                      and status = 'running' and lease_expires_at > clock_timestamp()
                      and cancellation_requested and destructive_started_at is null
                    """,
                    parameters)
                == 1);
  }

  @Override
  public boolean retry(
      DeletionLease lease, String failureCode, Instant nextRetryAt, Instant failedAt) {
    return fenced(
        lease,
        failedAt,
        false,
        parameters -> {
          jdbc.update(
              """
              update public.account_deletion_steps set failure_code = :failureCode
              where request_id = :requestId and attempt = :attempt and completed_at is null
              """,
              parameters.addValue("attempt", lease.attempt()).addValue("failureCode", failureCode));
          return jdbc.update(
                  """
                  update public.account_deletion_requests
                  set next_retry_at = :nextRetryAt, failure_code = :failureCode,
                      lease_owner = null, lease_expires_at = null
                  where id = :requestId and lease_owner = :owner and fencing_token = :fence
                    and status = 'running' and lease_expires_at > clock_timestamp()
                  """,
                  parameters.addValue("nextRetryAt", utc(nextRetryAt)))
              == 1;
        });
  }

  @Override
  public boolean fail(DeletionLease lease, String failureCode, Instant failedAt) {
    return terminal(lease, "failed", failureCode, failedAt);
  }

  private boolean terminal(
      DeletionLease lease, String status, String failureCode, Instant completedAt) {
    return fenced(
        lease,
        completedAt,
        false,
        parameters ->
            jdbc.update(
                    """
                    update public.account_deletion_requests
                    set status = :status, failure_code = :failureCode, completed_at = :completedAt,
                        next_retry_at = null, lease_owner = null, lease_expires_at = null
                    where id = :requestId and lease_owner = :owner and fencing_token = :fence
                      and status = 'running' and lease_expires_at > clock_timestamp()
                    """,
                    parameters.addValue("status", status).addValue("failureCode", failureCode))
                == 1);
  }

  private boolean fenced(
      DeletionLease lease, Instant at, boolean rejectCancellation, FencedMutation mutation) {
    try {
      return Boolean.TRUE.equals(
          transaction.execute(
              ignored -> {
                MapSqlParameterSource parameters =
                    leaseParameters(lease)
                        .addValue("at", utc(at))
                        .addValue("startedAt", utc(at))
                        .addValue("completedAt", utc(at))
                        .addValue("failedAt", utc(at));
                String lockSql =
                    """
                    select id from public.account_deletion_requests
                    where id = :requestId and lease_owner = :owner and fencing_token = :fence
                      and status = 'running' and lease_expires_at > clock_timestamp()
                    """
                        + (rejectCancellation ? " and cancellation_requested = false\n" : "")
                        + " for update";
                String locked;
                try {
                  locked = jdbc.queryForObject(lockSql, parameters, String.class);
                } catch (EmptyResultDataAccessException ignoredMissing) {
                  locked = null;
                }
                if (locked == null || !mutation.apply(parameters)) {
                  throw StaleLease.INSTANCE;
                }
                return true;
              }));
    } catch (StaleLease ignored) {
      return false;
    }
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

  private static OffsetDateTime utc(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requireOne(int count) {
    if (count != 1) {
      throw StaleLease.INSTANCE;
    }
  }

  private WorkRow mapWorkRow(ResultSet resultSet, int rowNumber) throws SQLException {
    String rawStep = resultSet.getString("step");
    return new WorkRow(
        resultSet.getString("id"),
        resultSet.getBoolean("cancellation_requested"),
        resultSet.getBoolean("destructive_step_started"),
        resultSet.getString("auth_subject_ciphertext"),
        resultSet.getString("auth_subject_key_version"),
        rawStep == null ? null : DeletionStep.valueOf(rawStep));
  }

  private record WorkRow(
      String requestId,
      boolean cancellationRequested,
      boolean destructiveStepStarted,
      String ciphertext,
      String keyVersion,
      DeletionStep step) {}

  @FunctionalInterface
  private interface FencedMutation {
    boolean apply(MapSqlParameterSource parameters);
  }

  private static final class StaleLease extends RuntimeException {
    private static final StaleLease INSTANCE = new StaleLease();

    private StaleLease() {
      super(null, null, false, false);
    }
  }
}
