package com.timingjeju.api.global.importing;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
import com.timingjeju.api.application.importing.ImportCheckpointRepository;
import com.timingjeju.api.application.importing.ImportRunScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcImportCheckpointRepository implements ImportCheckpointRepository {

  private static final TypeReference<Map<String, Object>> CHECKPOINT_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcImportCheckpointRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<ImportCheckpoint> find(ImportRunScope scope) {
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    try {
      List<ImportCheckpoint> rows =
          jdbcTemplate.query(
              """
              select source_provider, source_service, source_operation, scope_key,
                     checkpoint::text, source_watermark_at, last_succeeded_run_id,
                     version, updated_at
              from public.data_import_checkpoints
              where source_provider = ? and source_service = ?
                and source_operation = ? and scope_key = ?
              """,
              (resultSet, rowNumber) -> map(resultSet),
              scope.provider(),
              scope.service(),
              scope.operation(),
              scope.scopeKey());
      return rows.stream().findFirst();
    } catch (ImportCheckpointException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw ImportCheckpointException.of(ImportCheckpointError.STORAGE_FAILURE);
    }
  }

  @Override
  public ImportCheckpoint advance(ImportCheckpointAdvanceCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    ImportRunScope scope = command.scope();
    try {
      String checkpoint = writeCheckpoint(command.checkpoint());
      return jdbcTemplate.queryForObject(
          """
          select source_provider, source_service, source_operation, scope_key,
                 checkpoint::text, source_watermark_at, last_succeeded_run_id,
                 version, updated_at
          from public.advance_data_import_checkpoint(?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """,
          (resultSet, rowNumber) -> map(resultSet),
          scope.provider(),
          scope.service(),
          scope.operation(),
          scope.scopeKey(),
          command.expectedVersion(),
          checkpoint,
          timestamp(command.sourceWatermarkAt()),
          command.lastSucceededRunId());
    } catch (ImportCheckpointException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw classify(failure);
    }
  }

  private ImportCheckpoint map(ResultSet resultSet) throws SQLException {
    return new ImportCheckpoint(
        new ImportRunScope(
            resultSet.getString("source_provider"),
            resultSet.getString("source_service"),
            resultSet.getString("source_operation"),
            resultSet.getString("scope_key")),
        readCheckpoint(resultSet.getString("checkpoint")),
        instant(resultSet.getTimestamp("source_watermark_at")),
        resultSet.getObject("last_succeeded_run_id", UUID.class),
        resultSet.getLong("version"),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private String writeCheckpoint(Map<String, Object> checkpoint) {
    try {
      return objectMapper.writeValueAsString(checkpoint);
    } catch (RuntimeException failure) {
      throw ImportCheckpointException.of(ImportCheckpointError.INVALID_ADVANCE);
    }
  }

  private Map<String, Object> readCheckpoint(String checkpoint) {
    try {
      return objectMapper.readValue(checkpoint, CHECKPOINT_TYPE);
    } catch (RuntimeException failure) {
      throw ImportCheckpointException.of(ImportCheckpointError.STORAGE_FAILURE);
    }
  }

  private static ImportCheckpointException classify(DataAccessException failure) {
    if (hasSqlState(failure, "40001")) {
      return ImportCheckpointException.of(ImportCheckpointError.STALE_VERSION);
    }
    if (hasSqlState(failure, "23514") || hasSqlState(failure, "23503")) {
      return ImportCheckpointException.of(ImportCheckpointError.INVALID_ADVANCE);
    }
    return ImportCheckpointException.of(ImportCheckpointError.STORAGE_FAILURE);
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

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
