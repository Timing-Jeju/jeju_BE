package com.timingjeju.api.global.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreError;
import com.timingjeju.api.application.snapshot.SnapshotStoreException;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.snapshot.StoredSnapshot;
import com.timingjeju.api.application.tago.route.TagoRouteSourceResponse;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.global.tago.route.SnapshottingTagoRouteGateway;
import com.timingjeju.api.global.tourapi.detailitem.SnapshottingDetailInfoPageGateway;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class JdbcSnapshotStoreIntegrationTest {

  private static final Instant FETCHED_AT = Instant.parse("2026-08-13T12:00:00Z");
  private static final UUID RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000023");
  private static final UUID ROUTE_RUN_ID = UUID.fromString("30000000-0000-0000-0000-000000000036");

  @Autowired private SnapshotStoreService service;
  @Autowired private JdbcSnapshotStore store;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SnapshottingTagoRouteGateway routeGateway;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
    insertRun(RUN_ID, "tour-api", "KorService2", "areaBasedList2", "jeju");
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }

  @Test
  void 같은_run_page_payload의_중복은_동일_snapshot을_멱등_반환한다() {
    SnapshotSaveResult first = service.save(command(RUN_ID, "page-1", "{\"safe\":1}"));
    SnapshotSaveResult replay = service.save(command(RUN_ID, "page-1", "{\"safe\":1}"));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots", Integer.class))
        .isEqualTo(1);
    assertThat(row(first.snapshotId()))
        .containsEntry("parse_status", "received")
        .containsEntry("payload_size_bytes", 10L)
        .containsEntry("redaction_version", "snapshot-redaction-v2");
  }

  @Test
  void TAGO_route_run은_각_원문_operation_snapshot을_같은_run_lineage로_보존한다() {
    insertRun(ROUTE_RUN_ID, "TAGO", "BusRouteInfoInqireService", "getRouteNoList", "jeju-routes");
    TagoRouteSourceResponse response =
        new TagoRouteSourceResponse(
            "{\"response\":{}}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);

    var routeList = routeGateway.save(ROUTE_RUN_ID, "route-list", "39", "101", 1, response);
    var routeDetail = routeGateway.save(ROUTE_RUN_ID, "route-detail", "39", "R-1", 0, response);
    var routeStops = routeGateway.save(ROUTE_RUN_ID, "route-stops", "39", "R-1", 1, response);
    routeGateway.markParsed(routeList);
    routeGateway.markParsed(routeDetail);
    routeGateway.markParsed(routeStops);

    assertThat(
            jdbcTemplate.queryForList(
                "select source_operation from public.external_api_snapshots where import_run_id=? and parse_status='parsed' order by source_operation",
                String.class,
                ROUTE_RUN_ID))
        .containsExactly("getRouteAcctoThrghSttnList", "getRouteInfoIem", "getRouteNoList");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(distinct import_run_id) from public.external_api_snapshots where import_run_id=?",
                Integer.class,
                ROUTE_RUN_ID))
        .isEqualTo(1);
  }

  @Test
  void 같은_run_page_payload는_재시도_수신시각이_달라도_기존_snapshot을_재사용한다() {
    SnapshotSaveCommand original = command(RUN_ID, "retry-page", "{\"safe\":1}");
    SnapshotSaveResult first = service.save(original);
    SnapshotSaveCommand retried =
        new SnapshotSaveCommand(
            original.importRunId(),
            original.scope(),
            original.externalRecordId(),
            original.pageKey(),
            original.httpStatus(),
            original.providerResultCode(),
            original.fetchedAt().plusSeconds(5),
            original.sourceModifiedAt(),
            original.expiresAt(),
            original.parserVersion(),
            original.payloadFormat(),
            original.charset(),
            original.decompressedPayload(),
            original.requestMetadata());

    SnapshotSaveResult replay = service.save(retried);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
  }

  @Test
  void 저장후_terminal_전환되어도_동일한_초기분류의_진짜_duplicate는_replay한다() {
    SnapshotSaveCommand original = command(RUN_ID, "terminal-replay", "{\"safe\":1}");
    SnapshotSaveResult first = service.save(original);
    service.transition(
        new SnapshotTransitionCommand(first.snapshotId(), SnapshotStatus.PARSED, null));

    SnapshotSaveResult replay = service.save(original);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
  }

  @Test
  void detailInfo_gateway의_시간이_진행된_terminal_replay는_DB의_최초_fetchedAt과_status를_사용한다() {
    UUID detailRun = UUID.fromString("30000000-0000-0000-0000-000000000028");
    insertRun(detailRun, "tour-api", "KorService2", "detailInfo2", "content:100");
    byte[] raw = "{\"response\":{\"body\":{\"pageNo\":1}}}".getBytes(StandardCharsets.UTF_8);
    DetailSourceResponse response = new DetailSourceResponse(raw, SnapshotPayloadFormat.JSON);
    var firstGateway =
        new SnapshottingDetailInfoPageGateway(service, Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
    var retryGateway =
        new SnapshottingDetailInfoPageGateway(
            service, Clock.fixed(FETCHED_AT.plusSeconds(30), ZoneOffset.UTC));

    var first = firstGateway.save(detailRun, "100", "12", 1, response);
    firstGateway.markParsed(first);
    var replay = retryGateway.save(detailRun, "100", "12", 1, response);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.lineage()).isEqualTo(first.lineage());
    assertThat(replay.fetchedAt()).isEqualTo(FETCHED_AT);
    assertThat(replay.status()).isEqualTo(SnapshotStatus.PARSED);
    assertThatCode(() -> retryGateway.markParsed(replay)).doesNotThrowAnyException();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots where import_run_id=? and page_key='1'",
                Integer.class,
                detailRun))
        .isEqualTo(1);
  }

  @Test
  void detailInfo_multi_page_terminal_replay는_page별_최초_snapshot_identity를_유지한다() {
    UUID detailRun = UUID.fromString("30000000-0000-0000-0000-000000000029");
    insertRun(detailRun, "tour-api", "KorService2", "detailInfo2", "content:100");
    var firstGateway =
        new SnapshottingDetailInfoPageGateway(service, Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
    var retryGateway =
        new SnapshottingDetailInfoPageGateway(
            service, Clock.fixed(FETCHED_AT.plusSeconds(30), ZoneOffset.UTC));
    DetailSourceResponse firstResponse =
        new DetailSourceResponse(
            "{\"page\":1}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    DetailSourceResponse secondResponse =
        new DetailSourceResponse(
            "{\"page\":2}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);

    var page1 = firstGateway.save(detailRun, "100", "12", 1, firstResponse);
    var page2 = firstGateway.save(detailRun, "100", "12", 2, secondResponse);
    firstGateway.markParsed(page1);
    firstGateway.markParsed(page2);
    var replay1 = retryGateway.save(detailRun, "100", "12", 1, firstResponse);
    var replay2 = retryGateway.save(detailRun, "100", "12", 2, secondResponse);

    assertThat(List.of(replay1.replayed(), replay2.replayed())).containsOnly(true);
    assertThat(List.of(replay1.lineage().snapshotId(), replay2.lineage().snapshotId()))
        .containsExactly(page1.lineage().snapshotId(), page2.lineage().snapshotId());
    assertThat(List.of(replay1.fetchedAt(), replay2.fetchedAt()))
        .containsExactly(FETCHED_AT, FETCHED_AT);
    assertThat(List.of(replay1.status(), replay2.status())).containsOnly(SnapshotStatus.PARSED);
  }

  @Test
  void detailInfo_동시_true_replay의_same_target_transition은_한_snapshot을_PARSED로_수렴한다()
      throws Exception {
    UUID detailRun = UUID.fromString("30000000-0000-0000-0000-000000000030");
    insertRun(detailRun, "tour-api", "KorService2", "detailInfo2", "content:100");
    var gateway =
        new SnapshottingDetailInfoPageGateway(service, Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
    DetailSourceResponse response =
        new DetailSourceResponse(
            "{\"page\":1}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage> first =
          executor.submit(() -> saveAndParse(gateway, detailRun, response, start));
      Future<com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage> second =
          executor.submit(() -> saveAndParse(gateway, detailRun, response, start));
      start.countDown();
      var firstResult = first.get(15, TimeUnit.SECONDS);
      var secondResult = second.get(15, TimeUnit.SECONDS);

      assertThat(firstResult.lineage().snapshotId()).isEqualTo(secondResult.lineage().snapshotId());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(
              jdbcTemplate.queryForMap(
                  "select parse_status, count(*) over () as snapshot_count from public.external_api_snapshots where import_run_id=?",
                  detailRun))
          .containsEntry("parse_status", "parsed")
          .containsEntry("snapshot_count", 1L);
    }
  }

  @Test
  void run_scope가_다르면_전체_insert가_rollback되고_안전한_code만_반환한다() {
    SnapshotSaveCommand mismatch =
        new SnapshotSaveCommand(
            RUN_ID,
            new SnapshotScope("TAGO", "BusArrival", "arrival", "jeju"),
            null,
            "page-1",
            200,
            null,
            FETCHED_AT,
            null,
            null,
            "parser-v1",
            SnapshotPayloadFormat.JSON,
            "UTF-8",
            "{\"token\":\"must-not-leak\"}".getBytes(StandardCharsets.UTF_8),
            Map.of());

    assertThatThrownBy(() -> service.save(mismatch))
        .isInstanceOf(SnapshotStoreException.class)
        .satisfies(
            failure -> {
              assertThat(((SnapshotStoreException) failure).code())
                  .isEqualTo(SnapshotStoreError.SCOPE_MISMATCH);
              assertThat(failure.getMessage()).doesNotContain("must-not-leak", "TAGO");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getSuppressed()).isEmpty();
            });
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots", Integer.class))
        .isZero();
  }

  @Test
  void received는_한번만_terminal로_전환하고_성공30일_실패7일을_기록한다() {
    SnapshotSaveResult parsed = service.save(command(RUN_ID, "parsed", "{\"safe\":1}"));
    service.transition(
        new SnapshotTransitionCommand(parsed.snapshotId(), SnapshotStatus.PARSED, null));
    Map<String, Object> parsedRow = row(parsed.snapshotId());
    assertThat(parsedRow).containsEntry("parse_status", "parsed");
    java.sql.Timestamp parsedAt = (java.sql.Timestamp) parsedRow.get("parsed_at");
    assertThat(parsedAt).isNotNull();
    assertThat(parsedRow.get("purge_after"))
        .isEqualTo(java.sql.Timestamp.from(parsedAt.toInstant().plusSeconds(30L * 24 * 60 * 60)));

    assertThatThrownBy(
            () ->
                service.transition(
                    new SnapshotTransitionCommand(
                        parsed.snapshotId(),
                        SnapshotStatus.REJECTED,
                        SnapshotFailure.PARSE_REJECTED)))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.INVALID_TRANSITION);

    SnapshotSaveResult rejected = service.save(command(RUN_ID, "rejected", "{\"safe\":2}"));
    service.transition(
        new SnapshotTransitionCommand(
            rejected.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
    Map<String, Object> rejectedRow = row(rejected.snapshotId());
    java.sql.Timestamp rejectedPurge = (java.sql.Timestamp) rejectedRow.get("purge_after");
    assertThat(rejectedPurge.toInstant())
        .isBetween(
            Instant.now().plusSeconds(7L * 24 * 60 * 60 - 10),
            Instant.now().plusSeconds(7L * 24 * 60 * 60 + 10));
  }

  @Test
  void 동시_duplicate는_한행만_만들고_모든_caller에_같은_id를_반환한다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> saveAfter(start));
      var second = pool.submit(() -> saveAfter(start));
      start.countDown();

      SnapshotSaveResult firstResult = first.get(15, TimeUnit.SECONDS);
      SnapshotSaveResult secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(firstResult.snapshotId()).isEqualTo(secondResult.snapshotId());
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 동일_hash_identity에_다른_canonical_payload면_collision으로_거부한다() {
    SnapshotSaveResult first = service.save(command(RUN_ID, "collision", "{\"safe\":1}"));
    StoredSnapshot original = store.findForTest(first.snapshotId());
    StoredSnapshot colliding =
        original.withSnapshotId(UUID.randomUUID()).withRawPayloadJson("{\"safe\":2}");

    assertThatThrownBy(() -> store.save(colliding))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.HASH_COLLISION);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 동일_byte라도_payload_format과_초기분류가_다르면_collision으로_거부한다() {
    byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
    SnapshotSaveCommand json = command(RUN_ID, "format-collision", "{");
    SnapshotSaveResult first = service.save(json);
    SnapshotSaveCommand binary =
        new SnapshotSaveCommand(
            json.importRunId(),
            json.scope(),
            json.externalRecordId(),
            json.pageKey(),
            json.httpStatus(),
            json.providerResultCode(),
            json.fetchedAt(),
            json.sourceModifiedAt(),
            json.expiresAt(),
            json.parserVersion(),
            SnapshotPayloadFormat.BINARY,
            "UTF-8",
            malformed,
            json.requestMetadata());

    assertThatThrownBy(() -> service.save(binary))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.HASH_COLLISION);
    assertThat(row(first.snapshotId()))
        .containsEntry("payload_format", "JSON")
        .containsEntry("parse_status", "rejected")
        .containsEntry("error_code", "SNAPSHOT_MALFORMED_PAYLOAD");
  }

  @Test
  void invalid_UTF8과_binary_및_status_error_audit_mismatch는_replay하지_않는다() {
    byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28};
    SnapshotSaveCommand json = command(RUN_ID, "invalid-encoding", "{}");
    json =
        new SnapshotSaveCommand(
            json.importRunId(),
            json.scope(),
            json.externalRecordId(),
            json.pageKey(),
            json.httpStatus(),
            json.providerResultCode(),
            json.fetchedAt(),
            json.sourceModifiedAt(),
            json.expiresAt(),
            json.parserVersion(),
            SnapshotPayloadFormat.JSON,
            "UTF-8",
            invalidUtf8,
            json.requestMetadata());
    SnapshotSaveResult first = service.save(json);
    SnapshotSaveCommand binary =
        new SnapshotSaveCommand(
            json.importRunId(),
            json.scope(),
            json.externalRecordId(),
            json.pageKey(),
            json.httpStatus(),
            json.providerResultCode(),
            json.fetchedAt(),
            json.sourceModifiedAt(),
            json.expiresAt(),
            json.parserVersion(),
            SnapshotPayloadFormat.BINARY,
            "UTF-8",
            invalidUtf8,
            json.requestMetadata());
    assertThatThrownBy(() -> service.save(binary))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.HASH_COLLISION);

    StoredSnapshot original = store.findForTest(first.snapshotId());
    StoredSnapshot mismatched =
        new StoredSnapshot(
            UUID.randomUUID(),
            original.importRunId(),
            original.scope(),
            original.externalRecordId(),
            original.requestHash(),
            original.pageKey(),
            original.httpStatus(),
            original.providerResultCode(),
            original.fetchedAt(),
            original.sourceModifiedAt(),
            original.expiresAt(),
            original.parserVersion(),
            original.payloadHash(),
            original.payloadFormat(),
            SnapshotStatus.IGNORED,
            "SNAPSHOT_BINARY_PAYLOAD",
            SnapshotStatus.IGNORED,
            "SNAPSHOT_BINARY_PAYLOAD",
            null,
            original.requestMetadataRedactedJson(),
            original.rawPayloadJson(),
            original.payloadSizeBytes(),
            original.redactionVersion(),
            original.purgeAfter());
    assertThatThrownBy(() -> store.save(mismatched))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.HASH_COLLISION);
  }

  @Test
  void migration은_nullable_payload와_감사_metadata_보안권한을_고정한다() {
    Map<String, String> columns =
        jdbcTemplate.query(
            """
            select column_name, is_nullable from information_schema.columns
            where table_schema='public' and table_name='external_api_snapshots'
              and column_name in ('raw_payload','payload_size_bytes','redaction_version','purged_at')
            """,
            resultSet -> {
              Map<String, String> result = new java.util.HashMap<>();
              while (resultSet.next()) {
                result.put(resultSet.getString(1), resultSet.getString(2));
              }
              return result;
            });
    assertThat(columns)
        .containsEntry("raw_payload", "YES")
        .containsEntry("payload_size_bytes", "NO")
        .containsEntry("redaction_version", "NO")
        .containsEntry("purged_at", "YES");
    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.external_api_snapshots'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('anon','public.external_api_snapshots','select') or has_table_privilege('authenticated','public.external_api_snapshots','select')",
                Boolean.class))
        .isFalse();
  }

  @Test
  void DB도_terminal_역행과_감사필드_변조를_거부하고_정상_purge만_허용한다() {
    SnapshotSaveResult result = service.save(command(RUN_ID, "db-guard", "{\"safe\":1}"));
    service.transition(
        new SnapshotTransitionCommand(result.snapshotId(), SnapshotStatus.TOMBSTONED, null));

    for (String update :
        List.of(
            "parse_status='received'",
            "payload_size_bytes=payload_size_bytes+1",
            "redaction_version='changed'",
            "error_message='changed'",
            "purge_after=purge_after+interval '1 day'")) {
      assertThatThrownBy(
              () ->
                  jdbcTemplate.update(
                      "update public.external_api_snapshots set " + update + " where id=?",
                      result.snapshotId()))
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    jdbcTemplate.update(
        "update public.external_api_snapshots set raw_payload=null, purged_at=purge_after where id=?",
        result.snapshotId());
    assertThat(row(result.snapshotId()))
        .containsEntry("raw_payload", null)
        .doesNotContainValue("changed");
    assertThat(row(result.snapshotId()).get("purged_at")).isNotNull();
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.external_api_snapshots set purged_at=purged_at+interval '1 second' where id=?",
                    result.snapshotId()))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
  }

  private SnapshotSaveResult saveAfter(CountDownLatch start) throws Exception {
    start.await(5, TimeUnit.SECONDS);
    return service.save(command(RUN_ID, "concurrent", "{\"safe\":1}"));
  }

  private com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage saveAndParse(
      SnapshottingDetailInfoPageGateway gateway,
      UUID runId,
      DetailSourceResponse response,
      CountDownLatch start)
      throws Exception {
    start.await(5, TimeUnit.SECONDS);
    var saved = gateway.save(runId, "100", "12", 1, response);
    gateway.markParsed(saved);
    return saved;
  }

  private SnapshotSaveCommand command(UUID runId, String page, String json) {
    return new SnapshotSaveCommand(
        runId,
        new SnapshotScope("tour-api", "KorService2", "areaBasedList2", "jeju"),
        null,
        page,
        200,
        "00",
        FETCHED_AT,
        null,
        null,
        "parser-v1",
        SnapshotPayloadFormat.JSON,
        "UTF-8",
        json.getBytes(StandardCharsets.UTF_8),
        Map.of("page", page, "serviceKey", "fixture-secret"));
  }

  private void insertRun(
      UUID runId, String provider, String service, String operation, String scopeKey) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tour_api', 'fixture', ?, 'v1', 'running', ?, 'parser-v1', 'schema-v1',
                  'incremental', ?, 'sha256:fixture', ?, ?, ?)
        """,
        runId,
        operation,
        java.sql.Timestamp.from(FETCHED_AT),
        scopeKey,
        "snapshot-" + runId,
        provider,
        service);
  }

  private Map<String, Object> row(UUID snapshotId) {
    return jdbcTemplate.queryForMap(
        "select * from public.external_api_snapshots where id=?", snapshotId);
  }
}
