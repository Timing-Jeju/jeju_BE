package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcTagoArrivalRepositoryIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID STOP = UUID.fromString("39000000-0000-0000-0000-000000000001");
  private static final UUID REF_RUN = UUID.fromString("39000000-0000-0000-0000-000000000002");
  private static final UUID REF_SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000003");
  private static final UUID RUN = UUID.fromString("39000000-0000-0000-0000-000000000010");
  private static final UUID OWNER = UUID.fromString("39000000-0000-0000-0000-000000000011");
  private static final UUID SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000012");
  private static final UUID NEW_RUN = UUID.fromString("39000000-0000-0000-0000-000000000020");
  private static final UUID NEW_SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000021");
  private static final UUID OLD_RUN = UUID.fromString("39000000-0000-0000-0000-000000000030");
  private static final UUID OLD_SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000031");
  private static final UUID COLLISION_RUN = UUID.fromString("39000000-0000-0000-0000-000000000040");
  private static final UUID COLLISION_SNAPSHOT =
      UUID.fromString("39000000-0000-0000-0000-000000000041");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 1);
  private static final TagoArrivalCacheKey KEY = TagoArrivalCacheKey.tago(STOP, "39", "JEP123");
  private static final TagoArrival ARRIVAL =
      new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4);
  private static final TagoArrival SECOND_ARRIVAL =
      new TagoArrival("JER002", "202", "지선버스", "저상버스", 600, 8);
  private static final byte[] EXACT =
      " {\"response\": {\"body\": {\"arrtime\":1.00}}} \n".getBytes(StandardCharsets.UTF_8);

  @Autowired private TagoArrivalRepository repository;
  @Autowired private TagoArrivalCommitter committer;
  @Autowired private TagoArrivalFlightCoordinator coordinator;
  @Autowired private TagoArrivalFlightStore flightStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    clean();
    insertStopReference();
    insertArrivalRunAndSnapshot();
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void parsed_raw와_run_lineage로_observed_expires_arrival_remaining을_append하고_latest를_복원한다() {
    TagoArrivalCommitCommand batch = command(List.of(ARRIVAL, SECOND_ARRIVAL));
    assertThat(committer.commit(batch).insertedCount()).isEqualTo(2);

    assertThat(
            jdbcTemplate.queryForList(
                "select source_provider, source_service, source_operation, import_run_id, source_snapshot_id, estimated_arrival_seconds, remaining_stops from public.bus_arrival_snapshots where stop_id=? order by estimated_arrival_seconds",
                STOP))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("source_provider", "TAGO");
              assertThat(row).containsEntry("source_service", "ArvlInfoInqireService");
              assertThat(row)
                  .containsEntry("source_operation", TagoArrivalImportSessionAdapter.OPERATION);
              assertThat(row).containsEntry("import_run_id", RUN);
              assertThat(row).containsEntry("source_snapshot_id", SNAPSHOT);
            });
    assertThat(
            jdbcTemplate.queryForObject(
                "select parse_status from public.external_api_snapshots where id=?",
                String.class,
                SNAPSHOT))
        .isEqualTo("parsed");
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, RUN))
        .isEqualTo("succeeded");
    assertThat(repository.findLatest(KEY))
        .contains(
            new TagoArrivalSnapshot(
                List.of(ARRIVAL, SECOND_ARRIVAL), NOW, NOW.plusSeconds(25), false, RUN, SNAPSHOT));
  }

  @Test
  void stop_node_scope_mismatch는_parsed전이와_normalized와_run_success를_전부_rollback한다() {
    TagoArrivalCommitCommand mismatch =
        new TagoArrivalCommitCommand(
            LEASE,
            TagoArrivalCacheKey.tago(STOP, "39", "WRONG-NODE"),
            List.of(ARRIVAL),
            saved(),
            NOW,
            NOW.plusSeconds(25));

    assertThatThrownBy(() -> committer.commit(mismatch)).isInstanceOf(TagoArrivalException.class);

    assertThat(rowCount()).isZero();
    assertThat(status("external_api_snapshots", "parse_status", SNAPSHOT)).isEqualTo("received");
    assertThat(status("data_import_runs", "status", RUN)).isEqualTo("running");
  }

  @Test
  void DB도_oversized_arrival_seconds를_거부한다() {
    jdbcTemplate.update(
        "update public.external_api_snapshots set parse_status='parsed', parsed_at=now() where id=?",
        SNAPSHOT);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into public.bus_arrival_snapshots (
                      stop_id, external_route_id, route_no, estimated_arrival_seconds,
                      remaining_stops, observed_at, expires_at, source_provider, source_service,
                      source_operation, import_run_id, source_snapshot_id, raw_payload
                    ) values (?, 'JER001', '201', 86401, 4, ?, ?, 'TAGO',
                              'ArvlInfoInqireService', ?, ?, ?, '{}'::jsonb)
                    """,
                    STOP,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW.plusSeconds(25)),
                    TagoArrivalImportSessionAdapter.OPERATION,
                    RUN,
                    SNAPSHOT))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(rowCount()).isZero();
  }

  @Test
  void 서로_다른_instance의_동시_20요청도_DB_lock후_history를_재확인해_append는_한번이다() throws Exception {
    Instant databaseNow = databaseNow();
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var loader =
        (com.timingjeju.api.application.tago.arrival.TagoArrivalLoader)
            key -> {
              loads.incrementAndGet();
              entered.countDown();
              await(release);
              committer.commit(commandAt(databaseNow));
              return new TagoArrivalSnapshot(
                  List.of(ARRIVAL), databaseNow, databaseNow.plusSeconds(25), false, RUN, SNAPSHOT);
            };
    List<TagoArrivalCacheService> caches =
        List.of(
            new TagoArrivalCacheService(
                loader,
                repository,
                coordinator,
                Clock.fixed(databaseNow, ZoneOffset.UTC),
                Duration.ofSeconds(25),
                Duration.ofMinutes(2)),
            new TagoArrivalCacheService(
                loader,
                repository,
                coordinator,
                Clock.fixed(databaseNow, ZoneOffset.UTC),
                Duration.ofSeconds(25),
                Duration.ofMinutes(2)));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(index -> executor.submit(() -> caches.get(index % caches.size()).get(KEY)))
              .toList();
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      for (var request : requests) assertThat(request.get(30, TimeUnit.SECONDS).stale()).isFalse();
    }

    assertThat(loads).hasValue(1);
    assertThat(rowCount()).isEqualTo(1);
  }

  @Test
  void newer와_older_batch가_공존해도_latest는_newer이고_같은_observed의_다른_lineage는_거부한다() {
    removeUnusedDefaultArrivalContext();
    parsedContext(NEW_RUN, NEW_SNAPSHOT, NOW.plusSeconds(10));
    repository.append(command(NEW_RUN, NEW_SNAPSHOT, NOW.plusSeconds(10), List.of(SECOND_ARRIVAL)));
    finishRun(NEW_RUN);
    parsedContext(OLD_RUN, OLD_SNAPSHOT, NOW.minusSeconds(10));
    repository.append(command(OLD_RUN, OLD_SNAPSHOT, NOW.minusSeconds(10), List.of(ARRIVAL)));
    finishRun(OLD_RUN);
    parsedContext(COLLISION_RUN, COLLISION_SNAPSHOT, NOW.plusSeconds(10));

    assertThat(repository.findLatest(KEY))
        .contains(
            new TagoArrivalSnapshot(
                List.of(SECOND_ARRIVAL),
                NOW.plusSeconds(10),
                NOW.plusSeconds(35),
                false,
                NEW_RUN,
                NEW_SNAPSHOT));
    assertThatThrownBy(
            () ->
                repository.append(
                    command(
                        COLLISION_RUN, COLLISION_SNAPSHOT, NOW.plusSeconds(10), List.of(ARRIVAL))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(rowCount()).isEqualTo(2);
  }

  @Test
  void RLS와_role_ACL및_covering_freshness_index_plan을_유지한다() {
    committer.commit(command());

    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.bus_arrival_snapshots'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('anon', 'public.bus_arrival_snapshots', 'select')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('service_role', 'public.bus_arrival_snapshots', 'select')",
                Boolean.class))
        .isTrue();

    jdbcTemplate.execute("set enable_seqscan=off");
    try {
      String plan =
          String.join(
              "\n",
              jdbcTemplate.queryForList(
                  """
                  explain (costs off)
                  select observed_at, expires_at, import_run_id, source_snapshot_id
                  from public.bus_arrival_snapshots
                  where source_provider='TAGO' and source_service='ArvlInfoInqireService'
                    and stop_id='39000000-0000-0000-0000-000000000001'::uuid
                    and octet_length(source_provider) <= 128
                    and octet_length(source_service) <= 128
                  order by observed_at desc limit 1
                  """,
                  String.class));
      assertThat(plan).contains("idx_bus_arrivals_source_stop_freshness");
    } finally {
      jdbcTemplate.execute("reset enable_seqscan");
    }
  }

  @Test
  void nested_commit후_final_flight_CAS가_0이면_snapshot_run_arrival이_전부_rollback된다() {
    String fingerprint = "d".repeat(64);
    TagoArrivalFlightDecision leader =
        flightStore.observeOrClaim(
            fingerprint, new UUID(39L, 90L), Duration.ofSeconds(12), Duration.ofSeconds(12));

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      flightStore.lockCurrent(leader.lease());
                      committer.commit(command());
                      jdbcTemplate.update(
                          """
                          update public.tago_arrival_flights
                          set lease_expires_at=clock_timestamp()-interval '1 second',
                              updated_at=clock_timestamp()-interval '2 seconds'
                          where fingerprint=?
                          """,
                          fingerprint);
                      if (!flightStore.completeSuccess(
                          leader.lease(), NOW.plusSeconds(25), Duration.ofSeconds(25))) {
                        throw TagoArrivalException.dataUnavailable();
                      }
                    }))
        .isInstanceOf(TagoArrivalException.class);

    assertThat(rowCount()).isZero();
    assertThat(status("external_api_snapshots", "parse_status", SNAPSHOT)).isEqualTo("received");
    assertThat(status("data_import_runs", "status", RUN)).isEqualTo("running");
  }

  private TagoArrivalCommitCommand command() {
    return command(List.of(ARRIVAL));
  }

  private TagoArrivalCommitCommand command(List<TagoArrival> arrivals) {
    return new TagoArrivalCommitCommand(LEASE, KEY, arrivals, saved(), NOW, NOW.plusSeconds(25));
  }

  private TagoArrivalCommitCommand commandAt(Instant observedAt) {
    return new TagoArrivalCommitCommand(
        LEASE, KEY, List.of(ARRIVAL), savedAt(observedAt), observedAt, observedAt.plusSeconds(25));
  }

  private TagoArrivalCommitCommand command(
      UUID run, UUID snapshot, Instant observedAt, List<TagoArrival> arrivals) {
    ImportRunLease lease = new ImportRunLease(run, owner(run), 1);
    SavedTagoArrivalSnapshot saved =
        new SavedTagoArrivalSnapshot(
            new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON),
            snapshot,
            "a".repeat(64),
            observedAt,
            observedAt.plusSeconds(25),
            false,
            SnapshotStatus.PARSED);
    return new TagoArrivalCommitCommand(
        lease, KEY, arrivals, saved, observedAt, observedAt.plusSeconds(25));
  }

  private SavedTagoArrivalSnapshot saved() {
    return savedAt(NOW);
  }

  private SavedTagoArrivalSnapshot savedAt(Instant observedAt) {
    return new SavedTagoArrivalSnapshot(
        new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON),
        SNAPSHOT,
        "a".repeat(64),
        observedAt,
        observedAt.plusSeconds(25),
        false,
        SnapshotStatus.RECEIVED);
  }

  private Instant databaseNow() {
    return jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
  }

  private void insertStopReference() {
    insertRun(
        REF_RUN,
        UUID.fromString("39000000-0000-0000-0000-000000000004"),
        "BusSttnInfoInqireService",
        "getSttnNoList",
        "jeju",
        "succeeded");
    insertSnapshot(
        REF_SNAPSHOT, REF_RUN, "BusSttnInfoInqireService", "getSttnNoList", "jeju", "parsed", NOW);
    jdbcTemplate.update(
        """
        insert into public.bus_stops (
          id, external_stop_id, node_id, node_name, location, source_provider,
          source_service, city_code, import_run_id, source_snapshot_id, last_seen_at, stale
        ) values (?, 'JEP123', 'JEP123', '제주공항',
                  ST_SetSRID(ST_MakePoint(126.493, 33.507), 4326)::geography,
                  'TAGO', 'BusSttnInfoInqireService', '39', ?, ?, ?, false)
        """,
        STOP,
        REF_RUN,
        REF_SNAPSHOT,
        Timestamp.from(NOW));
  }

  private void insertArrivalRunAndSnapshot() {
    insertRun(
        RUN,
        OWNER,
        "ArvlInfoInqireService",
        TagoArrivalImportSessionAdapter.OPERATION,
        TagoArrivalImportSessionAdapter.scopeKey(KEY),
        "running");
    insertSnapshot(
        SNAPSHOT,
        RUN,
        "ArvlInfoInqireService",
        TagoArrivalImportSessionAdapter.OPERATION,
        TagoArrivalImportSessionAdapter.scopeKey(KEY),
        "received",
        NOW);
  }

  private void parsedContext(UUID run, UUID snapshot, Instant observedAt) {
    insertRun(
        run,
        owner(run),
        "ArvlInfoInqireService",
        TagoArrivalImportSessionAdapter.OPERATION,
        TagoArrivalImportSessionAdapter.scopeKey(KEY),
        "running");
    insertSnapshot(
        snapshot,
        run,
        "ArvlInfoInqireService",
        TagoArrivalImportSessionAdapter.OPERATION,
        TagoArrivalImportSessionAdapter.scopeKey(KEY),
        "parsed",
        observedAt);
  }

  private void insertRun(
      UUID run, UUID owner, String service, String operation, String scope, String status) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          finished_at, parser_version, schema_version, sync_mode, scope_key,
          request_fingerprint, idempotency_key, source_provider, source_service,
          owner_token, fencing_token
        ) values (?, 'tago', 'issue-39-fixture', ?, 'live', ?, ?, ?,
                  'tago-arrival-v1', 'tago-arrival-v1', 'snapshot', ?, ?, ?, 'TAGO', ?, ?, 1)
        """,
        run,
        operation,
        status,
        Timestamp.from(NOW),
        "succeeded".equals(status) ? Timestamp.from(NOW) : null,
        scope,
        run.toString().replace("-", "") + "0".repeat(32),
        "issue-39-" + run,
        service,
        owner);
  }

  private void insertSnapshot(
      UUID snapshot,
      UUID run,
      String service,
      String operation,
      String scope,
      String status,
      Instant observedAt) {
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, expires_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'TAGO', ?, ?, ?, ?, 'arrival', ?, ?, 'tago-arrival-v1', ?,
                  '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON', ?, ?, ?)
        """,
        snapshot,
        run,
        service,
        operation,
        scope,
        snapshot.toString().replace("-", "") + "0".repeat(32),
        Timestamp.from(observedAt),
        Timestamp.from(observedAt.plusSeconds(25)),
        "a".repeat(64),
        status,
        status,
        "parsed".equals(status) ? Timestamp.from(observedAt) : null);
  }

  private int rowCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from public.bus_arrival_snapshots where stop_id=?", Integer.class, STOP);
  }

  private void removeUnusedDefaultArrivalContext() {
    assertThat(rowCount()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_api_snapshots where id=? and import_run_id=?",
                Integer.class,
                SNAPSHOT,
                RUN))
        .isEqualTo(1);
    jdbcTemplate.update("delete from public.external_api_snapshots where id=?", SNAPSHOT);
    assertThat(jdbcTemplate.update("delete from public.data_import_runs where id=?", RUN))
        .isEqualTo(1);
  }

  private void finishRun(UUID run) {
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(30)),
        run);
  }

  private static UUID owner(UUID run) {
    return UUID.nameUUIDFromBytes(("issue-39-owner:" + run).getBytes(StandardCharsets.UTF_8));
  }

  private String status(String table, String column, UUID id) {
    if ("external_api_snapshots".equals(table)) {
      return jdbcTemplate.queryForObject(
          "select parse_status from public.external_api_snapshots where id=?", String.class, id);
    }
    return jdbcTemplate.queryForObject(
        "select status from public.data_import_runs where id=?", String.class, id);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tago_arrival_flights");
    jdbcTemplate.update("delete from public.bus_arrival_snapshots where stop_id=?", STOP);
    jdbcTemplate.update("delete from public.bus_stops where id=?", STOP);
    jdbcTemplate.update(
        "delete from public.external_api_snapshots where import_run_id in (?, ?, ?, ?, ?)",
        REF_RUN,
        RUN,
        NEW_RUN,
        OLD_RUN,
        COLLISION_RUN);
    jdbcTemplate.update(
        "delete from public.data_import_runs where id in (?, ?, ?, ?, ?)",
        REF_RUN,
        RUN,
        NEW_RUN,
        OLD_RUN,
        COLLISION_RUN);
  }
}
