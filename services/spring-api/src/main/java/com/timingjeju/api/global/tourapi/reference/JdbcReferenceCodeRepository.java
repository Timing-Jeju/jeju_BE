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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
      for (ReferenceCode code : command.codes()) {
        StoredCode existing = find(code, command.validFrom());
        UUID rowId = existing == null ? deterministicId(code, command.validFrom()) : existing.id();
        boolean unchanged = existing != null && existing.sameValue(code, command);
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
              if (!unchanged) {
                write(rowId, code, command);
              }
            });
        if (existing == null) {
          inserted++;
        } else if (unchanged) {
          skipped++;
        } else {
          updated++;
        }
      }
      return new ReferenceCodeUpsertResult(inserted, updated, skipped);
    } catch (DataAccessException | TourApiProvenanceException failure) {
      throw ReferenceCodeSyncException.storageFailure();
    }
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

  private void write(UUID id, ReferenceCode code, ReferenceCodeUpsertCommand command) {
    jdbcTemplate.update(
        """
        insert into public.external_reference_codes (
          id, source_provider, source_service, code_type, external_code,
          parent_external_code, code_name, code_path, attributes, valid_from, valid_to,
          source_snapshot_id, import_run_id, last_seen_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
        on conflict (source_provider, source_service, code_type, external_code, valid_from)
        do update set
          parent_external_code=excluded.parent_external_code,
          code_name=excluded.code_name,
          code_path=excluded.code_path,
          attributes=excluded.attributes,
          valid_to=excluded.valid_to,
          source_snapshot_id=excluded.source_snapshot_id,
          import_run_id=excluded.import_run_id,
          last_seen_at=excluded.last_seen_at
        """,
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
    String naturalKey =
        PROVIDER
            + '\u001f'
            + SERVICE
            + '\u001f'
            + code.codeType()
            + '\u001f'
            + code.externalCode()
            + '\u001f'
            + validFrom;
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
