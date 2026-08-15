package com.timingjeju.api.global.snapshot;

import com.timingjeju.api.application.snapshot.SnapshotMutationOutcome;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStateMutation;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStore;
import com.timingjeju.api.application.snapshot.SnapshotStoreError;
import com.timingjeju.api.application.snapshot.SnapshotStoreException;
import com.timingjeju.api.application.snapshot.StoredSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSnapshotStore implements SnapshotStore {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcSnapshotStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public SnapshotSaveResult save(StoredSnapshot snapshot) {
    if (!hasMatchingRunScope(snapshot)) {
      throw SnapshotStoreException.of(SnapshotStoreError.SCOPE_MISMATCH);
    }
    try {
      List<UUID> inserted =
          jdbcTemplate.query(
              """
              insert into public.external_api_snapshots (
                id, import_run_id, source_provider, source_service, source_operation, scope_key,
                external_record_id, request_hash, page_key, http_status, provider_result_code,
                fetched_at, source_modified_at, expires_at, parser_version, payload_hash, payload_format,
                initial_parse_status, initial_error_code,
                parse_status, error_code, error_message, request_metadata_redacted, raw_payload,
                payload_size_bytes, redaction_version, purge_after
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
              on conflict (import_run_id, source_operation, request_hash, page_key, payload_hash)
              do nothing
              returning id
              """,
              (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
              parameters(snapshot));
      if (!inserted.isEmpty()) {
        return snapshot.result(false);
      }
      StoredSnapshot existing = findDuplicate(snapshot);
      if (!sameAuditPayload(existing, snapshot)) {
        throw SnapshotStoreException.of(SnapshotStoreError.HASH_COLLISION);
      }
      return existing.result(true);
    } catch (DataIntegrityViolationException failure) {
      throw SnapshotStoreException.of(SnapshotStoreError.INVALID_REQUEST);
    }
  }

  private boolean hasMatchingRunScope(StoredSnapshot snapshot) {
    SnapshotScope scope = snapshot.scope();
    return jdbcTemplate
        .queryForObject(
            """
            select exists(
              select 1 from public.data_import_runs
              where id=? and source_provider=? and source_service=?
                and source_operation=? and scope_key=?
            )
            """,
            Boolean.class,
            snapshot.importRunId(),
            scope.provider(),
            scope.service(),
            scope.operation(),
            scope.scopeKey())
        .booleanValue();
  }

  private static Object[] parameters(StoredSnapshot snapshot) {
    SnapshotScope scope = snapshot.scope();
    return new Object[] {
      snapshot.snapshotId(),
      snapshot.importRunId(),
      scope.provider(),
      scope.service(),
      scope.operation(),
      scope.scopeKey(),
      snapshot.externalRecordId(),
      snapshot.requestHash(),
      snapshot.pageKey(),
      snapshot.httpStatus(),
      snapshot.providerResultCode(),
      Timestamp.from(snapshot.fetchedAt()),
      timestamp(snapshot.sourceModifiedAt()),
      timestamp(snapshot.expiresAt()),
      snapshot.parserVersion(),
      snapshot.payloadHash(),
      snapshot.payloadFormat().name(),
      snapshot.initialStatus().databaseValue(),
      snapshot.initialErrorCode(),
      snapshot.status().databaseValue(),
      snapshot.errorCode(),
      snapshot.errorMessage(),
      snapshot.requestMetadataRedactedJson(),
      snapshot.rawPayloadJson(),
      snapshot.payloadSizeBytes(),
      snapshot.redactionVersion(),
      Timestamp.from(snapshot.purgeAfter())
    };
  }

  @Override
  public SnapshotMutationOutcome transition(SnapshotStateMutation mutation) {
    int updated =
        jdbcTemplate.update(
            """
            update public.external_api_snapshots
            set parse_status=?, parsed_at=?, error_code=?, error_message=?, purge_after=?
            where id=? and parse_status='received'
            """,
            mutation.status().databaseValue(),
            mutation.status() == SnapshotStatus.PARSED
                ? Timestamp.from(mutation.transitionedAt())
                : null,
            mutation.errorCode(),
            mutation.errorMessage(),
            Timestamp.from(mutation.transitionedAt().plus(mutation.retention())),
            mutation.snapshotId());
    if (updated == 1) {
      return SnapshotMutationOutcome.UPDATED;
    }
    String status =
        jdbcTemplate.query(
            "select parse_status from public.external_api_snapshots where id=?",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            mutation.snapshotId());
    if (status == null) return SnapshotMutationOutcome.NOT_FOUND;
    return status.equals(mutation.status().databaseValue())
        ? SnapshotMutationOutcome.ALREADY_AT_TARGET
        : SnapshotMutationOutcome.INVALID_TRANSITION;
  }

  StoredSnapshot findForTest(UUID snapshotId) {
    List<StoredSnapshot> rows =
        jdbcTemplate.query(
            "select * from public.external_api_snapshots where id=?",
            (resultSet, rowNumber) -> map(resultSet),
            snapshotId);
    if (rows.isEmpty()) {
      throw SnapshotStoreException.of(SnapshotStoreError.NOT_FOUND);
    }
    return rows.getFirst();
  }

  private StoredSnapshot findDuplicate(StoredSnapshot snapshot) {
    List<StoredSnapshot> rows =
        jdbcTemplate.query(
            """
            select * from public.external_api_snapshots
            where import_run_id=? and source_operation=? and request_hash=?
              and page_key=? and payload_hash=?
            """,
            (resultSet, rowNumber) -> map(resultSet),
            snapshot.importRunId(),
            snapshot.scope().operation(),
            snapshot.requestHash(),
            snapshot.pageKey(),
            snapshot.payloadHash());
    if (rows.size() != 1) {
      throw SnapshotStoreException.of(SnapshotStoreError.INVALID_REQUEST);
    }
    return rows.getFirst();
  }

  private static StoredSnapshot map(ResultSet resultSet) throws SQLException {
    return new StoredSnapshot(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("import_run_id", UUID.class),
        new SnapshotScope(
            resultSet.getString("source_provider"),
            resultSet.getString("source_service"),
            resultSet.getString("source_operation"),
            resultSet.getString("scope_key")),
        resultSet.getString("external_record_id"),
        resultSet.getString("request_hash"),
        resultSet.getString("page_key"),
        (Integer) resultSet.getObject("http_status"),
        resultSet.getString("provider_result_code"),
        resultSet.getTimestamp("fetched_at").toInstant(),
        instant(resultSet.getTimestamp("source_modified_at")),
        instant(resultSet.getTimestamp("expires_at")),
        resultSet.getString("parser_version"),
        resultSet.getString("payload_hash"),
        SnapshotPayloadFormat.valueOf(resultSet.getString("payload_format")),
        SnapshotStatus.valueOf(
            resultSet.getString("initial_parse_status").toUpperCase(java.util.Locale.ROOT)),
        resultSet.getString("initial_error_code"),
        SnapshotStatus.valueOf(
            resultSet.getString("parse_status").toUpperCase(java.util.Locale.ROOT)),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getString("request_metadata_redacted"),
        resultSet.getString("raw_payload"),
        resultSet.getLong("payload_size_bytes"),
        resultSet.getString("redaction_version"),
        resultSet.getTimestamp("purge_after").toInstant());
  }

  private boolean sameAuditPayload(StoredSnapshot first, StoredSnapshot second) {
    return java.util.Objects.equals(first.parserVersion(), second.parserVersion())
        && first.payloadFormat() == second.payloadFormat()
        && first.initialStatus() == second.initialStatus()
        && java.util.Objects.equals(first.initialErrorCode(), second.initialErrorCode())
        && sameJson(first.requestMetadataRedactedJson(), second.requestMetadataRedactedJson())
        && sameJson(first.rawPayloadJson(), second.rawPayloadJson())
        && first.payloadSizeBytes() == second.payloadSizeBytes()
        && java.util.Objects.equals(first.redactionVersion(), second.redactionVersion());
  }

  private boolean sameJson(String first, String second) {
    if (first == null || second == null) {
      return first == null && second == null;
    }
    try {
      return objectMapper.readTree(first).equals(objectMapper.readTree(second));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static Timestamp timestamp(java.time.Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static java.time.Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
