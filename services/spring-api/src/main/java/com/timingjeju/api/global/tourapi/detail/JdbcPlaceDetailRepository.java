package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.detail.DetailLineage;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertCommand;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class JdbcPlaceDetailRepository implements PlaceDetailRepository {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";

  private final JdbcTemplate jdbc;
  private final TourApiProvenanceWriter provenanceWriter;
  private final ObjectMapper objectMapper;

  public JdbcPlaceDetailRepository(
      JdbcTemplate jdbc, TourApiProvenanceWriter provenanceWriter, ObjectMapper objectMapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.provenanceWriter = Objects.requireNonNull(provenanceWriter);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  @Transactional
  public PlaceDetailUpsertResult upsert(PlaceDetailUpsertCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      lock(command.contentId());
      SourcePlace source = findSource(command.contentId());
      validateSource(source, command);
      String attributes = attributes(command);
      ExistingDetail existing = findDetail(source.placeId(), attributes, command);
      validateFreshness(source, existing, command);
      AtomicReference<PlaceDetailUpsertResult> outcome = new AtomicReference<>();
      provenanceWriter.write(
          provenance(
              "place_details", source.placeId(), command.commonLineage(), source.contentTypeId()),
          () ->
              provenanceWriter.write(
                  provenance(
                      "place_details",
                      source.placeId(),
                      command.introLineage(),
                      source.contentTypeId()),
                  () -> outcome.set(writeDetail(source.placeId(), existing, command, attributes))));
      provenanceWriter.write(
          provenance(
              "tour_places", source.placeId(), command.commonLineage(), source.contentTypeId()),
          () -> writeOverview(source, command, existing));
      return Objects.requireNonNull(outcome.get());
    } catch (DataAccessException | TourApiProvenanceException | JacksonException failure) {
      throw PlaceDetailImportException.storageFailure();
    }
  }

  private void lock(String contentId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 27))",
        resultSet -> null,
        naturalKey(contentId));
  }

  private SourcePlace findSource(String contentId) {
    List<SourcePlace> rows =
        jdbc.query(
            """
            select p.id, s.content_type_id, p.overview, p.source_modified_at
            from public.tour_place_sources s join public.tour_places p on p.id=s.place_id
            where s.source_provider=? and s.source_service=? and s.external_id=?
            for update of p, s
            """,
            (rs, row) ->
                new SourcePlace(
                    rs.getObject("id", UUID.class),
                    rs.getString("content_type_id"),
                    rs.getString("overview"),
                    instant(rs.getTimestamp("source_modified_at"))),
            PROVIDER,
            SERVICE,
            contentId);
    if (rows.size() != 1) throw PlaceDetailImportException.storageFailure();
    return rows.getFirst();
  }

  private static void validateSource(SourcePlace source, PlaceDetailUpsertCommand command) {
    if (!source.contentTypeId().equals(command.common().contentTypeId())) {
      throw PlaceDetailImportException.storageFailure();
    }
  }

  private ExistingDetail findDetail(
      UUID placeId, String attributes, PlaceDetailUpsertCommand command) {
    List<ExistingDetail> rows =
        jdbc.query(
            """
            select d.place_id,
              d.phone is not distinct from ? and d.homepage_url is not distinct from ?
              and d.operating_hours_text is not distinct from ? and d.closed_days_text is not distinct from ?
              and d.parking_text is not distinct from ? and d.pet_policy_text is not distinct from ?
              and d.admission_fee_text is not distinct from ? and d.facilities_text is not distinct from ?
              and d.reservation_info_text is not distinct from ? and d.accessibility_text is not distinct from ?
              and d.intro_attributes = ?::jsonb and d.source_updated_at is not distinct from ?
              and p.overview is not distinct from ? as same_values,
              d.homepage_url is not distinct from ?
              and d.intro_attributes->'detailCommon2' = (?::jsonb)->'detailCommon2'
              and d.source_updated_at is not distinct from ?
              and p.overview is not distinct from ? as same_common,
              d.operating_hours_text is not distinct from ?
              and d.closed_days_text is not distinct from ?
              and d.parking_text is not distinct from ?
              and d.pet_policy_text is not distinct from ?
              and d.admission_fee_text is not distinct from ?
              and d.facilities_text is not distinct from ?
              and d.reservation_info_text is not distinct from ?
              and d.accessibility_text is not distinct from ?
              and d.intro_attributes->'detailIntro2' = (?::jsonb)->'detailIntro2' as same_intro,
              d.source_updated_at
            from public.place_details d join public.tour_places p on p.id=d.place_id
            where d.place_id=?
            for update of d
            """,
            (rs, row) ->
                new ExistingDetail(
                    rs.getBoolean("same_values"),
                    rs.getBoolean("same_common"),
                    rs.getBoolean("same_intro"),
                    instant(rs.getTimestamp("source_updated_at"))),
            command.intro().phone() != null ? command.intro().phone() : command.common().phone(),
            command.common().homepageUrl(),
            command.intro().operatingHoursText(),
            command.intro().closedDaysText(),
            command.intro().parkingText(),
            command.intro().petPolicyText(),
            command.intro().admissionFeeText(),
            command.intro().facilitiesText(),
            command.intro().reservationInfoText(),
            command.intro().accessibilityText(),
            attributes,
            timestamp(command.common().sourceModifiedAt()),
            command.common().overviewPlainText(),
            command.common().homepageUrl(),
            attributes,
            timestamp(command.common().sourceModifiedAt()),
            command.common().overviewPlainText(),
            command.intro().operatingHoursText(),
            command.intro().closedDaysText(),
            command.intro().parkingText(),
            command.intro().petPolicyText(),
            command.intro().admissionFeeText(),
            command.intro().facilitiesText(),
            command.intro().reservationInfoText(),
            command.intro().accessibilityText(),
            attributes,
            placeId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private PlaceDetailUpsertResult writeDetail(
      UUID placeId, ExistingDetail existing, PlaceDetailUpsertCommand command, String attributes) {
    if (existing == null) {
      int changed =
          jdbc.update(
              """
          insert into public.place_details (place_id, phone, homepage_url, operating_hours_text,
            closed_days_text, parking_text, pet_policy_text, admission_fee_text, facilities_text,
            reservation_info_text, accessibility_text, intro_attributes, source_provider,
            source_service, source_updated_at, fetched_at, updated_at, source_snapshot_id,
            import_run_id, last_seen_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
              placeId,
              phone(command),
              command.common().homepageUrl(),
              command.intro().operatingHoursText(),
              command.intro().closedDaysText(),
              command.intro().parkingText(),
              command.intro().petPolicyText(),
              command.intro().admissionFeeText(),
              command.intro().facilitiesText(),
              command.intro().reservationInfoText(),
              command.intro().accessibilityText(),
              attributes,
              PROVIDER,
              SERVICE,
              timestamp(command.common().sourceModifiedAt()),
              timestamp(command.fetchedAt()),
              timestamp(command.fetchedAt()),
              command.introLineage().snapshotId(),
              command.introLineage().importRunId(),
              timestamp(command.fetchedAt()));
      requireOne(changed);
      return PlaceDetailUpsertResult.insertedResult();
    }
    if (existing.sameValues()) return PlaceDetailUpsertResult.skippedResult();
    int changed =
        jdbc.update(
            """
        update public.place_details set phone=?, homepage_url=?, operating_hours_text=?, closed_days_text=?,
          parking_text=?, pet_policy_text=?, admission_fee_text=?, facilities_text=?, reservation_info_text=?,
          accessibility_text=?, intro_attributes=?::jsonb, source_provider=?, source_service=?, source_updated_at=?,
          fetched_at=?, updated_at=?, source_snapshot_id=?, import_run_id=?, last_seen_at=?, stale_at=null,
          tombstoned_at=null where place_id=?
        """,
            phone(command),
            command.common().homepageUrl(),
            command.intro().operatingHoursText(),
            command.intro().closedDaysText(),
            command.intro().parkingText(),
            command.intro().petPolicyText(),
            command.intro().admissionFeeText(),
            command.intro().facilitiesText(),
            command.intro().reservationInfoText(),
            command.intro().accessibilityText(),
            attributes,
            PROVIDER,
            SERVICE,
            timestamp(command.common().sourceModifiedAt()),
            timestamp(command.fetchedAt()),
            timestamp(command.fetchedAt()),
            command.introLineage().snapshotId(),
            command.introLineage().importRunId(),
            timestamp(command.fetchedAt()),
            placeId);
    requireOne(changed);
    return PlaceDetailUpsertResult.updatedResult();
  }

  private void writeOverview(
      SourcePlace source, PlaceDetailUpsertCommand command, ExistingDetail existing) {
    if ((existing != null && existing.sameCommon())
        || (existing == null && samePlaceCommon(source, command))) return;
    int changed =
        jdbc.update(
            """
        update public.tour_places set overview=?, source_modified_at=?, source_snapshot_id=?,
          import_run_id=?, last_seen_at=?, updated_at=? where id=?
        """,
            command.common().overviewPlainText(),
            timestamp(command.common().sourceModifiedAt()),
            command.commonLineage().snapshotId(),
            command.commonLineage().importRunId(),
            timestamp(command.fetchedAt()),
            timestamp(command.fetchedAt()),
            source.placeId());
    requireOne(changed);
  }

  private String attributes(PlaceDetailUpsertCommand command) throws JacksonException {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("contentTypeId", command.common().contentTypeId());
    Map<String, Object> common = new LinkedHashMap<>();
    common.put("overviewRaw", command.common().overviewRaw());
    common.put("phone", command.common().phone());
    common.put("homepageUrl", command.common().homepageUrl());
    root.put("detailCommon2", common);
    root.put("detailIntro2", command.intro().introAttributes());
    return objectMapper.writeValueAsString(root);
  }

  private void validateFreshness(
      SourcePlace source, ExistingDetail existing, PlaceDetailUpsertCommand command) {
    Instant incomingCommonFetchedAt = snapshotFetchedAt(command.commonLineage());
    Instant incomingIntroFetchedAt = snapshotFetchedAt(command.introLineage());
    if (existing == null) {
      validateFirstCommonFreshness(source, command);
      return;
    }
    validateCommonFreshness(
        source.placeId(), existing, command.common().sourceModifiedAt(), incomingCommonFetchedAt);
    validateIntroFreshness(source.placeId(), existing, incomingIntroFetchedAt);
  }

  private static void validateFirstCommonFreshness(
      SourcePlace source, PlaceDetailUpsertCommand command) {
    Instant storedSourceModifiedAt = source.sourceModifiedAt();
    Instant incomingSourceModifiedAt = command.common().sourceModifiedAt();
    if (storedSourceModifiedAt == null) {
      return;
    }
    if (incomingSourceModifiedAt == null
        || incomingSourceModifiedAt.isBefore(storedSourceModifiedAt)
        || (incomingSourceModifiedAt.equals(storedSourceModifiedAt)
            && !Objects.equals(source.overview(), command.common().overviewPlainText()))) {
      throw PlaceDetailImportException.staleSource();
    }
  }

  private static boolean samePlaceCommon(SourcePlace source, PlaceDetailUpsertCommand command) {
    return Objects.equals(source.sourceModifiedAt(), command.common().sourceModifiedAt())
        && Objects.equals(source.overview(), command.common().overviewPlainText());
  }

  private void validateCommonFreshness(
      UUID placeId,
      ExistingDetail existing,
      Instant incomingSourceModifiedAt,
      Instant incomingSnapshotFetchedAt) {
    Instant storedSourceModifiedAt = existing.sourceUpdatedAt();
    if (storedSourceModifiedAt != null) {
      if (incomingSourceModifiedAt == null
          || incomingSourceModifiedAt.isBefore(storedSourceModifiedAt)
          || (incomingSourceModifiedAt.equals(storedSourceModifiedAt) && !existing.sameCommon())) {
        throw PlaceDetailImportException.staleSource();
      }
      return;
    }
    if (incomingSourceModifiedAt != null) {
      return;
    }
    Instant latestFetchedAt = latestSnapshotFetchedAt(placeId, "detailCommon2");
    if (latestFetchedAt != null
        && (incomingSnapshotFetchedAt.isBefore(latestFetchedAt)
            || (incomingSnapshotFetchedAt.equals(latestFetchedAt) && !existing.sameCommon()))) {
      throw PlaceDetailImportException.staleSource();
    }
  }

  private void validateIntroFreshness(
      UUID placeId, ExistingDetail existing, Instant incomingSnapshotFetchedAt) {
    Instant latestFetchedAt = latestSnapshotFetchedAt(placeId, "detailIntro2");
    if (latestFetchedAt != null
        && (incomingSnapshotFetchedAt.isBefore(latestFetchedAt)
            || (incomingSnapshotFetchedAt.equals(latestFetchedAt) && !existing.sameIntro()))) {
      throw PlaceDetailImportException.staleSource();
    }
  }

  private Instant snapshotFetchedAt(DetailLineage lineage) {
    List<Instant> rows =
        jdbc.query(
            """
            select snapshot.fetched_at
            from public.external_api_snapshots snapshot
            where snapshot.id=? and snapshot.import_run_id=?
              and snapshot.source_provider=? and snapshot.source_service=?
              and snapshot.source_operation=? and snapshot.request_hash=?
              and snapshot.parse_status in ('parsed', 'tombstoned')
            for key share
            """,
            (resultSet, rowNumber) -> resultSet.getTimestamp("fetched_at").toInstant(),
            lineage.snapshotId(),
            lineage.importRunId(),
            PROVIDER,
            SERVICE,
            lineage.operationKey(),
            lineage.requestFingerprint());
    if (rows.size() != 1) {
      throw PlaceDetailImportException.storageFailure();
    }
    return rows.getFirst();
  }

  private Instant latestSnapshotFetchedAt(UUID placeId, String operation) {
    return jdbc.queryForObject(
        """
        select max(snapshot.fetched_at)
        from public.tour_api_operation_provenance provenance
        join public.external_api_snapshots snapshot on snapshot.id=provenance.source_snapshot_id
        where provenance.normalized_entity_type='place_details'
          and provenance.normalized_row_id=? and provenance.operation_key=?
        """,
        (resultSet, rowNumber) -> instant(resultSet.getTimestamp(1)),
        placeId,
        operation);
  }

  private static String phone(PlaceDetailUpsertCommand command) {
    return command.intro().phone() != null ? command.intro().phone() : command.common().phone();
  }

  private static TourApiProvenanceCommand provenance(
      String type, UUID id, DetailLineage lineage, String contentType) {
    return new TourApiProvenanceCommand(
        type,
        id,
        lineage.operationKey(),
        contentType,
        lineage.requestFingerprint(),
        lineage.snapshotId(),
        lineage.importRunId());
  }

  private static String naturalKey(String contentId) {
    return PROVIDER + ':' + SERVICE + ':' + contentId;
  }

  private static Timestamp timestamp(java.time.Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static void requireOne(int changed) {
    if (changed != 1) throw PlaceDetailImportException.storageFailure();
  }

  private record SourcePlace(
      UUID placeId, String contentTypeId, String overview, Instant sourceModifiedAt) {}

  private record ExistingDetail(
      boolean sameValues, boolean sameCommon, boolean sameIntro, Instant sourceUpdatedAt) {}
}
