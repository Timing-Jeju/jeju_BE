package com.timingjeju.api.global.tago.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tago.route.TagoRoute;
import com.timingjeju.api.application.tago.route.TagoRouteCommitCommand;
import com.timingjeju.api.application.tago.route.TagoRouteImportCommitter;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteRepository;
import com.timingjeju.api.application.tago.route.TagoRouteStop;
import com.timingjeju.api.application.tago.route.TagoRouteStopWrite;
import com.timingjeju.api.application.tago.route.TagoRouteWrite;
import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStopPageLineage;
import com.timingjeju.api.application.tago.stop.TagoStopRepository;
import com.timingjeju.api.application.tago.stop.TagoStopWrite;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class JdbcRouteRepositoryIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID STOP_RUN = UUID.fromString("36000000-0000-0000-0000-000000000201");
  private static final UUID CITY_SNAPSHOT = UUID.fromString("36000000-0000-0000-0000-000000000202");
  private static final UUID STOP_SNAPSHOT = UUID.fromString("36000000-0000-0000-0000-000000000203");
  private static final UUID ROUTE_RUN = UUID.fromString("36000000-0000-0000-0000-000000000211");
  private static final UUID ROUTE_LIST_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000210");
  private static final UUID DETAIL_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000212");
  private static final UUID ROUTE_STOPS_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000213");
  private static final UUID OWNER = UUID.fromString("36000000-0000-0000-0000-000000000214");
  @Autowired private TagoStopRepository stopRepository;
  @Autowired private TagoRouteRepository routeRepository;
  @Autowired private TagoRouteImportCommitter committer;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertRun(
        STOP_RUN, "BusSttnInfoInqireService", "getSttnNoList", "jeju", "issue36-stop-fixture");
    insertSnapshot(
        CITY_SNAPSHOT,
        STOP_RUN,
        "BusSttnInfoInqireService",
        "getSttnNoList",
        "jeju",
        "city-1",
        "a");
    insertSnapshot(
        STOP_SNAPSHOT,
        STOP_RUN,
        "BusSttnInfoInqireService",
        "getSttnNoList",
        "jeju",
        "station-1",
        "b");
    stopRepository.apply(
        new TagoCityCode("39", "제주특별자치도"),
        stopLineage("city", CITY_SNAPSHOT),
        stopLineage("station", STOP_SNAPSHOT),
        List.of(stop("STOP-1"), stop("STOP-2")),
        STOP_RUN,
        NOW);
    jdbc.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW),
        STOP_RUN);
    insertRun(
        ROUTE_RUN,
        "BusRouteInfoInqireService",
        "getRouteNoList",
        "jeju-routes",
        "issue36-route-fixture");
    insertSnapshot(
        ROUTE_LIST_SNAPSHOT,
        ROUTE_RUN,
        "BusRouteInfoInqireService",
        "getRouteNoList",
        "jeju-routes",
        "route-list-101-1",
        "f");
    insertSnapshot(
        DETAIL_SNAPSHOT,
        ROUTE_RUN,
        "BusRouteInfoInqireService",
        "getRouteInfoIem",
        "jeju-routes",
        "route-detail-R-1",
        "c");
    insertSnapshot(
        ROUTE_STOPS_SNAPSHOT,
        ROUTE_RUN,
        "BusRouteInfoInqireService",
        "getRouteAcctoThrghSttnList",
        "jeju-routes",
        "route-stops-R-1",
        "d");
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void natural_key_replay와_freshness를_보존하고_missing_stop_batch는_전체_rollback한다() {
    var first =
        routeRepository.apply(
            List.of(routeWrite("R-1", NOW)), routeStopWrites("R-1", NOW), ROUTE_RUN, NOW);
    UUID routeId =
        jdbc.queryForObject(
            "select id from public.bus_routes where external_route_id='R-1'", UUID.class);
    var stale =
        routeRepository.apply(
            List.of(routeWrite("R-1", NOW.minusSeconds(1))),
            routeStopWrites("R-1", NOW.minusSeconds(1)),
            ROUTE_RUN,
            NOW.minusSeconds(1));

    assertThat(first.inserted()).isEqualTo(1);
    assertThat(stale.skipped()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select id from public.bus_routes where external_route_id='R-1'", UUID.class))
        .isEqualTo(routeId);
    assertThat(
            jdbc.queryForList(
                "select stop_sequence from public.route_stops where route_id=? order by stop_sequence",
                Integer.class,
                routeId))
        .containsExactly(1, 2);

    assertThatThrownBy(
            () ->
                routeRepository.apply(
                    List.of(routeWrite("R-MISSING", NOW)),
                    List.of(
                        new TagoRouteStopWrite(
                            new TagoRouteStop("39", "R-MISSING", "ABSENT", 1),
                            "R-MISSING",
                            ROUTE_STOPS_SNAPSHOT,
                            ROUTE_RUN,
                            NOW)),
                    ROUTE_RUN,
                    NOW))
        .isInstanceOf(TagoRouteImportException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.bus_routes where external_route_id='R-MISSING'",
                Integer.class))
        .isZero();
  }

  @Test
  void 같은_freshness는_동일_manifest만_true_replay하고_충돌은_모든_상태를_rollback한다() {
    routeRepository.apply(
        List.of(routeWrite("R-EQUAL", NOW)), routeStopWrites("R-EQUAL", NOW), ROUTE_RUN, NOW);
    String routeXmin =
        jdbc.queryForObject(
            "select xmin::text from public.bus_routes where external_route_id='R-EQUAL'",
            String.class);
    List<String> stopXmins =
        jdbc.queryForList(
            "select xmin::text from public.route_stops where route_id=(select id from public.bus_routes where external_route_id='R-EQUAL') order by stop_sequence",
            String.class);

    var replay =
        routeRepository.apply(
            List.of(routeWrite("R-EQUAL", NOW)), routeStopWrites("R-EQUAL", NOW), ROUTE_RUN, NOW);

    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select xmin::text from public.bus_routes where external_route_id='R-EQUAL'",
                String.class))
        .isEqualTo(routeXmin);
    assertThat(
            jdbc.queryForList(
                "select xmin::text from public.route_stops where route_id=(select id from public.bus_routes where external_route_id='R-EQUAL') order by stop_sequence",
                String.class))
        .containsExactlyElementsOf(stopXmins);

    var lease = new com.timingjeju.api.application.importing.ImportRunLease(ROUTE_RUN, OWNER, 1);
    assertEqualFreshnessConflictRollsBack(
        lease,
        routeWrite("R-EQUAL", NOW, "일반"),
        routeStopWrites("R-EQUAL", NOW),
        routeXmin,
        stopXmins);
    assertEqualFreshnessConflictRollsBack(
        lease,
        routeWrite("R-EQUAL", NOW),
        List.of(
            routeStopWrite("R-EQUAL", "STOP-2", 1, NOW),
            routeStopWrite("R-EQUAL", "STOP-1", 2, NOW)),
        routeXmin,
        stopXmins);
  }

  @Test
  void DB는_RLS_scope_index_deferred_sequence_guard를_유지한다() {
    routeRepository.apply(
        List.of(routeWrite("R-A", NOW), routeWrite("R-B", NOW)),
        java.util.stream.Stream.concat(
                routeStopWrites("R-A", NOW).stream(), routeStopWrites("R-B", NOW).stream())
            .toList(),
        ROUTE_RUN,
        NOW);

    assertThat(
            jdbc.queryForList(
                "select distinct direction_key from public.route_stops order by direction_key",
                String.class))
        .containsExactly("R-A", "R-B");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.bus_routes where route_no='101' and source_service='BusRouteInfoInqireService'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.bus_routes where import_run_id=? and source_snapshot_id=?",
                Integer.class,
                ROUTE_RUN,
                DETAIL_SNAPSHOT))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.route_stops where import_run_id=? and source_snapshot_id=?",
                Integer.class,
                ROUTE_RUN,
                ROUTE_STOPS_SNAPSHOT))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.external_api_snapshots where import_run_id=? and parse_status='parsed'",
                Integer.class,
                ROUTE_RUN))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForList(
                "select source_operation from public.external_api_snapshots where import_run_id=? order by source_operation",
                String.class,
                ROUTE_RUN))
        .containsExactly("getRouteAcctoThrghSttnList", "getRouteInfoIem", "getRouteNoList");
    assertThat(
            jdbc.queryForObject(
                "select version from public.data_import_checkpoints where source_provider='TAGO' and source_service='BusRouteInfoInqireService' and source_operation='getRouteNoList' and scope_key='jeju-routes'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select relrowsecurity from pg_class where oid='public.route_stops'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname='public' and indexname in ('idx_bus_routes_tago_scope_freshness','idx_route_stops_scope_direction_sequence')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_trigger where tgname='trg_route_stops_sequence_contiguous' and tgdeferrable and tginitdeferred",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 같은_natural_key_동시_upsert는_한_route와_한_direction_sequence만_남긴다() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var calls =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await(10, TimeUnit.SECONDS);
                            return routeRepository.apply(
                                List.of(routeWrite("R-1", NOW)),
                                routeStopWrites("R-1", NOW),
                                ROUTE_RUN,
                                NOW);
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var results =
          List.of(calls.get(0).get(30, TimeUnit.SECONDS), calls.get(1).get(30, TimeUnit.SECONDS));
      assertThat(results).extracting(result -> result.inserted()).containsExactlyInAnyOrder(1, 0);
    } finally {
      executor.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.bus_routes where external_route_id='R-1'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.route_stops route_stop join public.bus_routes route on route.id=route_stop.route_id where route.external_route_id='R-1' and route_stop.direction_key='R-1'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void stale_checkpoint_CAS는_normalized_write와_run_success를_같이_rollback한다() {
    var lease = new com.timingjeju.api.application.importing.ImportRunLease(ROUTE_RUN, OWNER, 1);
    var lineage =
        new com.timingjeju.api.application.tago.route.TagoRouteLineage(
            "route-stops", "R-CAS", 1, 2, ROUTE_STOPS_SNAPSHOT, "f".repeat(64), NOW);

    assertThatThrownBy(
            () ->
                committer.commit(
                    new TagoRouteCommitCommand(
                        lease,
                        99,
                        List.of(routeWrite("R-CAS", NOW)),
                        routeStopWrites("R-CAS", NOW),
                        List.of(lineage))))
        .isInstanceOf(com.timingjeju.api.application.importing.ImportCheckpointException.class);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.bus_routes where external_route_id='R-CAS'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, ROUTE_RUN))
        .isEqualTo("running");
  }

  private TagoRouteWrite routeWrite(String routeId, Instant observedAt) {
    return routeWrite(routeId, observedAt, "급행");
  }

  private TagoRouteWrite routeWrite(String routeId, Instant observedAt, String routeType) {
    return new TagoRouteWrite(
        new TagoRoute("39", routeId, "101", routeType, "공항", "성산", routeId),
        DETAIL_SNAPSHOT,
        ROUTE_RUN,
        observedAt);
  }

  private List<TagoRouteStopWrite> routeStopWrites(String routeId, Instant observedAt) {
    return List.of(
        routeStopWrite(routeId, "STOP-1", 1, observedAt),
        routeStopWrite(routeId, "STOP-2", 2, observedAt));
  }

  private TagoRouteStopWrite routeStopWrite(
      String routeId, String nodeId, int sequence, Instant observedAt) {
    return new TagoRouteStopWrite(
        new TagoRouteStop("39", routeId, nodeId, sequence),
        routeId,
        ROUTE_STOPS_SNAPSHOT,
        ROUTE_RUN,
        observedAt);
  }

  private void assertEqualFreshnessConflictRollsBack(
      com.timingjeju.api.application.importing.ImportRunLease lease,
      TagoRouteWrite route,
      List<TagoRouteStopWrite> stops,
      String routeXmin,
      List<String> stopXmins) {
    assertThatThrownBy(
            () ->
                committer.commit(
                    new TagoRouteCommitCommand(lease, 0, List.of(route), stops, List.of())))
        .isInstanceOf(TagoRouteImportException.class);
    assertThat(
            jdbc.queryForObject(
                "select xmin::text from public.bus_routes where external_route_id='R-EQUAL'",
                String.class))
        .isEqualTo(routeXmin);
    assertThat(
            jdbc.queryForList(
                "select xmin::text from public.route_stops where route_id=(select id from public.bus_routes where external_route_id='R-EQUAL') order by stop_sequence",
                String.class))
        .containsExactlyElementsOf(stopXmins);
    assertThat(
            jdbc.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, ROUTE_RUN))
        .isEqualTo("running");
    assertThat(
            jdbc.queryForObject(
                "select version from public.data_import_checkpoints where source_provider='TAGO' and source_service='BusRouteInfoInqireService' and source_operation='getRouteNoList' and scope_key='jeju-routes'",
                Long.class))
        .isZero();
  }

  private TagoStopWrite stop(String nodeId) {
    return new TagoStopWrite(
        new TagoStation("39", nodeId, nodeId, nodeId, 126.5, 33.5), STOP_SNAPSHOT, STOP_RUN, NOW);
  }

  private TagoStopPageLineage stopLineage(String kind, UUID snapshot) {
    return new TagoStopPageLineage(
        kind, kind.equals("city") ? 0 : 1, 2, snapshot, "e".repeat(64), NOW);
  }

  private void insertRun(UUID run, String service, String operation, String scope, String name) {
    jdbc.update(
        "insert into public.data_import_runs (id, source_kind, source_name, source_operation, data_version, status, started_at, parser_version, schema_version, sync_mode, scope_key, request_fingerprint, idempotency_key, source_provider, source_service, owner_token, fencing_token) values (?, 'tago', ?, ?, '2026', 'running', ?, 'tago-route-v1', 'tago-route-v1', 'full', ?, ?, ?, 'TAGO', ?, ?, 1)",
        run,
        name,
        operation,
        Timestamp.from(NOW),
        scope,
        run.toString().replace("-", "") + "0".repeat(32),
        name + '-' + run,
        service,
        OWNER);
  }

  private void insertSnapshot(
      UUID snapshot,
      UUID run,
      String service,
      String operation,
      String scope,
      String pageKey,
      String hashPrefix) {
    String hash = hashPrefix.repeat(64);
    jdbc.update(
        "insert into public.external_api_snapshots (id, import_run_id, source_provider, source_service, source_operation, scope_key, request_hash, page_key, fetched_at, parser_version, payload_hash, request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version, payload_format, initial_parse_status, parse_status, parsed_at) values (?, ?, 'TAGO', ?, ?, ?, ?, ?, ?, 'tago-route-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', 'parsed', 'parsed', ?)",
        snapshot,
        run,
        service,
        operation,
        scope,
        hash,
        pageKey,
        Timestamp.from(NOW),
        hash,
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbc.update("delete from public.route_stops where source_provider='TAGO' and city_code='39'");
    jdbc.update(
        "delete from public.bus_routes where source_provider='TAGO' and source_service='BusRouteInfoInqireService'");
    jdbc.update(
        "delete from public.bus_stops where source_provider='TAGO' and source_service='BusSttnInfoInqireService' and node_id in ('STOP-1','STOP-2')");
    jdbc.update(
        "delete from public.external_reference_codes where source_provider='TAGO' and source_service='BusSttnInfoInqireService' and external_code='39'");
    jdbc.update(
        "delete from public.external_api_snapshots where import_run_id in (?, ?)",
        STOP_RUN,
        ROUTE_RUN);
    jdbc.update("delete from public.data_import_runs where id in (?, ?)", STOP_RUN, ROUTE_RUN);
  }
}
