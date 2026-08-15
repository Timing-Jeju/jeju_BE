package com.timingjeju.api.global.tourapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.place.PlaceLineage;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import com.timingjeju.api.application.tourapi.place.TourPlace;
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
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcPlaceListRepositoryIntegrationTest {

  private static final UUID RUN = UUID.fromString("26000000-0000-0000-0000-000000000001");
  private static final UUID SNAPSHOT = UUID.fromString("26000000-0000-0000-0000-000000000002");
  private static final String HASH =
      "2626262626262626262626262626262626262626262626262626262626262626";
  private static final Instant NOW = Instant.parse("2026-08-16T03:00:00Z");

  @Autowired private PlaceListRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshot(RUN, SNAPSHOT, HASH);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void contentid_natural_key로_place와_source를_한번_저장하고_공통_provenance를_각각_남긴다() {
    var command = command(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW);

    var first = repository.upsert(command);
    UUID placeId = jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class);
    UUID sourceId =
        jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class);
    var replay = repository.upsert(command);

    assertThat(first.inserted()).isEqualTo(1);
    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(placeId);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class))
        .isEqualTo(sourceId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "select normalized_entity_type from public.tour_api_operation_provenance",
                String.class))
        .containsExactlyInAnyOrder("tour_place_sources", "tour_places");
    assertThat(
            jdbcTemplate.queryForObject(
                "select l_dong_regn_cd from public.tour_place_sources", String.class))
        .isEqualTo("50");
    assertThat(
            jdbcTemplate.queryForObject(
                "select lcls_systm3 from public.tour_place_sources", String.class))
        .isEqualTo("VE0101");
  }

  @Test
  void 한_batch의_duplicate_contentid도_장소행을_중복생성하지_않는다() {
    PlaceListWrite write = write(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW);

    var result = repository.upsert(new PlaceListUpsertCommand(List.of(write, write)));

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void snapshot_run_fingerprint가_불일치하면_place_source_provenance를_모두_rollback한다() {
    String mismatched = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    assertThatThrownBy(
            () -> repository.upsert(command(place("100", "성산일출봉"), RUN, SNAPSHOT, mismatched, NOW)))
        .isInstanceOf(PlaceListImportException.class);

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isZero();
  }

  @Test
  void 새_snapshot은_같은_UUID에_변경값과_lineage를_update하고_snapshot별_provenance를_보존한다() {
    repository.upsert(command(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW));
    UUID placeId = jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class);
    UUID sourceId =
        jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class);
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(1)),
        RUN);
    UUID nextRun = UUID.fromString("26000000-0000-0000-0000-000000000003");
    UUID nextSnapshot = UUID.fromString("26000000-0000-0000-0000-000000000004");
    String nextHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    insertRunAndSnapshot(nextRun, nextSnapshot, nextHash);

    var updated =
        repository.upsert(
            command(place("100", "성산봉"), nextRun, nextSnapshot, nextHash, NOW.plusSeconds(2)));

    assertThat(updated.updated()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(placeId);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class))
        .isEqualTo(sourceId);
    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("성산봉");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_places", UUID.class))
        .isEqualTo(nextSnapshot);
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_place_sources", UUID.class))
        .isEqualTo(nextSnapshot);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(4);
  }

  private static PlaceListUpsertCommand command(
      TourPlace place, UUID run, UUID snapshot, String hash, Instant seenAt) {
    return new PlaceListUpsertCommand(List.of(write(place, run, snapshot, hash, seenAt)));
  }

  private static PlaceListWrite write(
      TourPlace place, UUID run, UUID snapshot, String hash, Instant seenAt) {
    return new PlaceListWrite(
        place, seenAt, new PlaceLineage("areaBasedList2", hash, snapshot, run));
  }

  private static TourPlace place(String contentId, String title) {
    return new TourPlace(
        contentId,
        "12",
        title,
        126.941516,
        33.458111,
        "제주특별자치도 서귀포시",
        "성산읍",
        "https://images.example.test/place.jpg",
        "https://images.example.test/thumb.jpg",
        "50",
        "50130",
        "VE",
        "VE01",
        "VE0101",
        NOW.minusSeconds(60));
  }

  private void insertRunAndSnapshot(UUID run, UUID snapshot, String hash) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tour_api', 'fixture', 'areaBasedList2', '2026', 'running', ?,
                  'place-list-v1', 'schema-v1', 'full', 'jeju', ?, ?, 'tour-api', 'KorService2')
        """,
        run,
        Timestamp.from(NOW),
        hash,
        "issue-26-" + run);
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'tour-api', 'KorService2', 'areaBasedList2', 'jeju', ?, '1', ?,
                  'place-list-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1',
                  'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        hash,
        Timestamp.from(NOW),
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.tour_place_sources");
    jdbcTemplate.update("delete from public.tour_places");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }
}
