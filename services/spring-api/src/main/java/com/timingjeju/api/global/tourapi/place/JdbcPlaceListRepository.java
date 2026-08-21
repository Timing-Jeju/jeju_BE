package com.timingjeju.api.global.tourapi.place;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.place.PlaceAliasWrite;
import com.timingjeju.api.application.tourapi.place.PlaceLineage;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertResult;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlaceListRepository implements PlaceListRepository {

  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";

  private final JdbcTemplate jdbcTemplate;
  private final TourApiProvenanceWriter provenanceWriter;

  public JdbcPlaceListRepository(
      JdbcTemplate jdbcTemplate, TourApiProvenanceWriter provenanceWriter) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
    this.provenanceWriter = Objects.requireNonNull(provenanceWriter, "provenanceWriter는 필수입니다.");
  }

  @Override
  @Transactional
  public PlaceListUpsertResult upsert(PlaceListUpsertCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    try {
      List<PlaceListWrite> ordered =
          command.writes().stream()
              .sorted(Comparator.comparing((PlaceListWrite write) -> write.place().contentId()))
              .toList();
      for (PlaceListWrite write : ordered) {
        WriteOutcome outcome = upsertOne(write);
        switch (outcome) {
          case INSERTED -> inserted++;
          case UPDATED -> updated++;
          case SKIPPED -> skipped++;
        }
      }
      return new PlaceListUpsertResult(inserted, updated, skipped);
    } catch (DataAccessException | TourApiProvenanceException failure) {
      throw PlaceListImportException.storageFailure();
    }
  }

  private WriteOutcome upsertOne(PlaceListWrite write) {
    TourPlace place = write.place();
    lockNaturalKey(place.contentId());
    StoredPlace existing = find(place.contentId());
    UUID placeId =
        existing == null ? deterministicId("place", place.contentId()) : existing.placeId();
    UUID sourceId =
        existing == null || existing.sourceId() == null
            ? deterministicId("source", place.contentId())
            : existing.sourceId();
    AtomicReference<WriteOutcome> outcome = new AtomicReference<>();
    provenanceWriter.write(
        provenance("tour_places", placeId, place.contentTypeId(), write.lineage()),
        () -> {
          outcome.set(writePlace(existing, placeId, write));
          provenanceWriter.write(
              provenance("tour_place_sources", sourceId, place.contentTypeId(), write.lineage()),
              () -> writeSource(existing, sourceId, placeId, write));
          write
              .aliases()
              .forEach(alias -> writeAlias(placeId, place.contentTypeId(), alias, write));
        });
    return Objects.requireNonNull(outcome.get(), "write outcome이 누락되었습니다.");
  }

  private WriteOutcome writePlace(StoredPlace existing, UUID placeId, PlaceListWrite write) {
    if (existing == null) {
      insertPlace(placeId, write);
      return WriteOutcome.INSERTED;
    }
    if (existing.sameValue(write)) {
      return WriteOutcome.SKIPPED;
    }
    updatePlace(placeId, write);
    return WriteOutcome.UPDATED;
  }

  private void writeSource(
      StoredPlace existing, UUID sourceId, UUID placeId, PlaceListWrite write) {
    if (existing == null || existing.sourceId() == null) {
      insertSource(sourceId, placeId, write);
    } else if (!existing.sameValue(write)) {
      updateSource(sourceId, write);
    }
  }

  private void lockNaturalKey(String contentId) {
    jdbcTemplate.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        naturalKey(contentId));
  }

  private StoredPlace find(String contentId) {
    List<StoredPlace> rows =
        jdbcTemplate.query(
            """
            select p.id as place_id, s.id as source_id,
                   p.content_type_id, p.name, p.normalized_name, p.category,
                   p.region_code, p.address, p.address_detail,
                   ST_X(p.location::geometry) as longitude,
                   ST_Y(p.location::geometry) as latitude,
                   p.image_url, p.thumbnail_url, p.source_modified_at,
                   p.source_snapshot_id as place_snapshot_id, p.import_run_id,
                   s.l_dong_regn_cd, s.l_dong_signgu_cd,
                   s.lcls_systm1, s.lcls_systm2, s.lcls_systm3,
                   s.source_snapshot_id as source_snapshot_id, s.last_import_run_id
            from public.tour_places p
            left join public.tour_place_sources s
              on s.place_id=p.id
             and s.source_provider=?
             and s.source_service=?
             and s.external_id=p.content_id
            where p.content_id=?
            for update of p
            """,
            (resultSet, rowNumber) -> map(resultSet),
            PROVIDER,
            SERVICE,
            contentId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void insertPlace(UUID placeId, PlaceListWrite write) {
    TourPlace place = write.place();
    int changed =
        jdbcTemplate.update(
            """
            insert into public.tour_places (
              id, external_place_id, content_id, content_type_id, name, normalized_name,
              category, region_code, address, address_detail, location, image_url, thumbnail_url,
              source_provider, source_service, source_modified_at, import_run_id,
              source_snapshot_id, last_seen_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
              ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            placeId,
            place.contentId(),
            place.contentId(),
            place.contentTypeId(),
            place.title(),
            normalizedName(place.title()),
            category(place),
            regionCode(place),
            place.address(),
            place.addressDetail(),
            place.longitude(),
            place.latitude(),
            place.imageUrl(),
            place.thumbnailUrl(),
            PROVIDER,
            SERVICE,
            timestamp(place.sourceModifiedAt()),
            write.lineage().importRunId(),
            write.lineage().snapshotId(),
            Timestamp.from(write.seenAt()));
    requireOne(changed);
  }

  private void updatePlace(UUID placeId, PlaceListWrite write) {
    TourPlace place = write.place();
    int changed =
        jdbcTemplate.update(
            """
            update public.tour_places
            set content_type_id=?, name=?, normalized_name=?, category=?, region_code=?,
                address=?, address_detail=?, source_provider=?, source_service=?,
                location=ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                image_url=?, thumbnail_url=?, source_modified_at=?, import_run_id=?,
                source_snapshot_id=?, last_seen_at=?, stale=false, stale_at=null,
                stale_reason=null, source_deleted_at=null, updated_at=?
            where id=?
            """,
            place.contentTypeId(),
            place.title(),
            normalizedName(place.title()),
            category(place),
            regionCode(place),
            place.address(),
            place.addressDetail(),
            PROVIDER,
            SERVICE,
            place.longitude(),
            place.latitude(),
            place.imageUrl(),
            place.thumbnailUrl(),
            timestamp(place.sourceModifiedAt()),
            write.lineage().importRunId(),
            write.lineage().snapshotId(),
            Timestamp.from(write.seenAt()),
            Timestamp.from(write.seenAt()),
            placeId);
    requireOne(changed);
  }

  private void insertSource(UUID sourceId, UUID placeId, PlaceListWrite write) {
    TourPlace place = write.place();
    int changed =
        jdbcTemplate.update(
            """
            insert into public.tour_place_sources (
              id, place_id, source_provider, source_service, external_id, content_type_id,
              l_dong_regn_cd, l_dong_signgu_cd, lcls_systm1, lcls_systm2, lcls_systm3,
              source_snapshot_id, last_import_run_id, source_modified_at, last_seen_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            sourceId,
            placeId,
            PROVIDER,
            SERVICE,
            place.contentId(),
            place.contentTypeId(),
            place.lDongRegnCd(),
            place.lDongSignguCd(),
            place.lclsSystm1(),
            place.lclsSystm2(),
            place.lclsSystm3(),
            write.lineage().snapshotId(),
            write.lineage().importRunId(),
            timestamp(place.sourceModifiedAt()),
            Timestamp.from(write.seenAt()));
    requireOne(changed);
  }

  private void updateSource(UUID sourceId, PlaceListWrite write) {
    TourPlace place = write.place();
    int changed =
        jdbcTemplate.update(
            """
            update public.tour_place_sources
            set content_type_id=?, l_dong_regn_cd=?, l_dong_signgu_cd=?,
                lcls_systm1=?, lcls_systm2=?, lcls_systm3=?, source_snapshot_id=?,
                last_import_run_id=?, source_modified_at=?, last_seen_at=?, stale_at=null,
                tombstoned_at=null, source_deleted_at=null, updated_at=?
            where id=?
            """,
            place.contentTypeId(),
            place.lDongRegnCd(),
            place.lDongSignguCd(),
            place.lclsSystm1(),
            place.lclsSystm2(),
            place.lclsSystm3(),
            write.lineage().snapshotId(),
            write.lineage().importRunId(),
            timestamp(place.sourceModifiedAt()),
            Timestamp.from(write.seenAt()),
            Timestamp.from(write.seenAt()),
            sourceId);
    requireOne(changed);
  }

  private void writeAlias(
      UUID placeId, String contentTypeId, PlaceAliasWrite alias, PlaceListWrite write) {
    UUID aliasId = findAliasId(placeId, alias.normalizedAlias());
    boolean existing = aliasId != null;
    if (!existing) {
      aliasId = deterministicId("alias", placeId + "\u001f" + alias.normalizedAlias());
    }
    UUID normalizedRowId = aliasId;
    provenanceWriter.write(
        provenance("place_aliases", normalizedRowId, contentTypeId, write.lineage()),
        () -> {
          int changed =
              existing
                  ? jdbcTemplate.update(
                      """
                      update public.place_aliases
                      set alias=?, source_snapshot_id=?, import_run_id=?, last_seen_at=?,
                          stale_at=null, tombstoned_at=null
                      where id=?
                        and (alias, source_snapshot_id, import_run_id, last_seen_at, stale_at, tombstoned_at)
                          is distinct from (?, ?, ?, ?, null, null)
                      """,
                      alias.alias(),
                      write.lineage().snapshotId(),
                      write.lineage().importRunId(),
                      Timestamp.from(write.seenAt()),
                      normalizedRowId,
                      alias.alias(),
                      write.lineage().snapshotId(),
                      write.lineage().importRunId(),
                      Timestamp.from(write.seenAt()))
                  : jdbcTemplate.update(
                      """
                      insert into public.place_aliases (
                        id, place_id, alias, normalized_alias, alias_type, confidence,
                        source_snapshot_id, import_run_id, last_seen_at
                      ) values (?, ?, ?, ?, 'keyword', 1.000, ?, ?, ?)
                      """,
                      normalizedRowId,
                      placeId,
                      alias.alias(),
                      alias.normalizedAlias(),
                      write.lineage().snapshotId(),
                      write.lineage().importRunId(),
                      Timestamp.from(write.seenAt()));
          if ((!existing && changed != 1) || (existing && changed > 1)) {
            throw PlaceListImportException.storageFailure();
          }
        });
  }

  private UUID findAliasId(UUID placeId, String normalizedAlias) {
    List<UUID> rows =
        jdbcTemplate.query(
            """
            select id from public.place_aliases
            where place_id=? and normalized_alias=? and alias_type='keyword'
            for update
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            placeId,
            normalizedAlias);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static TourApiProvenanceCommand provenance(
      String entityType, UUID rowId, String contentTypeId, PlaceLineage lineage) {
    return new TourApiProvenanceCommand(
        entityType,
        rowId,
        lineage.operationKey(),
        contentTypeId,
        lineage.requestFingerprint(),
        lineage.snapshotId(),
        lineage.importRunId());
  }

  private static StoredPlace map(ResultSet resultSet) throws SQLException {
    return new StoredPlace(
        resultSet.getObject("place_id", UUID.class),
        resultSet.getObject("source_id", UUID.class),
        resultSet.getString("content_type_id"),
        resultSet.getString("name"),
        resultSet.getString("normalized_name"),
        resultSet.getString("category"),
        resultSet.getString("region_code"),
        resultSet.getString("address"),
        resultSet.getString("address_detail"),
        resultSet.getDouble("longitude"),
        resultSet.getDouble("latitude"),
        resultSet.getString("image_url"),
        resultSet.getString("thumbnail_url"),
        instant(resultSet, "source_modified_at"),
        resultSet.getObject("place_snapshot_id", UUID.class),
        resultSet.getObject("import_run_id", UUID.class),
        resultSet.getString("l_dong_regn_cd"),
        resultSet.getString("l_dong_signgu_cd"),
        resultSet.getString("lcls_systm1"),
        resultSet.getString("lcls_systm2"),
        resultSet.getString("lcls_systm3"),
        resultSet.getObject("source_snapshot_id", UUID.class),
        resultSet.getObject("last_import_run_id", UUID.class));
  }

  private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(java.time.Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static String category(TourPlace place) {
    return CanonicalPlaceCategory.fromSource(place.lclsSystm1(), place.contentTypeId());
  }

  private static String regionCode(TourPlace place) {
    return place.lDongSignguCd() == null ? place.lDongRegnCd() : place.lDongSignguCd();
  }

  private static String normalizedName(String title) {
    return title.strip().toLowerCase(Locale.ROOT);
  }

  private static void requireOne(int changed) {
    if (changed != 1) {
      throw PlaceListImportException.storageFailure();
    }
  }

  private static UUID deterministicId(String type, String contentId) {
    byte[] hash;
    try {
      hash =
          MessageDigest.getInstance("SHA-256")
              .digest((naturalKey(contentId) + '\u001f' + type).getBytes(StandardCharsets.UTF_8));
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

  private static String naturalKey(String contentId) {
    return PROVIDER + '\u001f' + SERVICE + '\u001f' + contentId;
  }

  private enum WriteOutcome {
    INSERTED,
    UPDATED,
    SKIPPED
  }

  private record StoredPlace(
      UUID placeId,
      UUID sourceId,
      String contentTypeId,
      String name,
      String normalizedName,
      String category,
      String regionCode,
      String address,
      String addressDetail,
      double longitude,
      double latitude,
      String imageUrl,
      String thumbnailUrl,
      java.time.Instant sourceModifiedAt,
      UUID placeSnapshotId,
      UUID placeRunId,
      String lDongRegnCd,
      String lDongSignguCd,
      String lclsSystm1,
      String lclsSystm2,
      String lclsSystm3,
      UUID sourceSnapshotId,
      UUID sourceRunId) {

    private boolean sameValue(PlaceListWrite write) {
      TourPlace place = write.place();
      return contentTypeId.equals(place.contentTypeId())
          && name.equals(place.title())
          && normalizedName.equals(JdbcPlaceListRepository.normalizedName(place.title()))
          && category.equals(JdbcPlaceListRepository.category(place))
          && Objects.equals(regionCode, JdbcPlaceListRepository.regionCode(place))
          && Objects.equals(address, place.address())
          && Objects.equals(addressDetail, place.addressDetail())
          && Double.compare(longitude, place.longitude()) == 0
          && Double.compare(latitude, place.latitude()) == 0
          && Objects.equals(imageUrl, place.imageUrl())
          && Objects.equals(thumbnailUrl, place.thumbnailUrl())
          && Objects.equals(sourceModifiedAt, place.sourceModifiedAt())
          && Objects.equals(placeSnapshotId, write.lineage().snapshotId())
          && Objects.equals(placeRunId, write.lineage().importRunId())
          && Objects.equals(lDongRegnCd, place.lDongRegnCd())
          && Objects.equals(lDongSignguCd, place.lDongSignguCd())
          && Objects.equals(lclsSystm1, place.lclsSystm1())
          && Objects.equals(lclsSystm2, place.lclsSystm2())
          && Objects.equals(lclsSystm3, place.lclsSystm3())
          && Objects.equals(sourceSnapshotId, write.lineage().snapshotId())
          && Objects.equals(sourceRunId, write.lineage().importRunId());
    }
  }
}
