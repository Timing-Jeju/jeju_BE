package com.timingjeju.api.global.tourapi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
import com.timingjeju.api.application.importing.ImportCheckpointRepository;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.application.tourapi.sync.IncrementalPlaceRepository;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitCommand;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitter;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCursor;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncLineage;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncPageLineage;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncStorageException;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncWrite;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncAction;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncChange;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
@SpringBootTest(properties = "timing-jeju.test.context=incremental-sync")
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class TransactionalIncrementalSyncCommitterIntegrationTest {
  private static final Instant BASE = Instant.parse("2026-08-16T01:00:00Z");
  private static final String HASH =
      "3030303030303030303030303030303030303030303030303030303030303030";
  private static final ImportRunScope SCOPE =
      new ImportRunScope("tour-api", "KorService2", "areaBasedSyncList2", "jeju");

  @Autowired private IncrementalPlaceRepository repository;
  @Autowired private IncrementalSyncCommitter committer;
  @Autowired private ImportCheckpointRepository checkpoints;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertCheckpoint();
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void normalized_run_checkpoint를_한_transaction으로_commit한다() {
    Fixture fixture = fixture(1, HASH, BASE);

    var result = committer.commit(command(fixture, 0, upsert("100", "성산일출봉", BASE), BASE));

    assertThat(result.checkpointVersion()).isEqualTo(1);
    assertThat(result.counts().insertedCount()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from public.data_import_runs where id=?",
                String.class,
                fixture.run()))
        .isEqualTo("succeeded");
    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("성산일출봉");
    assertThat(checkpoints.find(SCOPE))
        .get()
        .satisfies(
            value -> {
              assertThat(value.version()).isEqualTo(1);
              assertThat(value.lastSucceededRunId()).isEqualTo(fixture.run());
              assertThat(value.checkpoint()).containsEntry("modifiedTime", BASE.toString());
            });
  }

  @Test
  void newer_update는_같은_UUID를_유지하고_새_snapshot_run_lineage와_값을_저장한다() {
    Fixture add = fixture(1, HASH, BASE);
    committer.commit(command(add, 0, upsert("100", "이전 장소", BASE), BASE));
    UUID placeId = jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class);
    Fixture update = fixture(2, nextHash(2), BASE.plusSeconds(10));

    var result =
        committer.commit(
            command(update, 1, upsert("100", "변경 장소", BASE.plusSeconds(10)), BASE.plusSeconds(10)));

    assertThat(result.counts().updatedCount()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(placeId);
    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("변경 장소");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_places", UUID.class))
        .isEqualTo(update.snapshot());
    assertThat(
            jdbcTemplate.queryForObject("select import_run_id from public.tour_places", UUID.class))
        .isEqualTo(update.run());
  }

  @Test
  void stale_CAS_writer는_SQLSTATE_40001_domain으로_normalized_run_checkpoint를_전부_rollback한다() {
    Fixture first = fixture(1, HASH, BASE);
    committer.commit(command(first, 0, upsert("100", "최신 장소", BASE), BASE));
    Fixture stale = fixture(2, nextHash(2), BASE.plusSeconds(10));

    assertThatThrownBy(
            () ->
                committer.commit(
                    command(
                        stale,
                        0,
                        upsert("100", "덮어쓰면 안 됨", BASE.plusSeconds(10)),
                        BASE.plusSeconds(10))))
        .isInstanceOf(ImportCheckpointException.class)
        .satisfies(
            failure -> {
              ImportCheckpointException checkpoint = (ImportCheckpointException) failure;
              assertThat(checkpoint.code()).isEqualTo(ImportCheckpointError.STALE_VERSION);
              assertThat(checkpoint.retryable()).isTrue();
            });

    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("최신 장소");
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, stale.run()))
        .isEqualTo("running");
    assertThat(checkpoints.find(SCOPE))
        .get()
        .satisfies(
            value -> {
              assertThat(value.version()).isEqualTo(1);
              assertThat(value.lastSucceededRunId()).isEqualTo(first.run());
            });
  }

  @Test
  void delete는_stale에서_tombstone으로_두_단계_전이하고_hard_delete하지_않는다() {
    Fixture add = fixture(1, HASH, BASE);
    committer.commit(command(add, 0, upsert("100", "장소", BASE), BASE));
    Fixture stale = fixture(2, nextHash(2), BASE.plusSeconds(10));
    committer.commit(command(stale, 1, delete("100", BASE.plusSeconds(10)), BASE.plusSeconds(10)));

    assertThat(jdbcTemplate.queryForObject("select stale from public.tour_places", Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select stale_at is not null and tombstoned_at is null from public.tour_place_sources",
                Boolean.class))
        .isTrue();

    Fixture tombstone = fixture(3, nextHash(3), BASE.plusSeconds(20));
    var result =
        committer.commit(
            command(tombstone, 2, delete("100", BASE.plusSeconds(20)), BASE.plusSeconds(20)));

    assertThat(result.counts().deletedCount()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select tombstoned_at is not null from public.tour_place_sources", Boolean.class))
        .isTrue();
  }

  @Test
  void older와_same_cursor_true_replay는_최신행과_lineage를_바꾸지_않고_equal_different는_rollback한다() {
    Fixture current = fixture(1, HASH, BASE.plusSeconds(20));
    repository.apply(List.of(write(upsert("100", "최신", BASE.plusSeconds(20)), current)));
    finishFixture(current);
    UUID snapshot =
        jdbcTemplate.queryForObject(
            "select source_snapshot_id from public.tour_places", UUID.class);
    Fixture older = fixture(2, nextHash(2), BASE.plusSeconds(10));
    assertThat(
            repository
                .apply(List.of(write(upsert("100", "과거", BASE.plusSeconds(10)), older)))
                .skipped())
        .isEqualTo(1);
    finishFixture(older);
    Fixture replay = fixture(3, nextHash(3), BASE.plusSeconds(30));
    assertThat(
            repository
                .apply(List.of(write(upsert("100", "최신", BASE.plusSeconds(20)), replay)))
                .skipped())
        .isEqualTo(1);
    finishFixture(replay);
    Fixture collision = fixture(4, nextHash(4), BASE.plusSeconds(40));

    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_places", UUID.class))
        .isEqualTo(snapshot);
    assertThatThrownBy(
            () ->
                repository.apply(
                    List.of(write(upsert("100", "충돌", BASE.plusSeconds(20)), collision))))
        .isInstanceOf(IncrementalSyncStorageException.class);
    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("최신");
  }

  @Test
  void snapshot_run_fingerprint_lineage가_불일치하면_batch_전체를_rollback한다() {
    Fixture valid = fixture(1, HASH, BASE);
    IncrementalSyncWrite validWrite = write(upsert("100", "첫째", BASE), valid);
    IncrementalSyncWrite invalidWrite =
        new IncrementalSyncWrite(
            upsert("200", "둘째", BASE),
            BASE,
            new IncrementalSyncLineage(
                "areaBasedSyncList2", nextHash(9), valid.snapshot(), valid.run()));

    assertThatThrownBy(() -> repository.apply(List.of(validWrite, invalidWrite)))
        .isInstanceOf(IncrementalSyncStorageException.class);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isZero();
  }

  private IncrementalSyncCommitCommand command(
      Fixture fixture, long version, PlaceSyncChange change, Instant cursor) {
    return new IncrementalSyncCommitCommand(
        fixture.lease(),
        version,
        new IncrementalSyncCursor(cursor.minusSeconds(1)),
        new IncrementalSyncCursor(cursor),
        fixture.fetchedAt(),
        List.of(write(change, fixture)),
        List.of(
            new IncrementalSyncPageLineage(
                1, 1, fixture.payloadHash(), fixture.fetchedAt(), fixture.lineage())));
  }

  private IncrementalSyncWrite write(PlaceSyncChange change, Fixture fixture) {
    return new IncrementalSyncWrite(change, fixture.fetchedAt(), fixture.lineage());
  }

  private static PlaceSyncChange upsert(String id, String title, Instant modified) {
    TourPlace place =
        new TourPlace(
            id,
            "12",
            title,
            126.94,
            33.45,
            "제주",
            "제주시",
            "https://images.example.test/place.jpg",
            "https://images.example.test/thumb.jpg",
            "50",
            "50110",
            "VE",
            "VE01",
            "VE0101",
            modified);
    return new PlaceSyncChange(id, "12", modified, PlaceSyncAction.UPSERT, place);
  }

  private static PlaceSyncChange delete(String id, Instant modified) {
    return new PlaceSyncChange(id, "12", modified, PlaceSyncAction.DELETE, null);
  }

  private Fixture fixture(int sequence, String hash, Instant fetchedAt) {
    UUID run = UUID.fromString("30000000-0000-0000-0000-%012d".formatted(sequence));
    UUID snapshot = UUID.fromString("31000000-0000-0000-0000-%012d".formatted(sequence));
    UUID owner = UUID.fromString("32000000-0000-0000-0000-%012d".formatted(sequence));
    String payloadHash = nextHash(sequence + 20);
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service, owner_token, fencing_token
        ) values (?, 'tour_api', 'issue-30', 'areaBasedSyncList2', '2026', 'running', ?,
                  'incremental-sync-v1', 'schema-v1', 'incremental', 'jeju', ?, ?,
                  'tour-api', 'KorService2', ?, 1)
        """,
        run,
        Timestamp.from(fetchedAt),
        hash,
        "issue-30-" + sequence,
        owner);
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'tour-api', 'KorService2', 'areaBasedSyncList2', 'jeju', ?, '1', ?,
                  'incremental-sync-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1',
                  'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        hash,
        Timestamp.from(fetchedAt),
        payloadHash,
        Timestamp.from(fetchedAt));
    return new Fixture(run, snapshot, owner, hash, payloadHash, fetchedAt);
  }

  private void insertCheckpoint() {
    jdbcTemplate.update(
        """
        insert into public.data_import_checkpoints(
          source_provider,source_service,source_operation,scope_key,checkpoint,source_watermark_at)
        values ('tour-api','KorService2','areaBasedSyncList2','jeju',
                '{"modifiedTime":"1970-01-01T00:00:00Z"}'::jsonb,
                '1970-01-01T00:00:00Z')
        """);
  }

  private void finishFixture(Fixture fixture) {
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(fixture.fetchedAt().plusSeconds(1)),
        fixture.run());
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.tour_place_sources");
    jdbcTemplate.update("delete from public.tour_places");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.execute(
        "alter table public.data_import_checkpoints disable trigger trg_data_import_checkpoints_no_delete");
    jdbcTemplate.update(
        "delete from public.data_import_checkpoints where source_operation='areaBasedSyncList2'");
    jdbcTemplate.execute(
        "alter table public.data_import_checkpoints enable trigger trg_data_import_checkpoints_no_delete");
    jdbcTemplate.update(
        "delete from public.data_import_runs where source_operation='areaBasedSyncList2'");
  }

  private static String nextHash(int number) {
    return "%064x".formatted(number);
  }

  private record Fixture(
      UUID run, UUID snapshot, UUID owner, String hash, String payloadHash, Instant fetchedAt) {
    private ImportRunLease lease() {
      return new ImportRunLease(run, owner, 1);
    }

    private IncrementalSyncLineage lineage() {
      return new IncrementalSyncLineage("areaBasedSyncList2", hash, snapshot, run);
    }
  }
}
