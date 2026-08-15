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
  private static final String LIST_HASH = "1".repeat(64);
  private static final String COMMON_HASH = "2".repeat(64);
  private static final String INTRO_HASH = "3".repeat(64);
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
    var replay = repository.upsert(command(COMMON_HASH));

    assertThat(first.inserted()).isTrue();
    assertThat(replay.skipped()).isTrue();
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

  private PlaceDetailUpsertCommand command(String commonHash) {
    return new PlaceDetailUpsertCommand(
        "100",
        new PlaceDetailCommon(
            "100", "39", "064", null, "<p>안전한 개요</p>", "안전한 개요", NOW.minusSeconds(30)),
        new PlaceDetailIntro(
            "100",
            "39",
            "064",
            "10~20",
            "화요일",
            "무료",
            null,
            null,
            "갈치조림",
            "예약",
            null,
            Map.of("firstmenu", "갈치조림")),
        new DetailLineage("detailCommon2", commonHash, COMMON_SNAPSHOT, COMMON_RUN),
        new DetailLineage("detailIntro2", INTRO_HASH, INTRO_SNAPSHOT, INTRO_RUN),
        NOW);
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
    jdbc.update(
        "insert into public.data_import_runs (id, source_kind, source_name, source_operation, data_version, status, started_at, parser_version, schema_version, sync_mode, scope_key, request_fingerprint, idempotency_key, source_provider, source_service) values (?, 'tour_api', 'fixture', ?, '2026', 'running', ?, 'detail-v1', 'schema-v1', 'full', 'content:100', ?, ?, 'tour-api', 'KorService2')",
        run,
        operation,
        Timestamp.from(NOW),
        hash,
        operation + "-27");
    jdbc.update(
        "insert into public.external_api_snapshots (id, import_run_id, source_provider, source_service, source_operation, scope_key, request_hash, page_key, fetched_at, parser_version, payload_hash, request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version, payload_format, initial_parse_status, parse_status, parsed_at) values (?, ?, 'tour-api', 'KorService2', ?, 'content:100', ?, '1', ?, 'detail-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)",
        snapshot,
        run,
        operation,
        hash,
        Timestamp.from(NOW),
        "f".repeat(64),
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.place_details");
    jdbc.update("delete from public.tour_place_sources");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }
}
