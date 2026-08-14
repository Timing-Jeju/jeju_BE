package com.timingjeju.api.global.tourapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.TourApiProvenance;
import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceReader;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
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
class JdbcTourApiProvenanceRepositoryIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
  private static final UUID RUN = UUID.fromString("40000000-0000-0000-0000-000000000107");
  private static final UUID SNAPSHOT = UUID.fromString("41000000-0000-0000-0000-000000000107");
  private static final UUID TARGET = UUID.fromString("42000000-0000-0000-0000-000000000107");
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @Autowired private TourApiProvenanceWriter writer;
  @Autowired private TourApiProvenanceReader reader;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshot(RUN, SNAPSHOT, "tour-api", "areaBasedList2");
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void 하나의_normalized_row에_복수_operation_snapshot_계보를_보존한다() {
    writer.write(command("areaBasedList2", SNAPSHOT, RUN, "12"), () -> {});
    UUID detailRun = UUID.fromString("43000000-0000-0000-0000-000000000107");
    UUID detailSnapshot = UUID.fromString("44000000-0000-0000-0000-000000000107");
    insertRunAndSnapshot(detailRun, detailSnapshot, "tour-api", "detailCommon2");

    writer.write(command("detailCommon2", detailSnapshot, detailRun, null), () -> {});

    assertThat(reader.findByNormalizedRow("tour_places", TARGET))
        .extracting(TourApiProvenance::operationKey)
        .containsExactly("areaBasedList2", "detailCommon2");
  }

  @Test
  void 같은_row_operation_snapshot의_중복_계보는_한행만_남긴다() {
    TourApiProvenance first =
        writer.write(command("areaBasedList2", SNAPSHOT, RUN, null), () -> {});
    TourApiProvenance replay =
        writer.write(command("areaBasedList2", SNAPSHOT, RUN, null), () -> {});

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(reader.findByNormalizedRow("tour_places", TARGET)).hasSize(1);
  }

  @Test
  void 미등록_operation은_normalized_write와_provenance를_모두_rollback한다() {
    assertThatThrownBy(
            () ->
                writer.write(
                    command("unknownOperation", SNAPSHOT, RUN, null),
                    () -> jdbcTemplate.update("insert into public.tour_places " + placeValues())))
        .isInstanceOf(TourApiProvenanceException.class)
        .hasMessageNotContaining("unknownOperation");

    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void snapshot_provider_불일치와_snapshot_run_불일치는_전체_transaction을_rollback한다() {
    UUID otherRun = UUID.fromString("45000000-0000-0000-0000-000000000107");
    UUID otherSnapshot = UUID.fromString("46000000-0000-0000-0000-000000000107");
    insertRunAndSnapshot(otherRun, otherSnapshot, "tago", "areaBasedList2");

    for (TourApiProvenanceCommand invalid :
        List.of(
            command("areaBasedList2", otherSnapshot, otherRun, null),
            command("areaBasedList2", SNAPSHOT, otherRun, null))) {
      assertThatThrownBy(
              () ->
                  writer.write(
                      invalid,
                      () -> jdbcTemplate.update("insert into public.tour_places " + placeValues())))
          .isInstanceOf(TourApiProvenanceException.class);
    }

    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void migration은_8개_registry와_nullable_content_type_및_서버전용_보안계약을_고정한다() {
    assertThat(
            jdbcTemplate.queryForList(
                "select operation_key from public.tour_api_operations order by operation_key",
                String.class))
        .containsExactly(
            "areaBasedList2",
            "areaCode2",
            "categoryCode2",
            "detailCommon2",
            "detailIntro2",
            "locationBasedList2",
            "searchKeyword2",
            "searchStay2");
    assertThat(
            jdbcTemplate.queryForObject(
                "select is_nullable from information_schema.columns where table_schema='public' and table_name='tour_api_operation_provenance' and column_name='content_type_id'",
                String.class))
        .isEqualTo("YES");
    assertThat(
            jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_schema='public' and table_name='tour_api_operation_provenance'",
                String.class))
        .doesNotContain(
            "service_key", "api_key", "raw_query", "request_url", "latitude", "longitude");
    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.tour_api_operation_provenance'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('anon','public.tour_api_operation_provenance','select') or has_table_privilege('authenticated','public.tour_api_operation_provenance','select')",
                Boolean.class))
        .isFalse();
  }

  private TourApiProvenanceCommand command(
      String operation, UUID snapshot, UUID run, String contentTypeId) {
    return new TourApiProvenanceCommand(
        "tour_places", TARGET, operation, contentTypeId, HASH, snapshot, run);
  }

  private void insertRunAndSnapshot(UUID run, UUID snapshot, String provider, String operation) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, ?, 'fixture', ?, 'v1', 'running', ?, 'parser-v1', 'schema-v1',
                  'incremental', 'jeju', 'sha256:fixture', ?, ?, 'KorService2')
        """,
        run,
        provider.equals("tour-api") ? "tour_api" : "tago",
        operation,
        Timestamp.from(NOW),
        "provenance-" + run,
        provider);
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, ?, 'KorService2', ?, 'jeju', ?, '', ?, 'parser-v1', ?,
                  '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        provider,
        operation,
        HASH,
        Timestamp.from(NOW),
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        Timestamp.from(NOW));
  }

  private String placeValues() {
    return "(id, name, normalized_name, category, location, source_provider) values ('"
        + TARGET
        + "', 'test', 'test', 'test', ST_GeogFromText('SRID=4326;POINT(126.5 33.5)'), 'tour-api')";
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("select count(*) from public." + table, Integer.class);
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.tour_places");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }
}
