package com.timingjeju.api.global.tourapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenance;
import com.timingjeju.api.application.tourapi.TourApiProvenanceCommand;
import com.timingjeju.api.application.tourapi.TourApiProvenanceException;
import com.timingjeju.api.application.tourapi.TourApiProvenanceReader;
import com.timingjeju.api.application.tourapi.TourApiProvenanceWriter;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final UUID PARENT = UUID.fromString("4d000000-0000-0000-0000-000000000107");
  private static final UUID MISSING = UUID.fromString("4e000000-0000-0000-0000-000000000107");
  private static final String HASH =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  @Autowired private TourApiProvenanceWriter writer;
  @Autowired private TourApiProvenanceReader reader;
  @Autowired private SnapshotStoreService snapshotStoreService;
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
    writer.write(
        command("areaBasedList2", SNAPSHOT, RUN, "12"),
        () -> jdbcTemplate.update("insert into public.tour_places " + placeValues(SNAPSHOT, RUN)));
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
        writer.write(
            command("areaBasedList2", SNAPSHOT, RUN, null),
            () ->
                jdbcTemplate.update(
                    "insert into public.tour_places " + placeValues(SNAPSHOT, RUN)));
    TourApiProvenance replay =
        writer.write(command("areaBasedList2", SNAPSHOT, RUN, null), () -> {});

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(reader.findByNormalizedRow("tour_places", TARGET)).hasSize(1);
  }

  @Test
  void snapshot과_provenance는_동일한_canonical_request_fingerprint로_정상_저장된다() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("pageNo", "1");
    metadata.put("contentTypeId", "12");
    metadata.put("serviceKey", "fixture-secret");
    metadata.put("requestUrl", "https://provider.test/path?serviceKey=fixture-secret");

    SnapshotSaveCommand snapshotCommand = snapshotCommand(RUN, metadata);
    SnapshotSaveResult snapshot = snapshotStoreService.save(snapshotCommand);
    snapshotStoreService.transition(
        new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
    AtomicInteger callbackCalls = new AtomicInteger();
    TourApiProvenance provenance =
        writer.write(
            TourApiProvenanceCommand.fromSnapshot(
                "tour_places", TARGET, "12", snapshotCommand, snapshot),
            () -> {
              callbackCalls.incrementAndGet();
              jdbcTemplate.update(
                  "insert into public.tour_places " + placeValues(snapshot.snapshotId(), RUN));
            });

    assertThat(provenance.requestFingerprint()).isEqualTo(snapshot.requestFingerprint());
    assertThat(callbackCalls).hasValue(1);
  }

  @Test
  void snapshot과_다른_request_fingerprint면_normalized_write까지_rollback한다() {
    Map<String, Object> metadata = Map.of("pageNo", "1", "contentTypeId", "12");
    SnapshotSaveResult snapshot = snapshotStoreService.save(snapshotCommand(RUN, metadata));
    snapshotStoreService.transition(
        new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));

    AtomicInteger callbackCalls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                writer.write(
                    new TourApiProvenanceCommand(
                        "tour_places",
                        TARGET,
                        "areaBasedList2",
                        "12",
                        HASH,
                        snapshot.snapshotId(),
                        RUN),
                    () -> {
                      callbackCalls.incrementAndGet();
                      jdbcTemplate.update(
                          "insert into public.tour_places "
                              + placeValues(snapshot.snapshotId(), RUN));
                    }))
        .isInstanceOf(TourApiProvenanceException.class);

    assertThat(callbackCalls).hasValue(0);
    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void 미등록_operation은_normalized_write와_provenance를_모두_rollback한다() {
    AtomicInteger callbackCalls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                writer.write(
                    command("unknownOperation", SNAPSHOT, RUN, null),
                    () -> {
                      callbackCalls.incrementAndGet();
                      jdbcTemplate.update("insert into public.tour_places " + placeValues());
                    }))
        .isInstanceOf(TourApiProvenanceException.class)
        .hasMessageNotContaining("unknownOperation");

    assertThat(callbackCalls).hasValue(0);
    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void 비활성_operation도_normalized_callback_전에_거부한다() {
    jdbcTemplate.update(
        "update public.tour_api_operations set active=false where operation_key='areaBasedList2'");
    AtomicInteger callbackCalls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                writer.write(
                    command("areaBasedList2", SNAPSHOT, RUN, null), callbackCalls::incrementAndGet))
        .isInstanceOf(TourApiProvenanceException.class);

    assertThat(callbackCalls).hasValue(0);
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void registry와_lineage_불일치는_각각_normalized_callback_전에_거부한다() {
    UUID providerRun = UUID.fromString("45000000-0000-0000-0000-000000000107");
    UUID providerSnapshot = UUID.fromString("46000000-0000-0000-0000-000000000107");
    insertRunAndSnapshot(providerRun, providerSnapshot, "tago", "KorService2", "areaBasedList2");
    UUID serviceRun = UUID.fromString("47000000-0000-0000-0000-000000000107");
    UUID serviceSnapshot = UUID.fromString("48000000-0000-0000-0000-000000000107");
    insertRunAndSnapshot(serviceRun, serviceSnapshot, "tour-api", "OtherService", "areaBasedList2");
    UUID operationRun = UUID.fromString("49000000-0000-0000-0000-000000000107");
    UUID operationSnapshot = UUID.fromString("4a000000-0000-0000-0000-000000000107");
    insertRunAndSnapshot(
        operationRun, operationSnapshot, "tour-api", "KorService2", "detailCommon2");
    UUID unmatchedRun = UUID.fromString("4b000000-0000-0000-0000-000000000107");
    insertRun(unmatchedRun, "tour-api", "KorService2", "areaBasedList2", "seogwipo");

    List<TourApiProvenanceCommand> invalidCommands =
        List.of(
            command("areaBasedList2", providerSnapshot, providerRun, null),
            command("areaBasedList2", serviceSnapshot, serviceRun, null),
            command("areaBasedList2", operationSnapshot, operationRun, null),
            command("areaBasedList2", SNAPSHOT, unmatchedRun, null));

    for (TourApiProvenanceCommand invalid : invalidCommands) {
      AtomicInteger callbackCalls = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  writer.write(
                      invalid,
                      () -> {
                        callbackCalls.incrementAndGet();
                        jdbcTemplate.update("insert into public.tour_places " + placeValues());
                      }))
          .isInstanceOf(TourApiProvenanceException.class);
      assertThat(callbackCalls).hasValue(0);
    }

    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void 존재하지_않는_normalized_target은_provenance와_callback_DB_write를_모두_rollback한다() {
    UUID otherTarget = UUID.fromString("4c000000-0000-0000-0000-000000000107");

    assertThatThrownBy(
            () ->
                writer.write(
                    command("areaBasedList2", SNAPSHOT, RUN, null),
                    () ->
                        jdbcTemplate.update(
                            "insert into public.tour_places "
                                + placeValues(otherTarget, SNAPSHOT, RUN))))
        .isInstanceOf(TourApiProvenanceException.class);

    assertThat(count("tour_places")).isZero();
    assertThat(count("tour_api_operation_provenance")).isZero();
  }

  @Test
  void allowlist_7개_entity는_실제_target만_허용하고_missing_UUID는_23503으로_거부한다() {
    jdbcTemplate.update("insert into public.tour_places " + manualPlaceValues(PARENT));
    List<TargetFixture> targets =
        List.of(
            new TargetFixture(
                "external_reference_codes",
                UUID.fromString("51000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.external_reference_codes (
                          id, source_provider, source_service, code_type, external_code, code_name
                        ) values (?, 'admin_upload', 'contract', 'area', '50', '제주')
                        """,
                        UUID.fromString("51000000-0000-0000-0000-000000000107"))),
            new TargetFixture(
                "tour_places",
                UUID.fromString("52000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        "insert into public.tour_places "
                            + manualPlaceValues(
                                UUID.fromString("52000000-0000-0000-0000-000000000107")))),
            new TargetFixture(
                "tour_place_sources",
                UUID.fromString("53000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.tour_place_sources (
                          id, place_id, source_provider, source_service, external_id
                        ) values (?, ?, 'admin_upload', 'contract', 'source-107')
                        """,
                        UUID.fromString("53000000-0000-0000-0000-000000000107"),
                        PARENT)),
            new TargetFixture(
                "place_aliases",
                UUID.fromString("54000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.place_aliases (
                          id, place_id, alias, normalized_alias, alias_type,
                          source_snapshot_id, import_run_id
                        ) values (?, ?, '계약 별칭', '계약별칭', 'official', ?, ?)
                        """,
                        UUID.fromString("54000000-0000-0000-0000-000000000107"),
                        PARENT,
                        SNAPSHOT,
                        RUN)),
            new TargetFixture(
                "place_details",
                PARENT,
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.place_details (place_id, source_provider, source_service)
                        values (?, 'admin_upload', 'contract')
                        """,
                        PARENT)),
            new TargetFixture(
                "place_detail_items",
                UUID.fromString("56000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.place_detail_items (
                          id, place_id, source_provider, source_service, item_type,
                          source_item_key, payload_hash
                        ) values (?, ?, 'admin_upload', 'contract', 'overview', 'item-107', ?)
                        """,
                        UUID.fromString("56000000-0000-0000-0000-000000000107"),
                        PARENT,
                        HASH)),
            new TargetFixture(
                "place_images",
                UUID.fromString("57000000-0000-0000-0000-000000000107"),
                () ->
                    jdbcTemplate.update(
                        """
                        insert into public.place_images (
                          id, place_id, image_url, source_provider, source_service
                        ) values (?, ?, 'https://example.test/contract.jpg', 'admin_upload', 'contract')
                        """,
                        UUID.fromString("57000000-0000-0000-0000-000000000107"),
                        PARENT)));

    for (TargetFixture target : targets) {
      target.insertTarget().run();
      TourApiProvenance saved;
      try {
        saved = writer.write(command(target.entityType(), target.rowId()), () -> {});
      } catch (RuntimeException failure) {
        throw new AssertionError(target.entityType() + " 실제 target provenance 저장 실패", failure);
      }
      assertThat(saved.normalizedEntityType()).isEqualTo(target.entityType());
      assertThat(saved.normalizedRowId()).isEqualTo(target.rowId());

      Throwable failure =
          catchThrowable(() -> insertProvenanceDirectly(target.entityType(), MISSING));
      assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rootSqlException(failure).getSQLState()).isEqualTo("23503");
    }
  }

  @Test
  void allowlist_7개_entity의_identifier_UPDATE는_23503으로_거부하고_계보를_보존한다() {
    UUID alternateParent = UUID.fromString("4f000000-0000-0000-0000-000000000107");
    jdbcTemplate.update("insert into public.tour_places " + manualPlaceValues(PARENT));
    jdbcTemplate.update("insert into public.tour_places " + manualPlaceValues(alternateParent));
    List<TargetIdentity> targets =
        List.of(
            new TargetIdentity(
                "external_reference_codes",
                UUID.fromString("61000000-0000-0000-0000-000000000107")),
            new TargetIdentity(
                "tour_places", UUID.fromString("62000000-0000-0000-0000-000000000107")),
            new TargetIdentity(
                "tour_place_sources", UUID.fromString("63000000-0000-0000-0000-000000000107")),
            new TargetIdentity(
                "place_aliases", UUID.fromString("64000000-0000-0000-0000-000000000107")),
            new TargetIdentity("place_details", PARENT),
            new TargetIdentity(
                "place_detail_items", UUID.fromString("66000000-0000-0000-0000-000000000107")),
            new TargetIdentity(
                "place_images", UUID.fromString("67000000-0000-0000-0000-000000000107")));

    for (TargetIdentity target : targets) {
      insertTargetFixture(target);
      writer.write(command(target.entityType(), target.rowId()), () -> {});

      Throwable failure = catchThrowable(() -> updateTargetIdentifier(target, alternateParent));

      assertThat(failure)
          .as(target.entityType())
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(rootSqlException(failure).getSQLState())
          .as(target.entityType())
          .isEqualTo("23503");
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from public.tour_api_operation_provenance where normalized_entity_type=? and normalized_row_id=?",
                  Integer.class,
                  target.entityType(),
                  target.rowId()))
          .as(target.entityType())
          .isOne();

      jdbcTemplate.update(
          "delete from public.tour_api_operation_provenance where normalized_entity_type=? and normalized_row_id=?",
          target.entityType(),
          target.rowId());
      deleteTargetFixture(target);
    }
  }

  @Test
  void migration은_9개_registry와_nullable_content_type_및_서버전용_보안계약을_고정한다() {
    assertThat(
            jdbcTemplate.queryForList(
                "select operation_key from public.tour_api_operations order by operation_key",
                String.class))
        .containsExactly(
            "areaBasedList2",
            "areaCode2",
            "categoryCode2",
            "detailCommon2",
            "detailInfo2",
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

  private TourApiProvenanceCommand command(String entityType, UUID rowId) {
    return new TourApiProvenanceCommand(
        entityType, rowId, "areaBasedList2", null, HASH, SNAPSHOT, RUN);
  }

  private void insertProvenanceDirectly(String entityType, UUID rowId) {
    jdbcTemplate.update(
        """
        insert into public.tour_api_operation_provenance (
          normalized_entity_type, normalized_row_id, operation_key, request_fingerprint,
          source_snapshot_id, import_run_id
        ) values (?, ?, 'areaBasedList2', ?, ?, ?)
        """,
        entityType,
        rowId,
        HASH,
        SNAPSHOT,
        RUN);
  }

  private void insertTargetFixture(TargetIdentity target) {
    switch (target.entityType()) {
      case "external_reference_codes" ->
          jdbcTemplate.update(
              "insert into public.external_reference_codes (id, source_provider, source_service, code_type, external_code, code_name) values (?, 'admin_upload', 'contract', 'area', '60', '부산')",
              target.rowId());
      case "tour_places" ->
          jdbcTemplate.update(
              "insert into public.tour_places " + manualPlaceValues(target.rowId()));
      case "tour_place_sources" ->
          jdbcTemplate.update(
              "insert into public.tour_place_sources (id, place_id, source_provider, source_service, external_id) values (?, ?, 'admin_upload', 'contract', 'update-107')",
              target.rowId(),
              PARENT);
      case "place_aliases" ->
          jdbcTemplate.update(
              "insert into public.place_aliases (id, place_id, alias, normalized_alias, alias_type, source_snapshot_id, import_run_id) values (?, ?, '변경 별칭', '변경별칭', 'official', ?, ?)",
              target.rowId(),
              PARENT,
              SNAPSHOT,
              RUN);
      case "place_details" ->
          jdbcTemplate.update(
              "insert into public.place_details (place_id, source_provider, source_service) values (?, 'admin_upload', 'contract')",
              target.rowId());
      case "place_detail_items" ->
          jdbcTemplate.update(
              "insert into public.place_detail_items (id, place_id, source_provider, source_service, item_type, source_item_key, payload_hash) values (?, ?, 'admin_upload', 'contract', 'overview', 'update-107', ?)",
              target.rowId(),
              PARENT,
              HASH);
      case "place_images" ->
          jdbcTemplate.update(
              "insert into public.place_images (id, place_id, image_url, source_provider, source_service) values (?, ?, 'https://example.test/update.jpg', 'admin_upload', 'contract')",
              target.rowId(),
              PARENT);
      default -> throw new IllegalArgumentException(target.entityType());
    }
  }

  private void updateTargetIdentifier(TargetIdentity target, UUID alternateParent) {
    String identifier = target.entityType().equals("place_details") ? "place_id" : "id";
    UUID updatedId =
        target.entityType().equals("place_details")
            ? alternateParent
            : UUID.fromString("6f000000-0000-0000-0000-000000000107");
    jdbcTemplate.update(
        "update public."
            + target.entityType()
            + " set "
            + identifier
            + "=? where "
            + identifier
            + "=?",
        updatedId,
        target.rowId());
  }

  private void deleteTargetFixture(TargetIdentity target) {
    String identifier = target.entityType().equals("place_details") ? "place_id" : "id";
    jdbcTemplate.update(
        "delete from public." + target.entityType() + " where " + identifier + "=?",
        target.rowId());
  }

  private SQLException rootSqlException(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    assertThat(current).isInstanceOf(SQLException.class);
    return (SQLException) current;
  }

  private void insertRunAndSnapshot(UUID run, UUID snapshot, String provider, String operation) {
    insertRunAndSnapshot(run, snapshot, provider, "KorService2", operation);
  }

  private void insertRunAndSnapshot(
      UUID run, UUID snapshot, String provider, String service, String operation) {
    insertRun(run, provider, service, operation, "jeju");
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, ?, ?, ?, 'jeju', ?, '', ?, 'parser-v1', ?,
                  '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        provider,
        service,
        operation,
        HASH,
        Timestamp.from(NOW),
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        Timestamp.from(NOW));
  }

  private void insertRun(UUID run, String provider, String operation) {
    insertRun(run, provider, "KorService2", operation, "jeju");
  }

  private void insertRun(
      UUID run, String provider, String service, String operation, String scope) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, ?, 'fixture', ?, 'v1', 'running', ?, 'parser-v1', 'schema-v1',
                  'incremental', ?, 'sha256:fixture', ?, ?, ?)
        """,
        run,
        provider.equals("tour-api") ? "tour_api" : "tago",
        operation,
        Timestamp.from(NOW),
        scope,
        "provenance-" + run,
        provider,
        service);
  }

  private SnapshotSaveCommand snapshotCommand(UUID run, Map<String, Object> metadata) {
    return new SnapshotSaveCommand(
        run,
        new SnapshotScope("tour-api", "KorService2", "areaBasedList2", "jeju"),
        "content-1",
        "page-1",
        200,
        "00",
        NOW,
        null,
        null,
        "parser-v1",
        SnapshotPayloadFormat.JSON,
        "UTF-8",
        "{\"item\":1}".getBytes(StandardCharsets.UTF_8),
        metadata);
  }

  private String placeValues() {
    return "(id, name, normalized_name, category, location, source_provider) values ('"
        + TARGET
        + "', 'test', 'test', 'test', ST_GeogFromText('SRID=4326;POINT(126.5 33.5)'), 'tour-api')";
  }

  private String placeValues(UUID snapshotId, UUID runId) {
    return placeValues(TARGET, snapshotId, runId);
  }

  private String placeValues(UUID targetId, UUID snapshotId, UUID runId) {
    return "(id, name, normalized_name, category, location, source_provider, source_service, source_snapshot_id, import_run_id) values ('"
        + targetId
        + "', 'test', 'test', 'test', ST_GeogFromText('SRID=4326;POINT(126.5 33.5)'), 'tour-api', 'KorService2', '"
        + snapshotId
        + "', '"
        + runId
        + "')";
  }

  private String manualPlaceValues(UUID targetId) {
    return "(id, name, normalized_name, category, location, source_provider) values ('"
        + targetId
        + "', 'contract', 'contract', 'test', ST_GeogFromText('SRID=4326;POINT(126.5 33.5)'), 'admin_upload')";
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("select count(*) from public." + table, Integer.class);
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.tour_places");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
    jdbcTemplate.update("update public.tour_api_operations set active=true");
  }

  private record TargetFixture(String entityType, UUID rowId, Runnable insertTarget) {}

  private record TargetIdentity(String entityType, UUID rowId) {}
}
