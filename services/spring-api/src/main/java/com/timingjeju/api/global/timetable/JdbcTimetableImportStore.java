package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.TimetableAtomicWrite;
import com.timingjeju.api.application.timetable.TimetableEntryCandidate;
import com.timingjeju.api.application.timetable.TimetableImportFingerprint;
import com.timingjeju.api.application.timetable.TimetableImportStore;
import com.timingjeju.api.application.timetable.TimetableVersionState;
import com.timingjeju.api.application.timetable.TimetableWriteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcTimetableImportStore implements TimetableImportStore {
  private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
  private static final int MAX_OMISSION_COUNT = 999_999;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcTimetableImportStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  public TimetableVersionState inspect(
      String scheduleId, java.time.LocalDate effectiveDate, String importFingerprint) {
    List<String> hashes =
        jdbc.queryForList(
            """
            select metadata->>'importFingerprint' from public.data_import_runs
            where source_provider='JEJU_PROVINCE'
              and source_service='jeju-bus-schedule-xlsx'
              and source_operation='timetable-import'
              and status='succeeded'
              and metadata->>'gscheduleId'=?
              and metadata->>'effectiveDate'=?
            order by finished_at desc, id desc limit 1
            """,
            String.class,
            scheduleId,
            effectiveDate.toString());
    if (hashes.isEmpty()) return TimetableVersionState.NEW;
    return Objects.equals(hashes.getFirst(), importFingerprint)
        ? TimetableVersionState.REPLAY
        : TimetableVersionState.CONFLICT;
  }

  @Override
  @Transactional
  public TimetableWriteResult commit(TimetableAtomicWrite write) {
    ValidatedManifest manifest = validateManifest(write);
    int omissionCount = manifest.omissionCount();
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 38))",
        Object.class,
        write.scheduleId() + '/' + write.effectiveDate());
    TimetableVersionState current =
        inspect(write.scheduleId(), write.effectiveDate(), write.importFingerprint());
    if (current == TimetableVersionState.CONFLICT) {
      throw new IllegalStateException("SAME_VERSION_CONFLICT");
    }
    if (current == TimetableVersionState.REPLAY)
      return new TimetableWriteResult(0, 0, write.entries().size(), true);

    UUID runId = UUID.randomUUID();
    UUID snapshotId = UUID.randomUUID();
    String scope = "schedule:" + write.scheduleId();
    String requestHash = sha256(scope + '/' + write.effectiveDate());
    jdbc.update(
        """
        insert into public.data_import_runs (
          id,source_kind,source_name,source_operation,data_version,status,started_at,finished_at,
          row_count,fetched_count,inserted_count,skipped_count,rejected_count,
          metadata,parser_version,schema_version,sync_mode,scope_key,
          request_fingerprint,idempotency_key,source_provider,source_service
        ) values (?, 'jeju_bis', ?, 'timetable-import', ?, 'succeeded', ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
          'jeju-timetable-xlsx-v1','timetable-v1','full',?,?,?,'JEJU_PROVINCE','jeju-bus-schedule-xlsx')
        """,
        runId,
        write.source().providerName(),
        write.effectiveDate().toString(),
        Timestamp.from(write.fetchedAt()),
        Timestamp.from(write.fetchedAt()),
        write.entries().size(),
        write.entries().size() + omissionCount,
        write.entries().size(),
        omissionCount,
        0,
        sourceMetadataJson(write),
        scope,
        requestHash,
        write.idempotencyKey());
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id,import_run_id,source_provider,source_service,source_operation,scope_key,
          external_record_id,request_hash,page_key,fetched_at,
          parser_version,payload_hash,payload_format,parse_status,parsed_at,
          request_metadata_redacted,raw_payload,payload_size_bytes,redaction_version,
          initial_parse_status
        ) values (?,?,'JEJU_PROVINCE','jeju-bus-schedule-xlsx','timetable-import',?,?,?,'',?,
          'jeju-timetable-xlsx-v1',?,'JSON','parsed',?,?::jsonb,?::jsonb,?,
          'jeju-timetable-manifest-v1','parsed')
        """,
        snapshotId,
        runId,
        scope,
        write.scheduleId(),
        requestHash,
        Timestamp.from(write.fetchedAt()),
        write.sha256(),
        Timestamp.from(write.fetchedAt()),
        json(
            Map.of(
                "effectiveDate", write.effectiveDate().toString(), "omissionCount", omissionCount)),
        write.canonicalManifest(),
        write.canonicalManifest().getBytes(StandardCharsets.UTF_8).length);
    int inserted = 0;
    for (TimetableEntryCandidate entry : write.entries()) {
      inserted +=
          jdbc.update(
              """
              insert into public.timetable_entries (
                route_id,stop_id,direction_key,service_day_type,departure_time,
                source_record_key,valid_from,source_provider,source_service,city_code,
                route_source_provider,route_city_code,import_run_id,source_snapshot_id,last_seen_at
              ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
              """,
              entry.routeId(),
              entry.stopId(),
              entry.directionKey(),
              entry.serviceDayType(),
              entry.departureTime(),
              entry.sourceRecordKey(),
              write.effectiveDate(),
              entry.sourceProvider(),
              entry.sourceService(),
              entry.routeCityCode(),
              entry.routeSourceProvider(),
              entry.routeCityCode(),
              runId,
              snapshotId,
              Timestamp.from(write.fetchedAt()));
    }
    if (inserted != write.entries().size()) {
      throw new IllegalStateException("TIMETABLE_ATOMIC_COUNT_MISMATCH");
    }
    return new TimetableWriteResult(inserted, 0, 0);
  }

  private ValidatedManifest validateManifest(TimetableAtomicWrite write) {
    byte[] bytes = write.canonicalManifest().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_MANIFEST_BYTES) {
      throw new IllegalArgumentException("TIMETABLE_MANIFEST_TOO_LARGE");
    }
    try {
      JsonNode root = objectMapper.readTree(write.canonicalManifest());
      JsonNode effectiveDate = root.path("effectiveDate");
      JsonNode omissionCount = root.path("omissionCount");
      JsonNode recordKeys = root.path("recordKeys");
      JsonNode omissions = root.path("omissions");
      if (!root.isObject()
          || !effectiveDate.isTextual()
          || !write.effectiveDate().toString().equals(effectiveDate.asString())
          || !omissionCount.isIntegralNumber()
          || !omissionCount.canConvertToInt()
          || omissionCount.intValue() < 0
          || omissionCount.intValue() > MAX_OMISSION_COUNT
          || !recordKeys.isArray()
          || !omissions.isArray()
          || omissionCount.intValue() != omissions.size()) {
        throw new IllegalArgumentException("TIMETABLE_MANIFEST_INVALID");
      }
      List<String> manifestRecordKeys = textualValues(recordKeys);
      List<String> manifestOmissions = textualValues(omissions);
      List<String> entryRecordKeys =
          write.entries().stream().map(TimetableEntryCandidate::sourceRecordKey).toList();
      String expectedFingerprint;
      try {
        expectedFingerprint =
            TimetableImportFingerprint.compute(
                write.sha256(),
                write.mappingFingerprint(),
                write.canonicalManifest(),
                manifestRecordKeys,
                manifestOmissions);
      } catch (IllegalArgumentException invalidDigest) {
        throw new IllegalArgumentException("TIMETABLE_IMPORT_FINGERPRINT_INVALID", invalidDigest);
      }
      if (!manifestRecordKeys.equals(entryRecordKeys)
          || !expectedFingerprint.equals(write.importFingerprint())) {
        throw new IllegalArgumentException("TIMETABLE_IMPORT_FINGERPRINT_INVALID");
      }
      return new ValidatedManifest(omissionCount.intValue());
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("TIMETABLE_MANIFEST_INVALID", failure);
    }
  }

  private static List<String> textualValues(JsonNode array) {
    var values = new java.util.ArrayList<String>(array.size());
    for (JsonNode value : array) {
      if (!value.isTextual()) throw new IllegalArgumentException("TIMETABLE_MANIFEST_INVALID");
      values.add(value.asString());
    }
    return List.copyOf(values);
  }

  private String sourceMetadataJson(TimetableAtomicWrite write) {
    return json(
        Map.of(
            "datasetId", write.source().datasetId(),
            "effectiveDate", write.effectiveDate().toString(),
            "license", write.source().license(),
            "licenseCheckedAt", write.source().licenseCheckedAt(),
            "gscheduleId", write.scheduleId(),
            "importFingerprint", write.importFingerprint(),
            "mappingFingerprint", write.mappingFingerprint(),
            "sha256", write.sha256(),
            "uddi", write.source().uddi()));
  }

  private String json(Map<String, ?> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException impossible) {
      throw new IllegalStateException("TIMETABLE_METADATA_SERIALIZATION_FAILED", impossible);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record ValidatedManifest(int omissionCount) {}
}
