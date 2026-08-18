package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.application.tourapi.detailitem.DetailItem;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemBatch;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemLineage;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemPageLineage;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemRepository;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSweep;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSyncCommand;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSyncResult;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemWrite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDetailItemRepository implements DetailItemRepository {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";

  private final JdbcTemplate jdbc;
  private final TourApiProvenanceWriter provenanceWriter;
  private final ObjectMapper objectMapper;

  public JdbcDetailItemRepository(
      JdbcTemplate jdbc, TourApiProvenanceWriter provenanceWriter, ObjectMapper objectMapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.provenanceWriter = Objects.requireNonNull(provenanceWriter);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  @Transactional
  public DetailItemSyncResult sync(DetailItemSyncCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      lock(command.contentId());
      SourcePlace source = findSource(command.contentId());
      if (!command.contentTypeId().equals(source.contentTypeId())) {
        throw DetailItemImportException.storageFailure();
      }
      validateSweep(command.contentId(), command.sweep());
      Map<String, ExistingItem> existing = findItems(source.placeId(), command.contentTypeId());
      LatestSweep latest = findLatestSweep(source.placeId(), command.contentTypeId());
      validateFreshness(command.sweep(), latest, existing, command.batch());
      if (latest == null || !latest.manifestHash().equals(command.sweep().manifestHash())) {
        insertSweep(source, command.sweep(), command.observedAt());
      }
      Counts counts = new Counts();
      Set<String> incomingKeys =
          command.batch().items().stream()
              .map(JdbcDetailItemRepository::scopedKey)
              .collect(Collectors.toSet());

      for (DetailItemWrite write : command.batch().writes()) {
        DetailItem item = write.item();
        String key = scopedKey(item);
        ExistingItem stored = existing.get(key);
        ItemDocument document = document(item);
        if (stored == null) {
          insert(source, write, document, command);
          counts.inserted++;
        } else if (Objects.equals(
            stored.snapshotId(), write.pageLineage().lineage().snapshotId())) {
          if (!stored.same(document, item)
              || !Objects.equals(stored.sweepId(), command.sweep().sweepId())
              || stored.staleAt() != null
              || stored.tombstonedAt() != null) {
            throw DetailItemImportException.storageFailure();
          }
          writeProvenance(
              stored.id(), source.contentTypeId(), write.pageLineage().lineage(), () -> {});
          counts.skipped++;
        } else {
          update(source, stored.id(), write, document, command);
          counts.updated++;
        }
      }

      for (ExistingItem stored : existing.values()) {
        if (incomingKeys.contains(stored.scopedKey()) || stored.tombstonedAt() != null) continue;
        if (Objects.equals(stored.sweepId(), command.sweep().sweepId())) continue;
        if (stored.staleAt() == null) {
          markStale(source, stored.id(), command);
          counts.staled++;
        } else {
          markTombstoned(source, stored.id(), command);
          counts.tombstoned++;
        }
      }
      return counts.result();
    } catch (DataAccessException | TourApiProvenanceException | JacksonException failure) {
      throw DetailItemImportException.storageFailure();
    }
  }

  private void lock(String contentId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 28))",
        resultSet -> null,
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
            (rs, row) ->
                new SourcePlace(
                    rs.getObject("place_id", UUID.class),
                    rs.getString("content_type_id"),
                    rs.getString("external_id")),
            PROVIDER,
            SERVICE,
            contentId);
    if (rows.size() != 1 || rows.getFirst().contentTypeId() == null) {
      throw DetailItemImportException.storageFailure();
    }
    return rows.getFirst();
  }

  private void validateSweep(String contentId, DetailItemSweep sweep) {
    for (DetailItemPageLineage page : sweep.pages()) {
      DetailItemLineage lineage = page.lineage();
      List<SourceSnapshot> snapshots =
          jdbc.query(
              """
              select snapshot.id, snapshot.fetched_at, snapshot.payload_hash
              from public.external_api_snapshots snapshot
              join public.data_import_runs run on run.id=snapshot.import_run_id
              join public.tour_api_operations operation on operation.operation_key=snapshot.source_operation
              where snapshot.id=? and snapshot.import_run_id=? and snapshot.request_hash=?
                and snapshot.source_operation='detailInfo2'
                and snapshot.source_provider=? and snapshot.source_service=? and snapshot.scope_key=?
                and snapshot.page_key=? and snapshot.payload_hash=?
                and snapshot.parse_status in ('parsed','tombstoned')
                and run.source_operation='detailInfo2' and run.source_provider=? and run.source_service=?
                and operation.active
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
              SERVICE);
      if (snapshots.size() != 1
          || !snapshots.getFirst().payloadHash().equals(page.payloadHash())
          || !nearSameMicros(snapshots.getFirst().fetchedAt(), page.fetchedAt())) {
        throw DetailItemImportException.storageFailure();
      }
    }
  }

  private LatestSweep findLatestSweep(UUID placeId, String contentTypeId) {
    List<LatestSweep> rows =
        jdbc.query(
            """
            select id, manifest_hash, fetched_at
            from public.tour_api_detail_item_sweeps
            where place_id=? and content_type_id=?
              and source_provider=? and source_service=?
            order by fetched_at desc, id desc
            limit 1
            """,
            (rs, row) ->
                new LatestSweep(
                    rs.getObject("id", UUID.class),
                    rs.getString("manifest_hash"),
                    Objects.requireNonNull(instant(rs.getTimestamp("fetched_at")))),
            placeId,
            contentTypeId,
            PROVIDER,
            SERVICE);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static void validateFreshness(
      DetailItemSweep incoming,
      LatestSweep latest,
      Map<String, ExistingItem> existing,
      DetailItemBatch batch) {
    if (latest == null) return;
    int freshness = incoming.fetchedAt().compareTo(latest.fetchedAt());
    if (freshness < 0
        || (freshness == 0 && !incoming.manifestHash().equals(latest.manifestHash()))) {
      throw DetailItemImportException.storageFailure();
    }
    if (incoming.manifestHash().equals(latest.manifestHash())) {
      Set<String> incomingKeys =
          batch.items().stream()
              .map(JdbcDetailItemRepository::scopedKey)
              .collect(Collectors.toSet());
      boolean sameActiveKeys =
          existing.values().stream()
              .filter(row -> row.staleAt() == null && row.tombstonedAt() == null)
              .map(ExistingItem::scopedKey)
              .collect(Collectors.toSet())
              .equals(incomingKeys);
      if (!sameActiveKeys) throw DetailItemImportException.storageFailure();
    }
  }

  private void insertSweep(SourcePlace source, DetailItemSweep sweep, Instant observedAt) {
    requireOne(
        jdbc.update(
            """
            insert into public.tour_api_detail_item_sweeps
              (id, place_id, source_provider, source_service, content_id, content_type_id,
               import_run_id, manifest_hash, fetched_at, expected_total, page_count, accepted_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            timestamp(observedAt)));
    for (DetailItemPageLineage page : sweep.pages()) {
      requireOne(
          jdbc.update(
              """
              insert into public.tour_api_detail_item_sweep_pages
                (sweep_id, page_no, source_snapshot_id, request_fingerprint,
                 payload_hash, raw_item_count)
              values (?, ?, ?, ?, ?, ?)
              """,
              sweep.sweepId(),
              page.pageNo(),
              page.lineage().snapshotId(),
              page.lineage().requestFingerprint(),
              page.payloadHash(),
              page.rawItemCount()));
    }
  }

  private Map<String, ExistingItem> findItems(UUID placeId, String contentTypeId) {
    List<ExistingItem> rows =
        jdbc.query(
            """
            select id, item_type, source_item_key, title, sequence_no, attributes::text,
              payload_hash, source_snapshot_id, source_sweep_id, stale_at, tombstoned_at
            from public.place_detail_items
            where place_id=? and source_provider=? and source_service=? and content_type_id=?
            for update
            """,
            (rs, row) ->
                new ExistingItem(
                    rs.getObject("id", UUID.class),
                    rs.getString("item_type"),
                    rs.getString("source_item_key"),
                    rs.getString("title"),
                    rs.getInt("sequence_no"),
                    rs.getString("attributes"),
                    rs.getString("payload_hash"),
                    rs.getObject("source_snapshot_id", UUID.class),
                    rs.getObject("source_sweep_id", UUID.class),
                    instant(rs.getTimestamp("stale_at")),
                    instant(rs.getTimestamp("tombstoned_at"))),
            placeId,
            PROVIDER,
            SERVICE,
            contentTypeId);
    return rows.stream().collect(Collectors.toMap(ExistingItem::scopedKey, row -> row));
  }

  private void insert(
      SourcePlace source,
      DetailItemWrite write,
      ItemDocument document,
      DetailItemSyncCommand command) {
    DetailItem item = write.item();
    DetailItemLineage lineage = write.pageLineage().lineage();
    UUID id = deterministicId(source.placeId(), item);
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () -> {
          int changed =
              jdbc.update(
                  """
                  insert into public.place_detail_items
                    (id, place_id, source_provider, source_service, content_type_id, item_type,
                     source_item_key, title, sequence_no, attributes, payload_hash,
                     source_snapshot_id, source_sweep_id, import_run_id, last_seen_at, created_at, updated_at)
                  values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                  """,
                  id,
                  source.placeId(),
                  PROVIDER,
                  SERVICE,
                  source.contentTypeId(),
                  item.itemType(),
                  item.sourceItemKey(),
                  item.title(),
                  item.sequenceNo(),
                  document.attributesJson(),
                  document.payloadHash(),
                  lineage.snapshotId(),
                  command.sweep().sweepId(),
                  lineage.importRunId(),
                  timestamp(command.observedAt()),
                  timestamp(command.observedAt()),
                  timestamp(command.observedAt()));
          requireOne(changed);
        });
  }

  private void update(
      SourcePlace source,
      UUID id,
      DetailItemWrite write,
      ItemDocument document,
      DetailItemSyncCommand command) {
    DetailItem item = write.item();
    DetailItemLineage lineage = write.pageLineage().lineage();
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () ->
            requireOne(
                jdbc.update(
                    """
                    update public.place_detail_items set title=?, sequence_no=?, attributes=?::jsonb,
                      payload_hash=?, source_snapshot_id=?, source_sweep_id=?, import_run_id=?, last_seen_at=?,
                      stale_at=null, tombstoned_at=null, updated_at=? where id=?
                    """,
                    item.title(),
                    item.sequenceNo(),
                    document.attributesJson(),
                    document.payloadHash(),
                    lineage.snapshotId(),
                    command.sweep().sweepId(),
                    lineage.importRunId(),
                    timestamp(command.observedAt()),
                    timestamp(command.observedAt()),
                    id)));
  }

  private void markStale(SourcePlace source, UUID id, DetailItemSyncCommand command) {
    lifecycleUpdate(source, id, command, false);
  }

  private void markTombstoned(SourcePlace source, UUID id, DetailItemSyncCommand command) {
    lifecycleUpdate(source, id, command, true);
  }

  private void lifecycleUpdate(
      SourcePlace source, UUID id, DetailItemSyncCommand command, boolean tombstone) {
    DetailItemLineage lineage = command.sweep().pages().getLast().lineage();
    writeProvenance(
        id,
        source.contentTypeId(),
        lineage,
        () -> {
          String sql =
              tombstone
                  ? "update public.place_detail_items set tombstoned_at=?, source_snapshot_id=?, source_sweep_id=?, import_run_id=?, updated_at=? where id=? and tombstoned_at is null"
                  : "update public.place_detail_items set stale_at=?, source_snapshot_id=?, source_sweep_id=?, import_run_id=?, updated_at=? where id=? and stale_at is null";
          requireOne(
              jdbc.update(
                  sql,
                  timestamp(command.observedAt()),
                  lineage.snapshotId(),
                  command.sweep().sweepId(),
                  lineage.importRunId(),
                  timestamp(command.observedAt()),
                  id));
        });
  }

  private void writeProvenance(
      UUID id, String contentTypeId, DetailItemLineage lineage, Runnable write) {
    provenanceWriter.write(
        new TourApiProvenanceCommand(
            "place_detail_items",
            id,
            lineage.operationKey(),
            contentTypeId,
            lineage.requestFingerprint(),
            lineage.snapshotId(),
            lineage.importRunId()),
        write);
  }

  private ItemDocument document(DetailItem item) throws JacksonException {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("schema", item.attributes().schema());
    attributes.put("version", item.attributes().version());
    attributes.put("fields", item.attributes().fields());
    String attributesJson = item.attributes().canonicalJson();
    List<Object> canonical = new ArrayList<>();
    canonical.add(item.itemType());
    canonical.add(item.sourceItemKey());
    canonical.add(item.title());
    canonical.add(item.sequenceNo());
    canonical.add(attributes);
    return new ItemDocument(attributesJson, sha256(objectMapper.writeValueAsString(canonical)));
  }

  private static UUID deterministicId(UUID placeId, DetailItem item) {
    String hex =
        sha256(placeId + "\u001f" + PROVIDER + "\u001f" + SERVICE + "\u001f" + scopedKey(item));
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

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  private static String scopedKey(DetailItem item) {
    return item.itemType() + '\u0000' + item.sourceItemKey();
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

  private static void requireOne(int changed) {
    if (changed != 1) throw DetailItemImportException.storageFailure();
  }

  private record SourcePlace(UUID placeId, String contentTypeId, String contentId) {}

  private record SourceSnapshot(UUID id, Instant fetchedAt, String payloadHash) {}

  private record LatestSweep(UUID id, String manifestHash, Instant fetchedAt) {}

  private record ExistingItem(
      UUID id,
      String itemType,
      String sourceItemKey,
      String title,
      int sequenceNo,
      String attributesJson,
      String payloadHash,
      UUID snapshotId,
      UUID sweepId,
      Instant staleAt,
      Instant tombstonedAt) {
    String scopedKey() {
      return itemType + '\u0000' + sourceItemKey;
    }

    boolean same(ItemDocument document, DetailItem item) {
      return payloadHash.equals(document.payloadHash())
          && Objects.equals(title, item.title())
          && sequenceNo == item.sequenceNo();
    }
  }

  private record ItemDocument(String attributesJson, String payloadHash) {}

  private static final class Counts {
    private int inserted;
    private int updated;
    private int skipped;
    private int staled;
    private int tombstoned;

    DetailItemSyncResult result() {
      return new DetailItemSyncResult(inserted, updated, skipped, staled, tombstoned);
    }
  }
}
