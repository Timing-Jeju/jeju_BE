package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.image.PlaceImage;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import com.timingjeju.api.application.tourapi.image.PlaceImageLineage;
import com.timingjeju.api.application.tourapi.image.PlaceImagePageLineage;
import com.timingjeju.api.application.tourapi.image.PlaceImageRepository;
import com.timingjeju.api.application.tourapi.image.PlaceImageSweep;
import com.timingjeju.api.application.tourapi.image.PlaceImageSyncCommand;
import com.timingjeju.api.application.tourapi.image.PlaceImageSyncResult;
import com.timingjeju.api.application.tourapi.image.PlaceImageWrite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcPlaceImageRepository implements PlaceImageRepository {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private final JdbcTemplate jdbc;
  private final TourApiProvenanceWriter provenance;
  private final ObjectMapper objectMapper;

  public JdbcPlaceImageRepository(
      JdbcTemplate jdbc, TourApiProvenanceWriter provenance, ObjectMapper objectMapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.provenance = Objects.requireNonNull(provenance);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  @Transactional
  public PlaceImageSyncResult sync(PlaceImageSyncCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      lock(command.contentId());
      SourcePlace source = findSource(command.contentId());
      if (!source.contentTypeId().equals(command.contentTypeId())) fail();
      validateSweep(command.contentId(), command.sweep());
      List<ExistingImage> existing = findImages(source.placeId());
      LatestSweep latest = findLatest(source.placeId(), source.contentTypeId());
      validateFreshness(command, latest, existing);
      if (latest == null || !latest.manifestHash().equals(command.sweep().manifestHash())) {
        insertSweep(source, command.sweep(), command.observedAt());
      }

      Map<String, ExistingImage> bySourceId = new HashMap<>();
      Map<String, ExistingImage> byUrl = new HashMap<>();
      for (ExistingImage stored : existing) {
        if (stored.sourceImageId() != null) bySourceId.put(stored.sourceImageId(), stored);
        byUrl.put(stored.imageUrl(), stored);
      }
      Set<UUID> incomingIds = new HashSet<>();
      Counts counts = new Counts();
      for (PlaceImageWrite write : command.batch().writes()) {
        PlaceImage image = write.image();
        String identity = identity(image.sourceImageId(), image.imageUrl());
        ExistingImage stored = matchExisting(image, bySourceId, byUrl);
        if (stored == null) {
          String payloadHash = payloadHash(image);
          UUID id = deterministicId(source.placeId(), identity);
          insert(source, id, write, payloadHash, command);
          incomingIds.add(id);
          counts.inserted++;
        } else {
          PlaceImage persistedImage = preserveStableSourceId(image, stored);
          String payloadHash = payloadHash(persistedImage);
          incomingIds.add(stored.id());
          if (Objects.equals(stored.snapshotId(), write.pageLineage().lineage().snapshotId())) {
            if (!stored.same(persistedImage, payloadHash)
                || !Objects.equals(stored.sweepId(), command.sweep().sweepId())
                || stored.staleAt() != null
                || stored.tombstonedAt() != null) fail();
            writeProvenance(
                stored.id(), source.contentTypeId(), write.pageLineage().lineage(), () -> {});
            counts.skipped++;
          } else {
            update(source, stored.id(), write, persistedImage, payloadHash, command);
            counts.updated++;
          }
        }
      }
      for (ExistingImage stored : existing) {
        if (incomingIds.contains(stored.id()) || stored.tombstonedAt() != null) continue;
        if (Objects.equals(stored.sweepId(), command.sweep().sweepId())) continue;
        lifecycle(source, stored.id(), command, stored.staleAt() != null);
        if (stored.staleAt() == null) counts.staled++;
        else counts.tombstoned++;
      }
      return counts.result();
    } catch (DataAccessException | TourApiProvenanceException | JacksonException failure) {
      throw PlaceImageImportException.storageFailure();
    }
  }

  private void lock(String contentId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 29))",
        rs -> null,
        PROVIDER + ':' + SERVICE + ':' + contentId);
  }

  private SourcePlace findSource(String contentId) {
    List<SourcePlace> rows =
        jdbc.query(
            """
        select s.place_id, s.content_type_id, s.external_id from public.tour_place_sources s
        join public.tour_places p on p.id=s.place_id
        where s.source_provider=? and s.source_service=? and s.external_id=?
        for update of p, s
        """,
            (rs, n) ->
                new SourcePlace(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            PROVIDER,
            SERVICE,
            contentId);
    if (rows.size() != 1 || rows.getFirst().contentTypeId() == null) fail();
    return rows.getFirst();
  }

  private void validateSweep(String contentId, PlaceImageSweep sweep) {
    for (PlaceImagePageLineage page : sweep.pages()) {
      PlaceImageLineage lineage = page.lineage();
      List<SourceSnapshot> snapshots =
          jdbc.query(
              """
          select snapshot.id, snapshot.fetched_at, snapshot.payload_hash
          from public.external_api_snapshots snapshot
          join public.data_import_runs run on run.id=snapshot.import_run_id
          join public.tour_api_operations operation on operation.operation_key=snapshot.source_operation
          where snapshot.id=? and snapshot.import_run_id=? and snapshot.request_hash=?
            and snapshot.source_provider=? and snapshot.source_service=?
            and snapshot.source_operation='detailImage2' and snapshot.scope_key=?
            and snapshot.page_key=? and snapshot.payload_hash=?
            and snapshot.parse_status in ('parsed','tombstoned')
            and run.source_provider=? and run.source_service=? and run.source_operation='detailImage2'
            and run.scope_key=? and operation.active
          """,
              (rs, row) ->
                  new SourceSnapshot(
                      rs.getObject("id", UUID.class),
                      Objects.requireNonNull(instant(rs.getTimestamp("fetched_at"))),
                      rs.getString("payload_hash")),
              lineage.snapshotId(),
              lineage.importRunId(),
              lineage.requestFingerprint(),
              PROVIDER,
              SERVICE,
              "content:" + contentId,
              Integer.toString(page.pageNo()),
              page.payloadHash(),
              PROVIDER,
              SERVICE,
              "content:" + contentId);
      if (snapshots.size() != 1
          || !snapshots.getFirst().payloadHash().equals(page.payloadHash())
          || !nearSameMicros(snapshots.getFirst().fetchedAt(), page.fetchedAt())) {
        fail();
      }
    }
  }

  private LatestSweep findLatest(UUID placeId, String contentTypeId) {
    List<LatestSweep> rows =
        jdbc.query(
            """
        select manifest_hash, fetched_at from public.tour_api_place_image_sweeps
        where place_id=? and source_provider=? and source_service=? and content_type_id=?
        order by fetched_at desc, id desc limit 1
        """,
            (rs, n) -> new LatestSweep(rs.getString(1), rs.getTimestamp(2).toInstant()),
            placeId,
            PROVIDER,
            SERVICE,
            contentTypeId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static void validateFreshness(
      PlaceImageSyncCommand command, LatestSweep latest, List<ExistingImage> existing) {
    if (latest == null) return;
    int comparison = command.sweep().fetchedAt().compareTo(latest.fetchedAt());
    boolean sameManifest = command.sweep().manifestHash().equals(latest.manifestHash());
    if (comparison < 0 || (comparison == 0 && !sameManifest)) fail();
    if (sameManifest) {
      Set<String> incoming = new HashSet<>();
      for (PlaceImage image : command.batch().images()) {
        incoming.add(identity(image.sourceImageId(), image.imageUrl()));
      }
      Set<String> active = new HashSet<>();
      for (ExistingImage image : existing) {
        if (image.staleAt() == null && image.tombstonedAt() == null) active.add(image.identity());
      }
      if (!incoming.equals(active)) fail();
    }
  }

  private void insertSweep(SourcePlace source, PlaceImageSweep sweep, Instant acceptedAt) {
    one(
        jdbc.update(
            """
        insert into public.tour_api_place_image_sweeps
        (id,place_id,source_provider,source_service,content_id,content_type_id,import_run_id,
         manifest_hash,fetched_at,expected_total,page_count,accepted_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
            sweep.sweepId(),
            source.placeId(),
            PROVIDER,
            SERVICE,
            source.contentId(),
            source.contentTypeId(),
            sweep.importRunId(),
            sweep.manifestHash(),
            timestamp(sweep.fetchedAt()),
            sweep.expectedTotal(),
            sweep.pages().size(),
            timestamp(acceptedAt)));
    for (PlaceImagePageLineage page : sweep.pages()) {
      one(
          jdbc.update(
              """
          insert into public.tour_api_place_image_sweep_pages
          (sweep_id,page_no,source_snapshot_id,request_fingerprint,payload_hash,raw_item_count)
          values (?,?,?,?,?,?)
          """,
              sweep.sweepId(),
              page.pageNo(),
              page.lineage().snapshotId(),
              page.lineage().requestFingerprint(),
              page.payloadHash(),
              page.rawItemCount()));
    }
  }

  private List<ExistingImage> findImages(UUID placeId) {
    return jdbc.query(
        """
        select id,source_image_id,image_url,thumbnail_url,image_name,copyright_code,copyright_owner,
          license_text,display_order,payload_hash,source_snapshot_id,source_sweep_id,stale_at,tombstoned_at
        from public.place_images
        where place_id=? and source_provider=? and source_service=? for update
        """,
        (rs, n) ->
            new ExistingImage(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getInt(9),
                rs.getString(10),
                rs.getObject(11, UUID.class),
                rs.getObject(12, UUID.class),
                instant(rs.getTimestamp(13)),
                instant(rs.getTimestamp(14))),
        placeId,
        PROVIDER,
        SERVICE);
  }

  private void insert(
      SourcePlace source,
      UUID id,
      PlaceImageWrite write,
      String hash,
      PlaceImageSyncCommand command) {
    PlaceImage image = write.image();
    PlaceImageLineage lineage = write.pageLineage().lineage();
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () ->
            one(
                jdbc.update(
                    """
        insert into public.place_images
        (id,place_id,image_url,thumbnail_url,display_order,source_provider,source_service,
         source_image_id,image_name,copyright_code,copyright_owner,license_text,payload_hash,
         source_snapshot_id,source_sweep_id,import_run_id,last_seen_at,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
                    id,
                    source.placeId(),
                    image.imageUrl(),
                    image.thumbnailUrl(),
                    image.displayOrder(),
                    PROVIDER,
                    SERVICE,
                    image.sourceImageId(),
                    image.imageName(),
                    image.copyrightCode(),
                    image.copyrightOwner(),
                    image.licenseText(),
                    hash,
                    lineage.snapshotId(),
                    command.sweep().sweepId(),
                    lineage.importRunId(),
                    timestamp(command.observedAt()),
                    timestamp(command.observedAt()))));
  }

  private void update(
      SourcePlace source,
      UUID id,
      PlaceImageWrite write,
      PlaceImage persistedImage,
      String hash,
      PlaceImageSyncCommand command) {
    PlaceImageLineage lineage = write.pageLineage().lineage();
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () ->
            one(
                jdbc.update(
                    """
        update public.place_images set source_image_id=?,image_url=?,thumbnail_url=?,display_order=?,image_name=?,
          copyright_code=?,copyright_owner=?,license_text=?,payload_hash=?,source_snapshot_id=?,
          source_sweep_id=?,import_run_id=?,last_seen_at=?,stale_at=null,tombstoned_at=null
        where id=?
        """,
                    persistedImage.sourceImageId(),
                    persistedImage.imageUrl(),
                    persistedImage.thumbnailUrl(),
                    persistedImage.displayOrder(),
                    persistedImage.imageName(),
                    persistedImage.copyrightCode(),
                    persistedImage.copyrightOwner(),
                    persistedImage.licenseText(),
                    hash,
                    lineage.snapshotId(),
                    command.sweep().sweepId(),
                    lineage.importRunId(),
                    timestamp(command.observedAt()),
                    id)));
  }

  private void lifecycle(
      SourcePlace source, UUID id, PlaceImageSyncCommand command, boolean tombstone) {
    PlaceImageLineage lineage = command.sweep().pages().getLast().lineage();
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () -> {
          String sql =
              tombstone
                  ? "update public.place_images set tombstoned_at=?,source_snapshot_id=?,source_sweep_id=?,import_run_id=? where id=? and tombstoned_at is null"
                  : "update public.place_images set stale_at=?,source_snapshot_id=?,source_sweep_id=?,import_run_id=? where id=? and stale_at is null";
          one(
              jdbc.update(
                  sql,
                  timestamp(command.observedAt()),
                  lineage.snapshotId(),
                  command.sweep().sweepId(),
                  lineage.importRunId(),
                  id));
        });
  }

  private void writeProvenance(
      UUID id, String contentTypeId, PlaceImageLineage lineage, Runnable write) {
    provenance.write(
        new TourApiProvenanceCommand(
            "place_images",
            id,
            lineage.operationKey(),
            contentTypeId,
            lineage.requestFingerprint(),
            lineage.snapshotId(),
            lineage.importRunId()),
        write);
  }

  private String payloadHash(PlaceImage image) throws JacksonException {
    List<Object> canonical = new ArrayList<>();
    canonical.add(image.sourceImageId());
    canonical.add(image.imageUrl());
    canonical.add(image.thumbnailUrl());
    canonical.add(image.imageName());
    canonical.add(image.copyrightCode());
    canonical.add(image.copyrightOwner());
    canonical.add(image.licenseText());
    canonical.add(image.displayOrder());
    return sha256(objectMapper.writeValueAsString(canonical));
  }

  private static ExistingImage matchExisting(
      PlaceImage image, Map<String, ExistingImage> bySourceId, Map<String, ExistingImage> byUrl) {
    if (image.sourceImageId() == null) return byUrl.get(image.imageUrl());
    ExistingImage byId = bySourceId.get(image.sourceImageId());
    if (byId != null) return byId;
    ExistingImage urlCandidate = byUrl.get(image.imageUrl());
    return urlCandidate != null && urlCandidate.sourceImageId() == null ? urlCandidate : null;
  }

  private static PlaceImage preserveStableSourceId(PlaceImage incoming, ExistingImage stored) {
    if (incoming.sourceImageId() != null || stored.sourceImageId() == null) return incoming;
    return new PlaceImage(
        stored.sourceImageId(),
        incoming.imageUrl(),
        incoming.thumbnailUrl(),
        incoming.imageName(),
        incoming.copyrightCode(),
        incoming.copyrightOwner(),
        incoming.licenseText(),
        incoming.displayOrder());
  }

  private static String identity(String sourceId, String url) {
    return sourceId == null ? "url\0" + url : "id\0" + sourceId;
  }

  private static UUID deterministicId(UUID placeId, String identity) {
    String hex = sha256(lengthPrefixed(placeId.toString(), PROVIDER, SERVICE, identity));
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

  private static String lengthPrefixed(String... fields) {
    StringBuilder value = new StringBuilder();
    for (String field : fields) {
      int bytes = field.getBytes(StandardCharsets.UTF_8).length;
      value.append(bytes).append(':').append(field);
    }
    return value.toString();
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

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static boolean nearSameMicros(Instant dbFetchedAt, Instant commandFetchedAt) {
    return nanosAbsBetween(dbFetchedAt, commandFetchedAt) < 1_000L;
  }

  private static long nanosAbsBetween(Instant left, Instant right) {
    long leftSec = left.getEpochSecond();
    long rightSec = right.getEpochSecond();
    int leftNano = left.getNano();
    int rightNano = right.getNano();

    if (leftSec == rightSec) {
      return Math.abs((long) leftNano - rightNano);
    }
    if (leftSec + 1 == rightSec) {
      return 1_000_000_000L + rightNano - leftNano;
    }
    if (leftSec - 1 == rightSec) {
      return 1_000_000_000L + leftNano - rightNano;
    }
    return 1_000_000_000L;
  }

  private static void one(int changed) {
    if (changed != 1) fail();
  }

  private static void fail() {
    throw PlaceImageImportException.storageFailure();
  }

  private record SourceSnapshot(UUID id, Instant fetchedAt, String payloadHash) {}

  private record SourcePlace(UUID placeId, String contentTypeId, String contentId) {}

  private record LatestSweep(String manifestHash, Instant fetchedAt) {}

  private record ExistingImage(
      UUID id,
      String sourceImageId,
      String imageUrl,
      String thumbnailUrl,
      String imageName,
      String copyrightCode,
      String copyrightOwner,
      String licenseText,
      int displayOrder,
      String payloadHash,
      UUID snapshotId,
      UUID sweepId,
      Instant staleAt,
      Instant tombstonedAt) {
    String identity() {
      return JdbcPlaceImageRepository.identity(sourceImageId, imageUrl);
    }

    boolean same(PlaceImage image, String hash) {
      return Objects.equals(payloadHash, hash)
          && Objects.equals(imageUrl, image.imageUrl())
          && Objects.equals(thumbnailUrl, image.thumbnailUrl())
          && Objects.equals(imageName, image.imageName())
          && Objects.equals(copyrightCode, image.copyrightCode())
          && Objects.equals(copyrightOwner, image.copyrightOwner())
          && Objects.equals(licenseText, image.licenseText())
          && displayOrder == image.displayOrder();
    }
  }

  private static final class Counts {
    int inserted;
    int updated;
    int skipped;
    int staled;
    int tombstoned;

    PlaceImageSyncResult result() {
      return new PlaceImageSyncResult(inserted, updated, skipped, staled, tombstoned);
    }
  }
}
