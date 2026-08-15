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
          () -> writeOverview(source.placeId(), command, existing));
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
            select p.id, s.content_type_id
            from public.tour_place_sources s join public.tour_places p on p.id=s.place_id
            where s.source_provider=? and s.source_service=? and s.external_id=?
            for update of p, s
            """,
            (rs, row) ->
                new SourcePlace(rs.getObject("id", UUID.class), rs.getString("content_type_id")),
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
              and d.source_snapshot_id=? and d.import_run_id=? as same_detail,
              p.overview is not distinct from ? as same_overview
            from public.place_details d join public.tour_places p on p.id=d.place_id
            where d.place_id=?
            for update of d
            """,
            (rs, row) ->
                new ExistingDetail(rs.getBoolean("same_detail"), rs.getBoolean("same_overview")),
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
            command.introLineage().snapshotId(),
            command.introLineage().importRunId(),
            command.common().overviewPlainText(),
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
    if (existing.sameDetail() && existing.sameOverview())
      return PlaceDetailUpsertResult.skippedResult();
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
      UUID placeId, PlaceDetailUpsertCommand command, ExistingDetail existing) {
    if (existing != null && existing.sameOverview()) return;
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
            placeId);
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

  private static void requireOne(int changed) {
    if (changed != 1) throw PlaceDetailImportException.storageFailure();
  }

  private record SourcePlace(UUID placeId, String contentTypeId) {}

  private record ExistingDetail(boolean sameDetail, boolean sameOverview) {}
}
