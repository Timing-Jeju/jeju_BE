package com.timingjeju.api.global.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.detailitem.DetailItem;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemAttributes;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemBatch;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemLineage;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemRepository;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSyncCommand;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcDetailItemRepositoryIntegrationTest {
  private static final UUID PLACE = UUID.fromString("28000000-0000-0000-0000-000000000001");
  private static final UUID LIST_RUN = UUID.fromString("28000000-0000-0000-0000-000000000002");
  private static final UUID LIST_SNAPSHOT = UUID.fromString("28000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");

  @Autowired DetailItemRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertLineage(LIST_RUN, LIST_SNAPSHOT, "areaBasedList2", "1".repeat(64), NOW);
    insertPlace();
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void 반복_item을_순서대로_upsert하고_같은_snapshot_replay는_row와_provenance를_중복하지_않는다() {
    LineageFixture lineage = lineage(4, NOW.plusSeconds(10));
    DetailItemSyncCommand command = command(lineage, List.of(item("20", 1), item("10", 2)));

    var first = repository.sync(command);
    var replay = repository.sync(command);

    assertThat(first.insertedCount()).isEqualTo(2);
    assertThat(replay.skippedCount()).isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "select source_item_key from public.place_detail_items order by sequence_no",
                String.class))
        .containsExactly("20", "10");
    assertThat(
            jdbc.queryForObject(
                "select attributes::text from public.place_detail_items where source_item_key='20'",
                String.class))
        .contains("schema", "tour-api.detailInfo2.info", "version", "fields", "infotext");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_detail_items'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void 새_snapshot에서_누락되면_stale_동일_snapshot_replay는_유지하고_다음_새_snapshot에서_tombstone한다() {
    LineageFixture initial = lineage(4, NOW.plusSeconds(10));
    repository.sync(command(initial, List.of(item("1", 1))));
    finishRun(initial.run());

    LineageFixture absent = lineage(5, NOW.plusSeconds(20));
    var staled = repository.sync(command(absent, List.of()));
    ItemState firstAbsence = state("1");
    var replay = repository.sync(command(absent, List.of()));
    ItemState replayed = state("1");
    finishRun(absent.run());

    LineageFixture absentAgain = lineage(6, NOW.plusSeconds(30));
    var tombstoned = repository.sync(command(absentAgain, List.of()));

    assertThat(staled.staledCount()).isEqualTo(1);
    assertThat(firstAbsence.staleAt()).isEqualTo(NOW.plusSeconds(20));
    assertThat(firstAbsence.tombstonedAt()).isNull();
    assertThat(replay.staledCount()).isZero();
    assertThat(replayed).isEqualTo(firstAbsence);
    assertThat(tombstoned.tombstonedCount()).isEqualTo(1);
    assertThat(state("1").tombstonedAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(jdbc.queryForObject("select count(*) from public.place_detail_items", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void place_content_type과_snapshot_run_fingerprint_lineage가_다르면_부분_row없이_rollback한다() {
    LineageFixture lineage = lineage(4, NOW.plusSeconds(10));

    assertThatThrownBy(
            () ->
                repository.sync(
                    new DetailItemSyncCommand(
                        "100",
                        "39",
                        new DetailItemBatch("100", "39", List.of(item("1", 1))),
                        lineage.lineage(),
                        NOW.plusSeconds(10))))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(jdbc.queryForObject("select count(*) from public.place_detail_items", Integer.class))
        .isZero();

    DetailItemLineage wrong =
        new DetailItemLineage("detailInfo2", "f".repeat(64), lineage.snapshot(), lineage.run());
    assertThatThrownBy(
            () ->
                repository.sync(
                    new DetailItemSyncCommand(
                        "100", "12", batch(List.of(item("1", 1))), wrong, NOW.plusSeconds(10))))
        .isInstanceOf(DetailItemImportException.class);
    assertThat(jdbc.queryForObject("select count(*) from public.place_detail_items", Integer.class))
        .isZero();
  }

  @Test
  void 최신_snapshot_뒤에_도착한_과거_snapshot과_empty_batch는_row와_lifecycle을_되돌리지_못한다() {
    LineageFixture newer = lineage(7, NOW.plusSeconds(20));
    repository.sync(command(newer, List.of(item("1", 2))));
    ItemState newestState = state("1");
    finishRun(newer.run());

    LineageFixture equalTimeDifferentSnapshot = lineage(13, NOW.plusSeconds(20));
    assertThatThrownBy(
            () -> repository.sync(command(equalTimeDifferentSnapshot, List.of(item("1", 2)))))
        .isInstanceOf(DetailItemImportException.class);
    finishRun(equalTimeDifferentSnapshot.run());

    LineageFixture older = lineage(8, NOW.plusSeconds(10));
    assertThatThrownBy(() -> repository.sync(command(older, List.of(item("1", 1)))))
        .isInstanceOf(DetailItemImportException.class);
    assertThatThrownBy(() -> repository.sync(command(older, List.of())))
        .isInstanceOf(DetailItemImportException.class);

    assertThat(state("1")).isEqualTo(newestState);
    assertThat(
            jdbc.queryForObject(
                "select sequence_no from public.place_detail_items where source_item_key='1'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_detail_items'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 같은_snapshot은_payload와_active_key가_같은_true_replay만_허용한다() {
    LineageFixture lineage = lineage(14, NOW.plusSeconds(10));
    repository.sync(command(lineage, List.of(item("1", 1))));
    ItemState original = state("1");

    assertThatThrownBy(() -> repository.sync(command(lineage, List.of(item("1", 2)))))
        .isInstanceOf(DetailItemImportException.class);
    assertThatThrownBy(() -> repository.sync(command(lineage, List.of())))
        .isInstanceOf(DetailItemImportException.class);

    assertThat(state("1")).isEqualTo(original);
    assertThat(
            jdbc.queryForObject(
                "select sequence_no from public.place_detail_items where source_item_key='1'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_detail_items'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 동일_content_snapshot을_두_transaction이_동시에_sync해도_한_row와_한_provenance만_남긴다() throws Exception {
    LineageFixture lineage = lineage(9, NOW.plusSeconds(10));
    DetailItemSyncCommand command = command(lineage, List.of(item("1", 1)));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> first = executor.submit(() -> concurrentSync(command, ready, start));
      Future<?> second = executor.submit(() -> concurrentSync(command, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(jdbc.queryForObject("select count(*) from public.place_detail_items", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_detail_items'",
                Integer.class))
        .isEqualTo(1);
    assertThat(state("1").snapshotId()).isEqualTo(lineage.snapshot());
  }

  @Test
  void newer와_older_transaction이_겹쳐도_최종_content_lifecycle_lineage는_newer로_결정된다() throws Exception {
    LineageFixture newer = lineage(10, NOW.plusSeconds(20));
    finishRun(newer.run());
    LineageFixture older = lineage(11, NOW.plusSeconds(10));
    finishRun(older.run());
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> newerResult =
          executor.submit(
              () -> concurrentSyncOutcome(command(newer, List.of(item("1", 2))), start, 0));
      Future<Boolean> olderResult =
          executor.submit(() -> concurrentSyncOutcome(command(older, List.of()), start, 75));
      start.countDown();
      assertThat(newerResult.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(olderResult.get(10, TimeUnit.SECONDS)).isFalse();
    }

    ItemState state = state("1");
    assertThat(state.snapshotId()).isEqualTo(newer.snapshot());
    assertThat(state.staleAt()).isNull();
    assertThat(state.tombstonedAt()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select sequence_no from public.place_detail_items where source_item_key='1'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_detail_items'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 저장된_attributes_JSON_UTF8_size는_항상_64KiB_이하다() {
    LineageFixture lineage = lineage(12, NOW.plusSeconds(10));
    String escapedMultibyte = "제주 \"인용\" \\ 경로\n".repeat(2_000);
    DetailItem large =
        new DetailItem(
            "info",
            "large",
            "큰 속성",
            1,
            new DetailItemAttributes(
                "tour-api.detailInfo2.info", 1, Map.of("infotext", escapedMultibyte)));

    repository.sync(command(lineage, List.of(large)));

    assertThat(
            jdbc.queryForObject(
                "select octet_length(attributes::text) from public.place_detail_items where source_item_key='large'",
                Integer.class))
        .isLessThanOrEqualTo(DetailItemAttributes.MAX_BYTES);
  }

  private DetailItemSyncCommand command(LineageFixture fixture, List<DetailItem> items) {
    return new DetailItemSyncCommand(
        "100", "12", batch(items), fixture.lineage(), fixture.fetchedAt());
  }

  private static DetailItemBatch batch(List<DetailItem> items) {
    return new DetailItemBatch("100", "12", items);
  }

  private static DetailItem item(String key, int sequence) {
    return new DetailItem(
        "info",
        key,
        "안내 " + key,
        sequence,
        new DetailItemAttributes("tour-api.detailInfo2.info", 1, Map.of("infotext", "본문 " + key)));
  }

  private LineageFixture lineage(int suffix, Instant fetchedAt) {
    UUID run = UUID.fromString("28000000-0000-0000-0000-" + String.format("%012d", suffix));
    UUID snapshot = UUID.fromString("28000000-0000-0000-0001-" + String.format("%012d", suffix));
    String fingerprint = Integer.toHexString(suffix).repeat(64);
    insertLineage(run, snapshot, "detailInfo2", fingerprint, fetchedAt);
    return new LineageFixture(
        run,
        snapshot,
        fingerprint,
        fetchedAt,
        new DetailItemLineage("detailInfo2", fingerprint, snapshot, run));
  }

  private void insertPlace() {
    jdbc.update(
        "insert into public.tour_places (id, external_place_id, content_id, content_type_id, name, normalized_name, category, region_code, address, location, source_provider, source_service, import_run_id, source_snapshot_id, last_seen_at, created_at, updated_at) values (?, '100', '100', '12', '관광지', '관광지', 'attraction', 'jeju', '제주', ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography, 'tour-api', 'KorService2', ?, ?, ?, ?, ?)",
        PLACE,
        LIST_RUN,
        LIST_SNAPSHOT,
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    jdbc.update(
        "insert into public.tour_place_sources (id, place_id, source_provider, source_service, external_id, content_type_id, source_snapshot_id, last_import_run_id, created_at, updated_at) values (?, ?, 'tour-api', 'KorService2', '100', '12', ?, ?, ?, ?)",
        UUID.randomUUID(),
        PLACE,
        LIST_SNAPSHOT,
        LIST_RUN,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void insertLineage(
      UUID run, UUID snapshot, String operation, String fingerprint, Instant fetchedAt) {
    jdbc.update(
        "insert into public.data_import_runs (id, source_kind, source_name, source_operation, data_version, status, started_at, parser_version, schema_version, sync_mode, scope_key, request_fingerprint, idempotency_key, source_provider, source_service) values (?, 'tour_api', 'fixture', ?, '2026', 'running', ?, 'detail-info-v1', 'schema-v1', 'full', 'content:100', ?, ?, 'tour-api', 'KorService2')",
        run,
        operation,
        Timestamp.from(fetchedAt),
        fingerprint,
        operation + "-28-" + run);
    jdbc.update(
        "insert into public.external_api_snapshots (id, import_run_id, source_provider, source_service, source_operation, scope_key, request_hash, page_key, fetched_at, parser_version, payload_hash, request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version, payload_format, initial_parse_status, parse_status, parsed_at) values (?, ?, 'tour-api', 'KorService2', ?, 'content:100', ?, '1', ?, 'detail-info-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)",
        snapshot,
        run,
        operation,
        fingerprint,
        Timestamp.from(fetchedAt),
        "e".repeat(64),
        Timestamp.from(fetchedAt));
  }

  private void finishRun(UUID run) {
    jdbc.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(40)),
        run);
  }

  private ItemState state(String key) {
    return jdbc.queryForObject(
        "select stale_at, tombstoned_at, source_snapshot_id from public.place_detail_items where source_item_key=?",
        (rs, row) ->
            new ItemState(
                instant(rs.getTimestamp("stale_at")),
                instant(rs.getTimestamp("tombstoned_at")),
                rs.getObject("source_snapshot_id", UUID.class)),
        key);
  }

  private void concurrentSync(
      DetailItemSyncCommand command, CountDownLatch ready, CountDownLatch start) {
    try {
      ready.countDown();
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("동시 실행 시작 latch timeout");
      }
      repository.sync(command);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private boolean concurrentSyncOutcome(
      DetailItemSyncCommand command, CountDownLatch start, long delayMillis) {
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("동시 실행 시작 latch timeout");
      }
      if (delayMillis > 0) {
        Thread.sleep(delayMillis);
      }
      repository.sync(command);
      return true;
    } catch (DetailItemImportException expectedOlderRejection) {
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private void clean() {
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.place_detail_items");
    jdbc.update("delete from public.tour_place_sources");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private record LineageFixture(
      UUID run, UUID snapshot, String fingerprint, Instant fetchedAt, DetailItemLineage lineage) {}

  private record ItemState(Instant staleAt, Instant tombstonedAt, UUID snapshotId) {}
}
