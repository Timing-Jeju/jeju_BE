package com.timingjeju.api.global.importing;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleError;
import com.timingjeju.api.application.importing.ImportRunLifecycleException;
import com.timingjeju.api.application.importing.ImportRunMutationOutcome;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.importing.ImportRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcImportRunStore implements ImportRunStore {

  private static final int MAX_COUNT = Integer.MAX_VALUE;

  private final JdbcTemplate jdbcTemplate;

  public JdbcImportRunStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public ImportRunStartResult start(
      ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    Objects.requireNonNull(runId, "runId는 필수입니다.");
    Objects.requireNonNull(ownerToken, "ownerToken은 필수입니다.");
    Objects.requireNonNull(startedAt, "startedAt은 필수입니다.");
    Optional<StoredStart> existing = findIdempotent(command);
    if (existing.isPresent()) {
      return replay(command, existing.orElseThrow());
    }
    if (hasRunningScope(command)) {
      throw ImportRunLifecycleException.of(ImportRunLifecycleError.SCOPE_ALREADY_RUNNING);
    }
    try {
      Optional<ImportRunLease> inserted = insert(command, runId, ownerToken, startedAt);
      if (inserted.isPresent()) {
        return new ImportRunStartResult(
            inserted.orElseThrow(),
            false,
            ImportRunExecutionStatus.RUNNING,
            ImportRunCounts.zero());
      }

      Optional<StoredStart> replay = findIdempotent(command);
      if (replay.isPresent()) {
        return replay(command, replay.orElseThrow());
      }
      if (command.parentRunId().isPresent() && !hasValidParent(command)) {
        throw ImportRunLifecycleException.of(ImportRunLifecycleError.INVALID_PARENT);
      }
      if (hasRunningScope(command)) {
        throw ImportRunLifecycleException.of(ImportRunLifecycleError.SCOPE_ALREADY_RUNNING);
      }
      throw ImportRunLifecycleException.of(ImportRunLifecycleError.INVALID_REQUEST);
    } catch (DataIntegrityViolationException failure) {
      if (hasSqlState(failure, "23505")) {
        throw ImportRunLifecycleException.of(ImportRunLifecycleError.SCOPE_ALREADY_RUNNING);
      }
      throw ImportRunLifecycleException.of(ImportRunLifecycleError.INVALID_REQUEST);
    }
  }

  private static ImportRunStartResult replay(ImportRunStartCommand command, StoredStart stored) {
    if (!stored.matches(command)) {
      throw ImportRunLifecycleException.of(ImportRunLifecycleError.INVALID_REQUEST);
    }
    return new ImportRunStartResult(stored.lease(), true, stored.status(), stored.counts());
  }

  private static boolean hasSqlState(Throwable failure, String expectedSqlState) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && expectedSqlState.equals(sqlException.getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private Optional<ImportRunLease> insert(
      ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
    String prefix =
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, parent_run_id, retry_count, source_provider, source_service,
          owner_token, fencing_token
        )
        """;
    String suffix =
        """
        on conflict do nothing
        returning id, owner_token, fencing_token
        """;
    Object[] common = commonParameters(command, runId, ownerToken, startedAt);
    List<ImportRunLease> rows;
    if (command.parentRunId().isEmpty()) {
      rows =
          jdbcTemplate.query(
              prefix
                  + " values (?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?, ?, ?, null, 0, ?, ?, ?, 1) "
                  + suffix,
              (resultSet, rowNumber) -> lease(resultSet),
              common);
    } else {
      UUID parentRunId = command.parentRunId().orElseThrow();
      Object[] parameters = java.util.Arrays.copyOf(common, common.length + 6);
      parameters[common.length] = parentRunId;
      parameters[common.length + 1] = command.scope().provider();
      parameters[common.length + 2] = command.scope().service();
      parameters[common.length + 3] = command.scope().operation();
      parameters[common.length + 4] = command.scope().scopeKey();
      parameters[common.length + 5] = MAX_COUNT;
      rows =
          jdbcTemplate.query(
              prefix
                  + """
                   select ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?, ?, ?, ?, parent.id,
                          parent.retry_count + 1, ?, ?, ?, 1
                   from public.data_import_runs parent
                   where parent.id = ?
                     and parent.source_provider = ? and parent.source_service = ?
                     and parent.source_operation = ? and parent.scope_key = ?
                     and parent.status <> 'running' and parent.retry_count < ?
                  """
                  + suffix,
              (resultSet, rowNumber) -> lease(resultSet),
              parameters);
    }
    return single(rows);
  }

  private static Object[] commonParameters(
      ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
    return new Object[] {
      runId,
      command.sourceKind().databaseValue(),
      command.sourceName(),
      command.scope().operation(),
      command.dataVersion(),
      Timestamp.from(startedAt),
      command.parserVersion(),
      command.schemaVersion(),
      command.syncMode().databaseValue(),
      command.scope().scopeKey(),
      command.requestFingerprint(),
      command.idempotencyKey(),
      command.scope().provider(),
      command.scope().service(),
      ownerToken
    };
  }

  @Override
  public ImportRunMutationOutcome addCounts(ImportRunLease lease, ImportRunCounts delta) {
    int updated =
        jdbcTemplate.update(
            """
            update public.data_import_runs
            set row_count = row_count + ?, fetched_count = fetched_count + ?,
                inserted_count = inserted_count + ?, updated_count = updated_count + ?,
                skipped_count = skipped_count + ?, rejected_count = rejected_count + ?,
                deleted_count = deleted_count + ?, staled_count = staled_count + ?
            where id = ? and owner_token = ? and fencing_token = ? and status = 'running'
              and row_count <= ? - ? and fetched_count <= ? - ?
              and inserted_count <= ? - ? and updated_count <= ? - ?
              and skipped_count <= ? - ? and rejected_count <= ? - ?
              and deleted_count <= ? - ? and staled_count <= ? - ?
            """,
            mutationParameters(lease, delta));
    return updated == 1 ? ImportRunMutationOutcome.UPDATED : diagnose(lease);
  }

  @Override
  public ImportRunMutationOutcome finish(
      ImportRunLease lease,
      ImportRunStatus status,
      ImportRunCounts delta,
      ImportRunFailure failure,
      Instant finishedAt) {
    String errorCode = failure == null ? null : failure.code();
    String errorDetail = failure == null ? null : failure.detail();
    Object[] mutation = mutationParameters(lease, delta);
    Object[] parameters = new Object[mutation.length + 4];
    parameters[0] = status.databaseValue();
    parameters[1] = Timestamp.from(finishedAt);
    parameters[2] = errorCode;
    parameters[3] = errorDetail;
    System.arraycopy(mutation, 0, parameters, 4, mutation.length);
    int updated =
        jdbcTemplate.update(
            """
            update public.data_import_runs
            set status = ?, finished_at = ?, error_code = ?, error_message = ?,
                row_count = row_count + ?, fetched_count = fetched_count + ?,
                inserted_count = inserted_count + ?, updated_count = updated_count + ?,
                skipped_count = skipped_count + ?, rejected_count = rejected_count + ?,
                deleted_count = deleted_count + ?, staled_count = staled_count + ?
            where id = ? and owner_token = ? and fencing_token = ? and status = 'running'
              and row_count <= ? - ? and fetched_count <= ? - ?
              and inserted_count <= ? - ? and updated_count <= ? - ?
              and skipped_count <= ? - ? and rejected_count <= ? - ?
              and deleted_count <= ? - ? and staled_count <= ? - ?
            """,
            parameters);
    return updated == 1 ? ImportRunMutationOutcome.UPDATED : diagnose(lease);
  }

  private static Object[] mutationParameters(ImportRunLease lease, ImportRunCounts delta) {
    return new Object[] {
      delta.rowCount(),
      delta.fetchedCount(),
      delta.insertedCount(),
      delta.updatedCount(),
      delta.skippedCount(),
      delta.rejectedCount(),
      delta.deletedCount(),
      delta.staledCount(),
      lease.runId(),
      lease.ownerToken(),
      lease.fencingToken(),
      MAX_COUNT,
      delta.rowCount(),
      MAX_COUNT,
      delta.fetchedCount(),
      MAX_COUNT,
      delta.insertedCount(),
      MAX_COUNT,
      delta.updatedCount(),
      MAX_COUNT,
      delta.skippedCount(),
      MAX_COUNT,
      delta.rejectedCount(),
      MAX_COUNT,
      delta.deletedCount(),
      MAX_COUNT,
      delta.staledCount()
    };
  }

  private ImportRunMutationOutcome diagnose(ImportRunLease lease) {
    List<StoredState> states =
        jdbcTemplate.query(
            "select owner_token, fencing_token, status from public.data_import_runs where id = ?",
            (resultSet, rowNumber) ->
                new StoredState(
                    resultSet.getObject("owner_token", UUID.class),
                    resultSet.getLong("fencing_token"),
                    resultSet.getString("status")),
            lease.runId());
    if (states.isEmpty()) {
      return ImportRunMutationOutcome.NOT_FOUND;
    }
    StoredState state = states.getFirst();
    if (!state.ownerToken.equals(lease.ownerToken())
        || state.fencingToken != lease.fencingToken()) {
      return ImportRunMutationOutcome.OWNERSHIP_LOST;
    }
    if (!"running".equals(state.status)) {
      return ImportRunMutationOutcome.INVALID_TRANSITION;
    }
    return ImportRunMutationOutcome.COUNT_OVERFLOW;
  }

  private Optional<StoredStart> findIdempotent(ImportRunStartCommand command) {
    List<StoredStart> rows =
        jdbcTemplate.query(
            """
            select id, owner_token, fencing_token, request_fingerprint, parent_run_id,
                   parser_version, schema_version, data_version, sync_mode, source_kind, source_name,
                   status, row_count, fetched_count, inserted_count, updated_count, skipped_count,
                   rejected_count, deleted_count, staled_count
            from public.data_import_runs
            where source_provider = ? and source_service = ? and source_operation = ?
              and scope_key = ? and idempotency_key = ? and idempotency_enforced
            """,
            (resultSet, rowNumber) -> storedStart(resultSet),
            command.scope().provider(),
            command.scope().service(),
            command.scope().operation(),
            command.scope().scopeKey(),
            command.idempotencyKey());
    return single(rows);
  }

  private boolean hasValidParent(ImportRunStartCommand command) {
    return jdbcTemplate
        .queryForObject(
            """
            select exists(
              select 1 from public.data_import_runs
              where id = ? and source_provider = ? and source_service = ?
                and source_operation = ? and scope_key = ? and status <> 'running'
                and retry_count < ?
            )
            """,
            Boolean.class,
            command.parentRunId().orElseThrow(),
            command.scope().provider(),
            command.scope().service(),
            command.scope().operation(),
            command.scope().scopeKey(),
            MAX_COUNT)
        .booleanValue();
  }

  private boolean hasRunningScope(ImportRunStartCommand command) {
    return jdbcTemplate
        .queryForObject(
            """
            select exists(
              select 1 from public.data_import_runs
              where source_provider = ? and source_service = ? and source_operation = ?
                and scope_key = ? and status = 'running'
            )
            """,
            Boolean.class,
            command.scope().provider(),
            command.scope().service(),
            command.scope().operation(),
            command.scope().scopeKey())
        .booleanValue();
  }

  private static ImportRunLease lease(ResultSet resultSet) throws SQLException {
    return new ImportRunLease(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("owner_token", UUID.class),
        resultSet.getLong("fencing_token"));
  }

  private static StoredStart storedStart(ResultSet resultSet) throws SQLException {
    return new StoredStart(
        lease(resultSet),
        resultSet.getString("request_fingerprint"),
        resultSet.getObject("parent_run_id", UUID.class),
        resultSet.getString("parser_version"),
        resultSet.getString("schema_version"),
        resultSet.getString("data_version"),
        resultSet.getString("sync_mode"),
        resultSet.getString("source_kind"),
        resultSet.getString("source_name"),
        ImportRunExecutionStatus.fromDatabaseValue(resultSet.getString("status")),
        new ImportRunCounts(
            resultSet.getInt("row_count"),
            resultSet.getInt("fetched_count"),
            resultSet.getInt("inserted_count"),
            resultSet.getInt("updated_count"),
            resultSet.getInt("skipped_count"),
            resultSet.getInt("rejected_count"),
            resultSet.getInt("deleted_count"),
            resultSet.getInt("staled_count")));
  }

  private static <T> Optional<T> single(List<T> rows) {
    if (rows.size() > 1) {
      throw ImportRunLifecycleException.of(ImportRunLifecycleError.INVALID_REQUEST);
    }
    return rows.stream().findFirst();
  }

  private record StoredState(UUID ownerToken, long fencingToken, String status) {}

  private record StoredStart(
      ImportRunLease lease,
      String requestFingerprint,
      UUID parentRunId,
      String parserVersion,
      String schemaVersion,
      String dataVersion,
      String syncMode,
      String sourceKind,
      String sourceName,
      ImportRunExecutionStatus status,
      ImportRunCounts counts) {
    private boolean matches(ImportRunStartCommand command) {
      return Objects.equals(requestFingerprint, command.requestFingerprint())
          && Objects.equals(parentRunId, command.rawParentRunId())
          && Objects.equals(parserVersion, command.parserVersion())
          && Objects.equals(schemaVersion, command.schemaVersion())
          && Objects.equals(dataVersion, command.dataVersion())
          && Objects.equals(syncMode, command.syncMode().databaseValue())
          && Objects.equals(sourceKind, command.sourceKind().databaseValue())
          && Objects.equals(sourceName, command.sourceName());
    }
  }
}
