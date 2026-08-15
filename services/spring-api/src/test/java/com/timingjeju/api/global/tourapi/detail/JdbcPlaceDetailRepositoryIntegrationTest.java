package com.timingjeju.api.global.tourapi.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.detail.DetailLineage;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailCommon;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailIntro;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertCommand;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
class JdbcPlaceDetailRepositoryIntegrationTest {
  private static final UUID PLACE = UUID.fromString("27000000-0000-0000-0000-000000000001");
  private static final UUID LIST_RUN = UUID.fromString("27000000-0000-0000-0000-000000000002");
  private static final UUID LIST_SNAPSHOT = UUID.fromString("27000000-0000-0000-0000-000000000003");
  private static final UUID COMMON_RUN = UUID.fromString("27000000-0000-0000-0000-000000000004");
  private static final UUID COMMON_SNAPSHOT =
      UUID.fromString("27000000-0000-0000-0000-000000000005");
  private static final UUID INTRO_RUN = UUID.fromString("27000000-0000-0000-0000-000000000006");
  private static final UUID INTRO_SNAPSHOT =
      UUID.fromString("27000000-0000-0000-0000-000000000007");
  private static final UUID NEXT_COMMON_RUN =
      UUID.fromString("27000000-0000-0000-0000-000000000008");
  private static final UUID NEXT_COMMON_SNAPSHOT =
      UUID.fromString("27000000-0000-0000-0000-000000000009");
  private static final UUID NEXT_INTRO_RUN =
      UUID.fromString("27000000-0000-0000-0000-000000000010");
  private static final UUID NEXT_INTRO_SNAPSHOT =
      UUID.fromString("27000000-0000-0000-0000-000000000011");
  private static final String LIST_HASH = "1".repeat(64);
  private static final String COMMON_HASH = "2".repeat(64);
  private static final String INTRO_HASH = "3".repeat(64);
  private static final String NEXT_COMMON_HASH = "4".repeat(64);
  private static final String NEXT_INTRO_HASH = "5".repeat(64);
  private static final Instant NOW = Instant.parse("2026-08-16T06:00:00Z");

