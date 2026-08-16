package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.place.PlaceLineage;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.application.tourapi.sync.IncrementalPlaceRepository;
import com.timingjeju.api.application.tourapi.sync.IncrementalPlaceWriteResult;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncLineage;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncStorageException;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncWrite;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcIncrementalPlaceRepository implements IncrementalPlaceRepository {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";

  private final JdbcTemplate jdbcTemplate;
  private final PlaceListRepository placeListRepository;
  private final TourApiProvenanceWriter provenanceWriter;

  public JdbcIncrementalPlaceRepository(
      JdbcTemplate jdbcTemplate,
      PlaceListRepository placeListRepository,
      TourApiProvenanceWriter provenanceWriter) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
    this.placeListRepository =
        Objects.requireNonNull(placeListRepository, "placeListRepository는 필수입니다.");
    this.provenanceWriter = Objects.requireNonNull(provenanceWriter, "provenanceWriter는 필수입니다.");
  }

  @Override
  @Transactional
  public IncrementalPlaceWriteResult apply(List<IncrementalSyncWrite> writes) {
    Objects.requireNonNull(writes, "writes는 필수입니다.");
    Counts counts = new Counts();
    try {
      for (IncrementalSyncWrite write :
          writes.stream()
              .sorted(Comparator.comparing(value -> value.change().contentId()))
              .toList()) {
        applyOne(Objects.requireNonNull(write, "write는 필수입니다."), counts);
      }
      return counts.result();
    } catch (IncrementalSyncStorageException failure) {
      throw failure;
    } catch (DataAccessException | PlaceListImportException | TourApiProvenanceException failure) {
      throw IncrementalSyncStorageException.storageFailure();
    }
  }

  private void applyOne(IncrementalSyncWrite write, Counts counts) {
    String contentId = write.change().contentId();
    lock(contentId);
    Stored stored = find(contentId);
    if (stored == null) {
      if (write.change().action() == PlaceSyncAction.DELETE) {
        counts.skipped++;
      } else {
        var result =
            placeListRepository.upsert(new PlaceListUpsertCommand(List.of(placeWrite(write))));
        counts.inserted += result.inserted();
        counts.updated += result.updated();
        counts.skipped += result.skipped();
      }
      return;
    }

    int ordering =
        stored.sourceModifiedAt() == null
            ? 1
            : write.change().sourceModifiedAt().compareTo(stored.sourceModifiedAt());
    if (ordering < 0) {
      counts.skipped++;
      return;
    }
    if (ordering == 0) {
      if (sameLogicalState(stored, write)) {
        counts.skipped++;
        return;
      }
      throw IncrementalSyncStorageException.conflictingSourceVersion();
    }

    if (write.change().action() == PlaceSyncAction.UPSERT) {
      var result =
          placeListRepository.upsert(new PlaceListUpsertCommand(List.of(placeWrite(write))));
      counts.inserted += result.inserted();
      counts.updated += result.updated();
      counts.skipped += result.skipped();
      return;
    }
    retire(stored, write, counts);
  }

  private void retire(Stored stored, IncrementalSyncWrite write, Counts counts) {
    boolean tombstone = stored.staleAt() != null;
    IncrementalSyncLineage lineage = write.lineage();
    provenanceWriter.write(
        provenance("tour_places", stored.placeId(), write.change().contentTypeId(), lineage),
        () -> {
          int placeChanged =
              jdbcTemplate.update(
                  """
                  update public.tour_places
                  set stale=true, stale_at=coalesce(stale_at, greatest(?, created_at)), stale_reason='source_deleted',
                      source_deleted_at=coalesce(source_deleted_at, greatest(?, created_at)), source_modified_at=?,
                      import_run_id=?, source_snapshot_id=?, last_seen_at=?, updated_at=?
                  where id=?
                  """,
                  ts(write.observedAt()),
                  ts(write.observedAt()),
                  ts(write.change().sourceModifiedAt()),
                  lineage.importRunId(),
                  lineage.snapshotId(),
                  ts(write.observedAt()),
                  ts(write.observedAt()),
                  stored.placeId());
          requireOne(placeChanged);
          provenanceWriter.write(
              provenance(
                  "tour_place_sources", stored.sourceId(), write.change().contentTypeId(), lineage),
              () -> {
                int sourceChanged =
                    jdbcTemplate.update(
                        """
                        update public.tour_place_sources
                        set stale_at=coalesce(stale_at, greatest(?, created_at)),
                            tombstoned_at=case when ? then coalesce(tombstoned_at, greatest(?, created_at)) else tombstoned_at end,
                            source_deleted_at=coalesce(source_deleted_at, greatest(?, created_at)), source_modified_at=?,
                            source_snapshot_id=?, last_import_run_id=?, last_seen_at=?, updated_at=?
                        where id=?
                        """,
                        ts(write.observedAt()),
                        tombstone,
                        ts(write.observedAt()),
                        ts(write.observedAt()),
                        ts(write.change().sourceModifiedAt()),
                        lineage.snapshotId(),
                        lineage.importRunId(),
                        ts(write.observedAt()),
                        ts(write.observedAt()),
                        stored.sourceId());
                requireOne(sourceChanged);
              });
        });
    if (stored.tombstonedAt() != null) {
      counts.skipped++;
    } else if (tombstone) {
      counts.tombstoned++;
    } else {
      counts.staled++;
    }
  }

  private boolean sameLogicalState(Stored stored, IncrementalSyncWrite write) {
    if (write.change().action() == PlaceSyncAction.DELETE) {
      return stored.staleAt() != null;
    }
    TourPlace place = write.change().place();
    return stored.staleAt() == null
        && Objects.equals(stored.contentTypeId(), place.contentTypeId())
        && Objects.equals(stored.name(), place.title())
        && Objects.equals(stored.address(), place.address())
        && Objects.equals(stored.addressDetail(), place.addressDetail())
        && Objects.equals(stored.imageUrl(), place.imageUrl())
        && Objects.equals(stored.thumbnailUrl(), place.thumbnailUrl())
        && Double.compare(stored.longitude(), place.longitude()) == 0
        && Double.compare(stored.latitude(), place.latitude()) == 0
        && Objects.equals(stored.lDongRegnCd(), place.lDongRegnCd())
        && Objects.equals(stored.lDongSignguCd(), place.lDongSignguCd())
        && Objects.equals(stored.lclsSystm1(), place.lclsSystm1())
        && Objects.equals(stored.lclsSystm2(), place.lclsSystm2())
        && Objects.equals(stored.lclsSystm3(), place.lclsSystm3());
  }

  private PlaceListWrite placeWrite(IncrementalSyncWrite write) {
    IncrementalSyncLineage lineage = write.lineage();
    return new PlaceListWrite(
        write.change().place(),
        write.observedAt(),
        new PlaceLineage(
            lineage.operationKey(),
            lineage.requestFingerprint(),
            lineage.snapshotId(),
            lineage.importRunId()));
  }

  private Stored find(String contentId) {
    List<Stored> rows =
        jdbcTemplate.query(
            """
            select p.id place_id, s.id source_id, p.content_type_id, p.name, p.address,
                   p.address_detail, ST_X(p.location::geometry) longitude,
                   ST_Y(p.location::geometry) latitude, p.image_url, p.thumbnail_url,
                   s.source_modified_at, s.stale_at, s.tombstoned_at,
                   s.l_dong_regn_cd, s.l_dong_signgu_cd,
                   s.lcls_systm1, s.lcls_systm2, s.lcls_systm3
            from public.tour_place_sources s join public.tour_places p on p.id=s.place_id
            where s.source_provider=? and s.source_service=? and s.external_id=?
            for update of p,s
            """,
            (rs, row) -> map(rs),
            PROVIDER,
            SERVICE,
            contentId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void lock(String contentId) {
    jdbcTemplate.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        rs -> null,
        PROVIDER + '\u001f' + SERVICE + '\u001f' + contentId);
  }

  private static Stored map(ResultSet rs) throws SQLException {
    return new Stored(
        rs.getObject("place_id", UUID.class),
        rs.getObject("source_id", UUID.class),
        rs.getString("content_type_id"),
        rs.getString("name"),
        rs.getString("address"),
        rs.getString("address_detail"),
        rs.getDouble("longitude"),
        rs.getDouble("latitude"),
        rs.getString("image_url"),
        rs.getString("thumbnail_url"),
        instant(rs, "source_modified_at"),
        instant(rs, "stale_at"),
        instant(rs, "tombstoned_at"),
        rs.getString("l_dong_regn_cd"),
        rs.getString("l_dong_signgu_cd"),
        rs.getString("lcls_systm1"),
        rs.getString("lcls_systm2"),
        rs.getString("lcls_systm3"));
  }

  private static TourApiProvenanceCommand provenance(
      String type, UUID rowId, String contentTypeId, IncrementalSyncLineage lineage) {
    return new TourApiProvenanceCommand(
        type,
        rowId,
        lineage.operationKey(),
        contentTypeId,
        lineage.requestFingerprint(),
        lineage.snapshotId(),
        lineage.importRunId());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static void requireOne(int count) {
    if (count != 1) throw IncrementalSyncStorageException.storageFailure();
  }

  private record Stored(
      UUID placeId,
      UUID sourceId,
      String contentTypeId,
      String name,
      String address,
      String addressDetail,
      double longitude,
      double latitude,
      String imageUrl,
      String thumbnailUrl,
      Instant sourceModifiedAt,
      Instant staleAt,
      Instant tombstonedAt,
      String lDongRegnCd,
      String lDongSignguCd,
      String lclsSystm1,
      String lclsSystm2,
      String lclsSystm3) {}

  private static final class Counts {
    private int inserted;
    private int updated;
    private int skipped;
    private int staled;
    private int tombstoned;

    private IncrementalPlaceWriteResult result() {
      return new IncrementalPlaceWriteResult(inserted, updated, skipped, staled, tombstoned);
    }
  }
}
