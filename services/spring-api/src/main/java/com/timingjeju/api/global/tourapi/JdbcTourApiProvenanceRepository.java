package com.timingjeju.api.global.tourapi;

import com.timingjeju.api.application.tourapi.TourApiProvenance;
import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceReader;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTourApiProvenanceRepository
    implements TourApiProvenanceWriter, TourApiProvenanceReader {

  private final JdbcTemplate jdbcTemplate;

  public JdbcTourApiProvenanceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  @Transactional
  public TourApiProvenance write(TourApiProvenanceCommand command, Runnable normalizedWrite) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    Objects.requireNonNull(normalizedWrite, "normalizedWrite는 필수입니다.");
    try {
      normalizedWrite.run();
      List<TourApiProvenance> inserted =
          jdbcTemplate.query(
              """
              insert into public.tour_api_operation_provenance (
                normalized_entity_type, normalized_row_id, operation_key, content_type_id,
                request_fingerprint, source_snapshot_id, import_run_id
              ) values (?, ?, ?, ?, ?, ?, ?)
              on conflict (normalized_entity_type, normalized_row_id, operation_key, source_snapshot_id)
              do nothing
              returning *
              """,
              (resultSet, rowNumber) -> map(resultSet),
              command.normalizedEntityType(),
              command.normalizedRowId(),
              command.operationKey(),
              command.contentTypeId(),
              command.requestFingerprint(),
              command.sourceSnapshotId(),
              command.importRunId());
      return inserted.isEmpty() ? findDuplicate(command) : inserted.getFirst();
    } catch (DataIntegrityViolationException failure) {
      throw new TourApiProvenanceException();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<TourApiProvenance> findByNormalizedRow(
      String normalizedEntityType, UUID normalizedRowId) {
    Objects.requireNonNull(normalizedEntityType, "normalizedEntityType은 필수입니다.");
    Objects.requireNonNull(normalizedRowId, "normalizedRowId는 필수입니다.");
    return jdbcTemplate.query(
        """
        select * from public.tour_api_operation_provenance
        where normalized_entity_type=? and normalized_row_id=?
        order by operation_key, created_at, id
        """,
        (resultSet, rowNumber) -> map(resultSet),
        normalizedEntityType,
        normalizedRowId);
  }

  private TourApiProvenance findDuplicate(TourApiProvenanceCommand command) {
    List<TourApiProvenance> rows =
        jdbcTemplate.query(
            """
            select * from public.tour_api_operation_provenance
            where normalized_entity_type=? and normalized_row_id=?
              and operation_key=? and source_snapshot_id=?
            """,
            (resultSet, rowNumber) -> map(resultSet),
            command.normalizedEntityType(),
            command.normalizedRowId(),
            command.operationKey(),
            command.sourceSnapshotId());
    if (rows.size() != 1 || !sameIdentity(rows.getFirst(), command)) {
      throw new TourApiProvenanceException();
    }
    return rows.getFirst();
  }

  private static boolean sameIdentity(
      TourApiProvenance existing, TourApiProvenanceCommand command) {
    return Objects.equals(existing.contentTypeId(), command.contentTypeId())
        && existing.requestFingerprint().equals(command.requestFingerprint())
        && existing.importRunId().equals(command.importRunId());
  }

  private static TourApiProvenance map(ResultSet resultSet) throws SQLException {
    return new TourApiProvenance(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("normalized_entity_type"),
        resultSet.getObject("normalized_row_id", UUID.class),
        resultSet.getString("operation_key"),
        resultSet.getString("content_type_id"),
        resultSet.getString("request_fingerprint"),
        resultSet.getObject("source_snapshot_id", UUID.class),
        resultSet.getObject("import_run_id", UUID.class),
        resultSet.getTimestamp("created_at").toInstant());
  }
}