  @Autowired PlaceDetailRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertRunSnapshot(LIST_RUN, LIST_SNAPSHOT, "areaBasedList2", LIST_HASH);
    insertPlace();
    insertRunSnapshot(COMMON_RUN, COMMON_SNAPSHOT, "detailCommon2", COMMON_HASH);
    insertRunSnapshot(INTRO_RUN, INTRO_SNAPSHOT, "detailIntro2", INTRO_HASH);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void place_details를_한번_upsert하고_common_intro_operation별_provenance와_raw를_보존한다() {
    var first = repository.upsert(command(COMMON_HASH));
    DetailState beforeReplay = state();
    var replay = repository.upsert(command(COMMON_HASH));

    assertThat(first.inserted()).isTrue();
    assertThat(replay.skipped()).isTrue();
    assertThat(state()).isEqualTo(beforeReplay);
    assertThat(
            jdbc.queryForObject(
                "select overview from public.tour_places where id=?", String.class, PLACE))
        .isEqualTo("안전한 개요");
    String attributes =
        jdbc.queryForObject(
            "select intro_attributes::text from public.place_details where place_id=?",
            String.class,
            PLACE);
    assertThat(attributes).contains("overviewRaw", "<p>안전한 개요</p>", "firstmenu", "갈치조림");
    assertThat(
            jdbc.queryForList(
                "select operation_key from public.tour_api_operation_provenance where normalized_entity_type='place_details'",
                String.class))
        .containsExactlyInAnyOrder("detailCommon2", "detailIntro2");
    assertThat(jdbc.queryForObject("select count(*) from public.place_details", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void lineage_fingerprint가_다르면_place_details와_overview와_provenance를_모두_rollback한다() {
    assertThatThrownBy(() -> repository.upsert(command("a".repeat(64))))
        .isInstanceOf(PlaceDetailImportException.class);

    assertThat(jdbc.queryForObject("select count(*) from public.place_details", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select overview from public.tour_places where id=?", String.class, PLACE))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_api_operation_provenance where operation_key in ('detailCommon2','detailIntro2')",
                Integer.class))
        .isZero();
  }

  @Test
  void 없는_place_source와_content_type_mismatch는_저장전에_거부한다() {
    jdbc.update("delete from public.tour_place_sources");
    assertThatThrownBy(() -> repository.upsert(command(COMMON_HASH)))
        .isInstanceOf(PlaceDetailImportException.class);
    assertThat(jdbc.queryForObject("select count(*) from public.place_details", Integer.class))
        .isZero();
  }

  @Test
  void 더_오래된_detailCommon_sourceModifiedAt은_새_snapshot이어도_최신_row와_provenance를_덮어쓰지_않는다() {
    repository.upsert(command(COMMON_HASH));
    DetailState before = state();
    finishRun(COMMON_RUN);
    finishRun(INTRO_RUN);
    insertRunSnapshot(
        NEXT_COMMON_RUN,
        NEXT_COMMON_SNAPSHOT,
        "detailCommon2",
        NEXT_COMMON_HASH,
        NOW.plusSeconds(10));
    insertRunSnapshot(
        NEXT_INTRO_RUN, NEXT_INTRO_SNAPSHOT, "detailIntro2", NEXT_INTRO_HASH, NOW.plusSeconds(10));

    PlaceDetailUpsertCommand stale =
        command(
            "<p>오래된 개요</p>",
            "오래된 개요",
            NOW.minusSeconds(60),
            new DetailLineage(
                "detailCommon2", NEXT_COMMON_HASH, NEXT_COMMON_SNAPSHOT, NEXT_COMMON_RUN),
            intro("갈치조림"),
            new DetailLineage("detailIntro2", NEXT_INTRO_HASH, NEXT_INTRO_SNAPSHOT, NEXT_INTRO_RUN),
            NOW.plusSeconds(10));

    assertThatThrownBy(() -> repository.upsert(stale))
        .isInstanceOf(PlaceDetailImportException.class);
    assertThat(state()).isEqualTo(before);
  }

  @Test
  void 더_오래된_detailIntro_snapshot은_새_common과_함께와도_최신_row와_provenance를_덮어쓰지_않는다() {
    repository.upsert(command(COMMON_HASH));
    DetailState before = state();
    finishRun(COMMON_RUN);
    finishRun(INTRO_RUN);
    insertRunSnapshot(
        NEXT_COMMON_RUN,
        NEXT_COMMON_SNAPSHOT,
        "detailCommon2",
        NEXT_COMMON_HASH,
        NOW.plusSeconds(10));
    insertRunSnapshot(
        NEXT_INTRO_RUN, NEXT_INTRO_SNAPSHOT, "detailIntro2", NEXT_INTRO_HASH, NOW.minusSeconds(60));

    PlaceDetailUpsertCommand stale =
        command(
            "<p>안전한 개요</p>",
            "안전한 개요",
            NOW.minusSeconds(30),
            new DetailLineage(
                "detailCommon2", NEXT_COMMON_HASH, NEXT_COMMON_SNAPSHOT, NEXT_COMMON_RUN),
            intro("오래된 메뉴"),
            new DetailLineage("detailIntro2", NEXT_INTRO_HASH, NEXT_INTRO_SNAPSHOT, NEXT_INTRO_RUN),
            NOW.plusSeconds(10));

    assertThatThrownBy(() -> repository.upsert(stale))
        .isInstanceOf(PlaceDetailImportException.class);
    assertThat(state()).isEqualTo(before);
  }

  private PlaceDetailUpsertCommand command(String commonHash) {
    return command(
        "<p>안전한 개요</p>",
        "안전한 개요",
        NOW.minusSeconds(30),
        new DetailLineage("detailCommon2", commonHash, COMMON_SNAPSHOT, COMMON_RUN),
        intro("갈치조림"),
        new DetailLineage("detailIntro2", INTRO_HASH, INTRO_SNAPSHOT, INTRO_RUN),
        NOW);
  }

  private PlaceDetailUpsertCommand command(
      String overviewRaw,
      String overviewPlainText,
      Instant sourceModifiedAt,
      DetailLineage commonLineage,
      PlaceDetailIntro intro,
      DetailLineage introLineage,
      Instant fetchedAt) {
    return new PlaceDetailUpsertCommand(
        "100",
        new PlaceDetailCommon(
            "100", "39", "064", null, overviewRaw, overviewPlainText, sourceModifiedAt),
        intro,
        commonLineage,
        introLineage,
        fetchedAt);
  }

  private static PlaceDetailIntro intro(String firstMenu) {
    return new PlaceDetailIntro(
        "100",
        "39",
        "064",
        "10~20",
        "화요일",
        "무료",
        null,
        null,
        firstMenu,
        "예약",
        null,
        Map.of("firstmenu", firstMenu));
  }

  private void insertPlace() {
    jdbc.update(
        "insert into public.tour_places (id, external_place_id, content_id, content_type_id, name, normalized_name, category, region_code, address, location, source_provider, source_service, import_run_id, source_snapshot_id) values (?, '100', '100', '39', '맛집', '맛집', 'food', 'jeju', '제주', ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography, 'tour-api', 'KorService2', ?, ?)",
        PLACE,
        LIST_RUN,
        LIST_SNAPSHOT);
    jdbc.update(
        "insert into public.tour_place_sources (id, place_id, source_provider, source_service, external_id, content_type_id, source_snapshot_id, last_import_run_id) values (?, ?, 'tour-api', 'KorService2', '100', '39', ?, ?)",
        UUID.randomUUID(),
        PLACE,
        LIST_SNAPSHOT,
        LIST_RUN);
  }

  private void insertRunSnapshot(UUID run, UUID snapshot, String operation, String hash) {
    insertRunSnapshot(run, snapshot, operation, hash, NOW);
  }

  private void insertRunSnapshot(
      UUID run, UUID snapshot, String operation, String hash, Instant fetchedAt) {
    jdbc.update(
        "insert into public.data_import_runs (id, source_kind, source_name, source_operation, data_version, status, started_at, parser_version, schema_version, sync_mode, scope_key, request_fingerprint, idempotency_key, source_provider, source_service) values (?, 'tour_api', 'fixture', ?, '2026', 'running', ?, 'detail-v1', 'schema-v1', 'full', 'content:100', ?, ?, 'tour-api', 'KorService2')",
        run,
        operation,
        Timestamp.from(NOW),
        hash,
        operation + "-27-" + run);
    jdbc.update(
        "insert into public.external_api_snapshots (id, import_run_id, source_provider, source_service, source_operation, scope_key, request_hash, page_key, fetched_at, parser_version, payload_hash, request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version, payload_format, initial_parse_status, parse_status, parsed_at) values (?, ?, 'tour-api', 'KorService2', ?, 'content:100', ?, '1', ?, 'detail-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)",
        snapshot,
        run,
        operation,
        hash,
        Timestamp.from(fetchedAt),
        "f".repeat(64),
        Timestamp.from(fetchedAt));
  }

  private void finishRun(UUID run) {
    jdbc.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(1)),
        run);
  }

  private DetailState state() {
    return jdbc.queryForObject(
        """
        select p.overview, d.source_updated_at, d.fetched_at, d.updated_at,
          d.intro_attributes::text as attributes,
          (select count(*) from public.tour_api_operation_provenance
           where normalized_row_id=?) as provenance_count
        from public.tour_places p join public.place_details d on d.place_id=p.id
        where p.id=?
        """,
        (resultSet, rowNumber) ->
            new DetailState(
                resultSet.getString("overview"),
                resultSet.getTimestamp("source_updated_at").toInstant(),
                resultSet.getTimestamp("fetched_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getString("attributes"),
                resultSet.getInt("provenance_count")),
        PLACE,
        PLACE);
  }

  private void clean() {
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.place_details");
    jdbc.update("delete from public.tour_place_sources");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }

  private record DetailState(
      String overview,
      Instant sourceUpdatedAt,
      Instant fetchedAt,
      Instant updatedAt,
      String attributes,
      int provenanceCount) {}
}
