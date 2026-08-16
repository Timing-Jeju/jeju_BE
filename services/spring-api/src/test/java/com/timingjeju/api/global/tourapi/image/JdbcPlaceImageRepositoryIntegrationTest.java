package com.timingjeju.api.global.tourapi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.image.PlaceImage;
import com.timingjeju.api.application.tourapi.image.PlaceImageBatch;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import com.timingjeju.api.application.tourapi.image.PlaceImageLineage;
import com.timingjeju.api.application.tourapi.image.PlaceImagePageLineage;
import com.timingjeju.api.application.tourapi.image.PlaceImageRepository;
import com.timingjeju.api.application.tourapi.image.PlaceImageSweep;
import com.timingjeju.api.application.tourapi.image.PlaceImageSyncCommand;
import com.timingjeju.api.application.tourapi.image.PlaceImageWrite;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class JdbcPlaceImageRepositoryIntegrationTest {
  private static final UUID PLACE = UUID.fromString("29000000-0000-0000-0000-000000000001");
  private static final UUID LIST_RUN = UUID.fromString("29000000-0000-0000-0000-000000000002");
  private static final UUID LIST_SNAPSHOT = UUID.fromString("29000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-08-16T09:00:00Z");
  @Autowired PlaceImageRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertLineage(
        LIST_RUN, LIST_SNAPSHOT, "areaBasedList2", "1".repeat(64), NOW, "1", "e".repeat(64));
    insertPlace();
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void source_id는_URL변경에도_같은_row를_update하고_true_replay는_불변이다() {
    Fixture first = fixture(1, NOW.plusSeconds(10));
    repository.sync(command(first, List.of(image("ID-1", "https://img.test/old.jpg", 1))));
    finish(first.run());
    Fixture second = fixture(2, NOW.plusSeconds(20));
    var updated =
        repository.sync(command(second, List.of(image("ID-1", "https://img.test/new.jpg", 1))));
    var replayed =
        repository.sync(command(second, List.of(image("ID-1", "https://img.test/new.jpg", 1))));

    assertThat(updated.updatedCount()).isEqualTo(1);
    assertThat(replayed.skippedCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select image_url from public.place_images", String.class))
        .isEqualTo("https://img.test/new.jpg");
    assertThat(
            jdbc.queryForObject(
                "select source_url_key=public.source_identity_digest(place_id::text,source_provider,source_service,image_url) from public.place_images",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where normalized_entity_type='place_images'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void URL_fallback은_누락시_stale_다음_complete_sweep에서_tombstone하고_hard_delete하지_않는다() {
    Fixture first = fixture(3, NOW.plusSeconds(10));
    repository.sync(command(first, List.of(image(null, "https://img.test/a.jpg", 1))));
    finish(first.run());
    Fixture absent = fixture(4, NOW.plusSeconds(20));
    assertThat(repository.sync(command(absent, List.of())).staledCount()).isEqualTo(1);
    finish(absent.run());
    Fixture absentAgain = fixture(5, NOW.plusSeconds(30));
    assertThat(repository.sync(command(absentAgain, List.of())).tombstonedCount()).isEqualTo(1);

    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select stale_at is not null and tombstoned_at is not null from public.place_images",
                Boolean.class))
        .isTrue();
  }

  @Test
  void source_id가_없는_URL변경은_새_identity를_insert하고_기존_URL을_stale한다() {
    Fixture first = fixture(11, NOW.plusSeconds(10));
    repository.sync(command(first, List.of(image(null, "https://img.test/old-url.jpg", 1))));
    finish(first.run());
    Fixture changed = fixture(12, NOW.plusSeconds(20));

    var result =
        repository.sync(command(changed, List.of(image(null, "https://img.test/new-url.jpg", 1))));
    var replay =
        repository.sync(command(changed, List.of(image(null, "https://img.test/new-url.jpg", 1))));

    assertThat(result.insertedCount()).isEqualTo(1);
    assertThat(result.staledCount()).isEqualTo(1);
    assertThat(replay.skippedCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select stale_at is not null from public.place_images where image_url='https://img.test/old-url.jpg'",
                Boolean.class))
        .isTrue();
  }

  @Test
  void 동일_URL에_source_id가_추가되면_기존_URL_fallback_row를_enrich한다() {
    Fixture first = fixture(15, NOW.plusSeconds(10));
    repository.sync(command(first, List.of(image(null, "https://img.test/enrich.jpg", 1))));
    UUID originalId = jdbc.queryForObject("select id from public.place_images", UUID.class);
    finish(first.run());
    Fixture enriched = fixture(16, NOW.plusSeconds(20));

    var result =
        repository.sync(
            command(enriched, List.of(image("SOURCE-15", "https://img.test/enrich.jpg", 1))));

    assertThat(result.updatedCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select id from public.place_images", UUID.class))
        .isEqualTo(originalId);
    assertThat(jdbc.queryForObject("select source_image_id from public.place_images", String.class))
        .isEqualTo("SOURCE-15");
  }

  @Test
  void 최신_empty_sweep은_row가_없어도_과거_nonempty를_거부한다() {
    Fixture newer = fixture(6, NOW.plusSeconds(20));
    repository.sync(command(newer, List.of()));
    finish(newer.run());
    Fixture older = fixture(7, NOW.plusSeconds(10));

    assertThatThrownBy(
            () ->
                repository.sync(
                    command(older, List.of(image("old", "https://img.test/old.jpg", 1)))))
        .isInstanceOf(PlaceImageImportException.class);
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_place_image_sweeps", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void multi_page_row는_각_page_snapshot과_같은_sweep_pair로_연결된다() {
    Fixture first = fixture(8, NOW.plusSeconds(10));
    UUID secondSnapshot = snapshotUuid(208);
    String secondFingerprint = String.format("%064x", 108);
    insertSnapshot(
        first.run(),
        secondSnapshot,
        "detailImage2",
        secondFingerprint,
        "2",
        "d".repeat(64),
        NOW.plusSeconds(11));
    PlaceImagePageLineage page1 = page(first, 1, 1, "e".repeat(64));
    PlaceImageLineage secondLineage =
        new PlaceImageLineage("detailImage2", secondFingerprint, secondSnapshot, first.run());
    PlaceImagePageLineage page2 =
        new PlaceImagePageLineage(2, 1, "d".repeat(64), NOW.plusSeconds(11), secondLineage);
    PlaceImageSweep sweep = new PlaceImageSweep(first.run(), 2, List.of(page1, page2));
    PlaceImageBatch batch =
        new PlaceImageBatch(
            "100",
            "12",
            List.of(
                new PlaceImageWrite(image("p1", "https://img.test/1.jpg", 1), page1),
                new PlaceImageWrite(image("p2", "https://img.test/2.jpg", 2), page2)));

    repository.sync(new PlaceImageSyncCommand("100", "12", batch, sweep, NOW.plusSeconds(11)));

    assertThat(
            jdbc.queryForList(
                "select source_snapshot_id from public.place_images order by display_order",
                UUID.class))
        .containsExactly(first.snapshot(), secondSnapshot);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.place_images image join public.tour_api_place_image_sweep_pages page on page.sweep_id=image.source_sweep_id and page.source_snapshot_id=image.source_snapshot_id",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void place_contentType_snapshot_fingerprint_mismatch는_sweep과_row를_rollback한다() {
    Fixture fixture = fixture(9, NOW.plusSeconds(10));
    PlaceImageLineage wrong =
        new PlaceImageLineage("detailImage2", "f".repeat(64), fixture.snapshot(), fixture.run());
    PlaceImagePageLineage page =
        new PlaceImagePageLineage(1, 1, "e".repeat(64), fixture.fetchedAt(), wrong);
    PlaceImageSweep sweep = new PlaceImageSweep(fixture.run(), 1, List.of(page));
    PlaceImageBatch batch =
        new PlaceImageBatch(
            "100",
            "12",
            List.of(new PlaceImageWrite(image("bad", "https://img.test/bad.jpg", 1), page)));

    assertThatThrownBy(
            () ->
                repository.sync(
                    new PlaceImageSyncCommand("100", "12", batch, sweep, fixture.fetchedAt())))
        .isInstanceOf(PlaceImageImportException.class);
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_place_image_sweeps", Integer.class))
        .isZero();
  }

  @Test
  void 동일_content를_동시에_sync해도_한_row와_한_sweep만_남는다() throws Exception {
    Fixture fixture = fixture(10, NOW.plusSeconds(10));
    PlaceImageSyncCommand command =
        command(fixture, List.of(image("same", "https://img.test/same.jpg", 1)));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> a = executor.submit(() -> awaitAndSync(start, command));
      Future<?> b = executor.submit(() -> awaitAndSync(start, command));
      start.countDown();
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_place_image_sweeps", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void newer_empty와_older_nonempty가_겹쳐도_complete_empty_fence가_최종상태다() throws Exception {
    Fixture newer = fixture(13, NOW.plusSeconds(20));
    finish(newer.run());
    Fixture older = fixture(14, NOW.plusSeconds(10));
    finish(older.run());
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> accepted =
          executor.submit(() -> concurrentOutcome(start, command(newer, List.of()), 0));
      Future<Boolean> rejected =
          executor.submit(
              () ->
                  concurrentOutcome(
                      start,
                      command(
                          older, List.of(image("outdated", "https://img.test/outdated.jpg", 1))),
                      75));
      start.countDown();
      assertThat(accepted.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(rejected.get(10, TimeUnit.SECONDS)).isFalse();
    }
    assertThat(jdbc.queryForObject("select count(*) from public.place_images", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_place_image_sweeps", Integer.class))
        .isEqualTo(1);
  }

  private PlaceImageSyncCommand command(Fixture fixture, List<PlaceImage> images) {
    PlaceImagePageLineage page = page(fixture, 1, images.size(), "e".repeat(64));
    PlaceImageSweep sweep = new PlaceImageSweep(fixture.run(), images.size(), List.of(page));
    PlaceImageBatch batch =
        new PlaceImageBatch(
            "100", "12", images.stream().map(i -> new PlaceImageWrite(i, page)).toList());
    return new PlaceImageSyncCommand("100", "12", batch, sweep, fixture.fetchedAt());
  }

  private static PlaceImage image(String id, String url, int order) {
    return new PlaceImage(
        id, url, "https://img.test/thumb.jpg", "제주 이미지", "Type1", "한국관광공사", "공공누리", order);
  }

  private static PlaceImagePageLineage page(Fixture f, int pageNo, int count, String hash) {
    return new PlaceImagePageLineage(pageNo, count, hash, f.fetchedAt(), f.lineage());
  }

  private Fixture fixture(int suffix, Instant fetchedAt) {
    UUID run = uuid(1000 + suffix);
    UUID snapshot = snapshotUuid(100 + suffix);
    String fingerprint = String.format("%064x", suffix);
    insertLineage(run, snapshot, "detailImage2", fingerprint, fetchedAt, "1", "e".repeat(64));
    return new Fixture(
        run,
        snapshot,
        fingerprint,
        fetchedAt,
        new PlaceImageLineage("detailImage2", fingerprint, snapshot, run));
  }

  private void insertPlace() {
    jdbc.update(
        "insert into public.tour_places (id,external_place_id,content_id,content_type_id,name,normalized_name,category,region_code,address,location,source_provider,source_service,import_run_id,source_snapshot_id,last_seen_at,created_at,updated_at) values (?,'100','100','12','관광지','관광지','attraction','jeju','제주',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'tour-api','KorService2',?,?,?,?,?)",
        PLACE,
        LIST_RUN,
        LIST_SNAPSHOT,
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    jdbc.update(
        "insert into public.tour_place_sources (id,place_id,source_provider,source_service,external_id,content_type_id,source_snapshot_id,last_import_run_id,created_at,updated_at) values (?,?, 'tour-api','KorService2','100','12',?,?,?,?)",
        UUID.randomUUID(),
        PLACE,
        LIST_SNAPSHOT,
        LIST_RUN,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void insertLineage(
      UUID run,
      UUID snapshot,
      String operation,
      String fingerprint,
      Instant fetchedAt,
      String page,
      String hash) {
    jdbc.update(
        "insert into public.data_import_runs (id,source_kind,source_name,source_operation,data_version,status,started_at,parser_version,schema_version,sync_mode,scope_key,request_fingerprint,idempotency_key,source_provider,source_service) values (?,'tour_api','fixture',?,'2026','running',?,'detail-image-v1','schema-v1','full','content:100',?,?, 'tour-api','KorService2')",
        run,
        operation,
        Timestamp.from(fetchedAt),
        fingerprint,
        operation + "-29-" + run);
    insertSnapshot(run, snapshot, operation, fingerprint, page, hash, fetchedAt);
  }

  private void insertSnapshot(
      UUID run,
      UUID snapshot,
      String operation,
      String fingerprint,
      String page,
      String hash,
      Instant fetchedAt) {
    jdbc.update(
        "insert into public.external_api_snapshots (id,import_run_id,source_provider,source_service,source_operation,scope_key,request_hash,page_key,fetched_at,parser_version,payload_hash,request_metadata_redacted,raw_payload,payload_size_bytes,redaction_version,payload_format,initial_parse_status,parse_status,parsed_at) values (?,?,'tour-api','KorService2',?,'content:100',?,?,?,'detail-image-v1',?,'{}'::jsonb,'{}'::jsonb,2,'test-v1','JSON','parsed','parsed',?)",
        snapshot,
        run,
        operation,
        fingerprint,
        page,
        Timestamp.from(fetchedAt),
        hash,
        Timestamp.from(fetchedAt));
  }

  private void finish(UUID run) {
    jdbc.update(
        "update public.data_import_runs set status='succeeded',finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(60)),
        run);
  }

  private void awaitAndSync(CountDownLatch start, PlaceImageSyncCommand command) {
    try {
      start.await(5, TimeUnit.SECONDS);
      repository.sync(command);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private boolean concurrentOutcome(
      CountDownLatch start, PlaceImageSyncCommand command, long delayMillis) {
    try {
      start.await(5, TimeUnit.SECONDS);
      if (delayMillis > 0) Thread.sleep(delayMillis);
      repository.sync(command);
      return true;
    } catch (PlaceImageImportException expected) {
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private void clean() {
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.place_images");
    jdbc.update("delete from public.tour_api_place_image_sweep_pages");
    jdbc.update("delete from public.tour_api_place_image_sweeps");
    jdbc.update("delete from public.tour_place_sources");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("29000000-0000-0000-0000-" + String.format("%012d", suffix));
  }

  private static UUID snapshotUuid(int suffix) {
    return UUID.fromString("29000000-0000-0000-0001-" + String.format("%012d", suffix));
  }

  private record Fixture(
      UUID run, UUID snapshot, String fingerprint, Instant fetchedAt, PlaceImageLineage lineage) {}
}
