package com.timingjeju.api.global.tourapi.reference;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.reference.ReferenceCode;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeLineage;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeRepository;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncException;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeUpsertCommand;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeUpsertResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcReferenceCodeRepository implements ReferenceCodeRepository {

  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final TourApiProvenanceWriter provenanceWriter;

  public JdbcReferenceCodeRepository(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      TourApiProvenanceWriter provenanceWriter) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    this.provenanceWriter = Objects.requireNonNull(provenanceWriter, "provenanceWriter는 필수입니다.");
  }

  @Override
  @Transactional
  public ReferenceCodeUpsertResult upsert(ReferenceCodeUpsertCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    try {
      List<ReferenceCode> orderedCodes =
          command.codes().stream()
              .sorted(
                  Comparator.comparing(ReferenceCode::codeType)
                      .thenComparing(ReferenceCode::externalCode))
              .toList();
      for (ReferenceCode code : orderedCodes) {
        lockNaturalKey(code, command.validFrom());
        StoredCode existing = find(code, command.validFrom());
        UUID rowId = existing == null ? deterministicId(code, command.validFrom()) : existing.id();
        AtomicReference<WriteOutcome> outcome = new AtomicReference<>();
        ReferenceCodeLineage lineage = command.lineage();
        provenanceWriter.write(
            new TourApiProvenanceCommand(
                "external_reference_codes",
                rowId,
                lineage.operationKey(),
                null,
                lineage.requestFingerprint(),
                lineage.snapshotId(),
                lineage.importRunId()),
            () -> {
              outcome.set(writeActual(existing, rowId, code, command));
            });
        switch (outcome.get()) {
          case INSERTED -> inserted++;
          case UPDATED -> updated++;
          case SKIPPED -> skipped++;
        }
      }
      return new ReferenceCodeUpsertResult(inserted, updated, skipped);
    } catch (DataAccessException | TourApiProvenanceException failure) {
      throw ReferenceCodeSyncException.storageFailure();
    }
  }

  private void lockNaturalKey(ReferenceCode code, LocalDate validFrom) {
    jdbcTemplate.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        naturalKey(code, validFrom));
  }

  private WriteOutcome writeActual(
      StoredCode existing, UUID rowId, ReferenceCode code, ReferenceCodeUpsertCommand command) {
    if (existing != null) {
      if (existing.sameValue(code, command)) {
        return WriteOutcome.SKIPPED;
      }
      update(rowId, code, command);
      return WriteOutcome.UPDATED;
    }
    if (insert(rowId, code, command)) {
      return WriteOutcome.INSERTED;
    }
    StoredCode raced = find(code, command.validFrom());
    if (raced == null) {
      throw ReferenceCodeSyncException.storageFailure();
    }
    if (raced.sameValue(code, command)) {
      return WriteOutcome.SKIPPED;
    }
    update(raced.id(), code, command);
    return WriteOutcome.UPDATED;
  }

  private StoredCode find(ReferenceCode code, LocalDate validFrom) {
    List<StoredCode> rows =
        jdbcTemplate.query(
            """
            select id, parent_external_code, code_name, code_path, attributes::text,
                   valid_to, source_snapshot_id, import_run_id
            from public.external_reference_codes
            where source_provider=? and source_service=? and code_type=?
              and external_code=? and valid_from=?
            for update
            """,
            (resultSet, rowNumber) -> map(resultSet),
            PROVIDER,
            SERVICE,
            code.codeType(),
            code.externalCode(),
            validFrom);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private boolean insert(UUID id, ReferenceCode code, ReferenceCodeUpsertCommand command) {
    List<UUID> inserted =
        jdbcTemplate.query(
            """
            insert into public.external_reference_codes (
              id, source_provider, source_service, code_type, external_code,
              parent_external_code, code_name, code_path, attributes, valid_from, valid_to,
              source_snapshot_id, import_run_id, last_seen_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            on conflict (source_provider, source_service, code_type, external_code, valid_from)
            do nothing
            returning id
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            id,
            PROVIDER,
            SERVICE,
            code.codeType(),
            code.externalCode(),
            code.parentExternalCode(),
            code.name(),
            code.path(),
            json(code),
            command.validFrom(),
            command.validTo(),
            command.lineage().snapshotId(),
            command.lineage().importRunId(),
            Timestamp.from(command.seenAt()));
    return !inserted.isEmpty();
  }

  private void update(UUID id, ReferenceCode code, ReferenceCodeUpsertCommand command) {
    int updated =
        jdbcTemplate.update(
            """
        update public.external_reference_codes
        set parent_external_code=?, code_name=?, code_path=?, attributes=?::jsonb,
            valid_to=?, source_snapshot_id=?, import_run_id=?, last_seen_at=?
        where id=?
        """,
            code.parentExternalCode(),
            code.name(),
            code.path(),
            json(code),
            command.validTo(),
            command.lineage().snapshotId(),
            command.lineage().importRunId(),
            Timestamp.from(command.seenAt()),
            id);
    if (updated != 1) {
      throw ReferenceCodeSyncException.storageFailure();
    }
  }

  private String json(ReferenceCode code) {
    try {
      return objectMapper.writeValueAsString(code.attributes());
    } catch (JacksonException ignored) {
      throw ReferenceCodeSyncException.storageFailure();
    }
  }

  private StoredCode map(ResultSet resultSet) throws SQLException {
    return new StoredCode(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("parent_external_code"),
        resultSet.getString("code_name"),
        resultSet.getString("code_path"),
        resultSet.getString("attributes"),
        resultSet.getObject("valid_to", LocalDate.class),
        resultSet.getObject("source_snapshot_id", UUID.class),
        resultSet.getObject("import_run_id", UUID.class));
  }

  private static UUID deterministicId(ReferenceCode code, LocalDate validFrom) {
    return uuidFromNaturalKey(naturalKey(code, validFrom));
  }

  private static String naturalKey(ReferenceCode code, LocalDate validFrom) {
    return PROVIDER
        + '\u001f'
        + SERVICE
        + '\u001f'
        + code.codeType()
        + '\u001f'
        + code.externalCode()
        + '\u001f'
        + validFrom;
  }

  private enum WriteOutcome {
    INSERTED,
    UPDATED,
    SKIPPED
  }

  private static UUID uuidFromNaturalKey(String naturalKey) {
    byte[] hash;
    try {
      hash =
          MessageDigest.getInstance("SHA-256").digest(naturalKey.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
    String hex = HexFormat.of().formatHex(hash);
    return UUID.fromString(
        hex.substring(0, 8)
            + '-'
            + hex.substring(8, 12)
            + "-5"
            + hex.substring(13, 16)
            + "-a"
            + hex.substring(17, 20)
            + '-'
            + hex.substring(20, 32));
  }

  private record StoredCode(
      UUID id,
      String parent,
      String name,
      String path,
      String attributesJson,
      LocalDate validTo,
      UUID snapshotId,
      UUID runId) {
    private boolean sameValue(ReferenceCode code, ReferenceCodeUpsertCommand command) {
      return Objects.equals(parent, code.parentExternalCode())
          && name.equals(code.name())
          && Objects.equals(path, code.path())
          && attributesJson.equals("{}")
          && Objects.equals(validTo, command.validTo())
          && snapshotId.equals(command.lineage().snapshotId())
          && runId.equals(command.lineage().importRunId());
    }
  }
}
