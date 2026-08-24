package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.retention.SnapshotRetentionCommand;
import com.timingjeju.api.application.retention.SnapshotRetentionException;
import com.timingjeju.api.application.retention.SnapshotRetentionResult;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcSnapshotRetentionRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
  private static final List<String> PROVIDERS = List.of("TAGO", "kma", "tour-api");

  @Autowired private JdbcSnapshotRetentionRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate namedJdbc;

  @BeforeEach
  @AfterEach
  void cleanFixtures() {
    jdbc.execute("drop trigger if exists issue_164_fail on public.external_api_snapshots");
    jdbc.execute("drop function if exists public.issue_164_test_fail() cascade");
    jdbc.execute("drop sequence if exists public.issue_164_trigger_reached");
    jdbc.update("delete from public.external_reference_codes where code_type='issue-164'");
    jdbc.update("delete from public.external_api_snapshots where parser_version='issue-164-v1'");
    jdbc.update("delete from public.data_import_runs where source_name='issue-164'");
  }

  @Test
  void purgeAfter_equality는_due이고_payload만_null_purgedAt은_exact_now다() {
    Fixture fixture = insertFixture(1, "tour-api", "succeeded", NOW, true, null);

    SnapshotRetentionResult result = repository.execute(command(false, 500));

    assertThat(result.candidateCount()).isOne();
    assertThat(row(fixture.snapshotId()))
        .containsEntry("raw_payload", null)
        .containsEntry("purged_at", Timestamp.from(NOW));
  }

  @Test
  void due_502건은_500과_2로_resume하고_중복처리하지_않는다() {
    for (int sequence = 10; sequence < 512; sequence++) {
      insertFixture(sequence, "tour-api", "succeeded", NOW.minusSeconds(1), true, null);
    }

    assertThat(repository.execute(command(false, 500)).purgedCount()).isEqualTo(500);
    assertThat(repository.execute(command(false, 500)).purgedCount()).isEqualTo(2);
    assertThat(repository.execute(command(false, 500)).purgedCount()).isZero();
  }

  @Test
  void running_future_alreadyPurged_TMAP은_제외한다() {
    Fixture eligible = insertFixture(600, "TAGO", "succeeded", NOW.minusSeconds(1), true, null);
    Fixture running = insertFixture(601, "TAGO", "running", NOW.minusSeconds(1), true, null);
    Fixture future = insertFixture(602, "kma", "succeeded", NOW.plusSeconds(1), true, null);
    Fixture purged =
        insertFixture(
            603, "tour-api", "succeeded", NOW.minusSeconds(2), false, NOW.minusSeconds(1));
    Fixture tmap = insertFixture(604, "tmap", "succeeded", NOW.minusSeconds(1), true, null);

    assertThat(repository.execute(command(false, 500)).purgedCount()).isOne();
    assertThat(rawPayload(eligible.snapshotId())).isNull();
    assertThat(rawPayload(running.snapshotId())).isNotNull();
    assertThat(rawPayload(future.snapshotId())).isNotNull();
    assertThat(rawPayload(purged.snapshotId())).isNull();
    assertThat(rawPayload(tmap.snapshotId())).isNotNull();
  }

  @Test
  void dryRun은_같은_candidate_count를_반환하고_mutation은_0이다() {
    Fixture first = insertFixture(700, "kma", "succeeded", NOW, true, null);
    Fixture second = insertFixture(701, "tour-api", "failed", NOW, true, null);

    SnapshotRetentionResult result = repository.execute(command(true, 500));

    assertThat(result.candidateCount()).isEqualTo(2);
    assertThat(result.purgedCount()).isZero();
    assertThat(rawPayload(first.snapshotId())).isNotNull();
    assertThat(rawPayload(second.snapshotId())).isNotNull();
  }

  @Test
  void 두_worker는_SKIPPED_LOCK으로_같은_snapshot을_중복처리하지_않는다() throws Exception {
    for (int sequence = 800; sequence < 900; sequence++) {
      insertFixture(sequence, "tour-api", "succeeded", NOW, true, null);
    }
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Integer> first = executor.submit(() -> executeAfter(start, 100));
      Future<Integer> second = executor.submit(() -> executeAfter(start, 100));
      start.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(100);
    }
    assertThat(purgedCount()).isEqualTo(100);
  }

  @Test
  void batch_중_DB오류는_전체_transaction을_rollback하고_raw_cause를_숨긴다() {
    Fixture first = insertFixture(910, "tour-api", "succeeded", NOW, true, null);
    Fixture failing = insertFixture(911, "tour-api", "succeeded", NOW, true, null);
    jdbc.execute("create sequence public.issue_164_trigger_reached start 1");
    jdbc.execute(
        "create function public.issue_164_test_fail() returns trigger language plpgsql as $$ begin "
            + "if old.id='"
            + failing.snapshotId()
            + "'::uuid then perform nextval('public.issue_164_trigger_reached'); "
            + "raise exception 'raw secret'; end if; "
            + "return new; end $$");
    jdbc.execute(
        "create trigger issue_164_fail before update on public.external_api_snapshots "
            + "for each row execute function public.issue_164_test_fail()");

    assertThatThrownBy(() -> repository.execute(command(false, 500)))
        .isInstanceOf(SnapshotRetentionException.class)
        .hasMessage("SNAPSHOT_RETENTION_UNAVAILABLE")
        .hasNoCause();
    assertThat(
            jdbc.queryForObject(
                "select last_value from public.issue_164_trigger_reached", Long.class))
        .isEqualTo(1L);
    assertThat(rawPayload(first.snapshotId())).isNotNull();
    assertThat(rawPayload(failing.snapshotId())).isNotNull();
    jdbc.execute("drop trigger issue_164_fail on public.external_api_snapshots");
    jdbc.execute("drop function public.issue_164_test_fail()");
    jdbc.execute("drop sequence public.issue_164_trigger_reached");
  }

  @Test
  void purge후에도_snapshot_run_hash_status와_normalized_lineage가_보존된다() {
    Fixture fixture = insertFixture(920, "tour-api", "succeeded", NOW, true, null);
    UUID normalizedId = uuid(5, 920);
    jdbc.update(
        "insert into public.external_reference_codes "
            + "(id,source_provider,source_service,code_type,external_code,code_name,source_snapshot_id,import_run_id) "
            + "values (?, 'tour-api', 'KorService2', 'issue-164', '920', 'fixture', ?, ?)",
        normalizedId,
        fixture.snapshotId(),
        fixture.runId());
    Map<String, Object> before = auditRow(fixture.snapshotId());

    repository.execute(command(false, 500));

    assertThat(auditRow(fixture.snapshotId())).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot_id from public.external_reference_codes where id=?",
                UUID.class,
                normalizedId))
        .isEqualTo(fixture.snapshotId());
  }

  @Test
  @Transactional
  void 많은_due_distractor에서도_partial_ordered_index를_bounded하게_사용한다() throws Exception {
    for (int sequence = 1000; sequence < 1800; sequence++) {
      insertFixture(
          sequence, "tour-api", "succeeded", NOW.minusSeconds(1800L - sequence), true, null);
    }
    jdbc.execute("analyze public.external_api_snapshots");
    jdbc.execute("set local enable_seqscan=off");
    String json =
        namedJdbc.queryForObject(
            "explain (analyze, buffers, format json) "
                + JdbcSnapshotRetentionRepository.DRY_RUN_SQL,
            parameters(32),
            (resultSet, rowNumber) -> resultSet.getString(1));

    List<JsonNode> nodes = flatten(new ObjectMapper().readTree(json));
    assertThat(nodes)
        .anySatisfy(
            node -> {
              assertThat(node.path("Index Name").asString())
                  .isEqualTo("idx_external_api_snapshots_retention_due");
              assertThat(node.path("Index Cond").asString()).contains("purge_after");
              assertThat(node.path("Scan Direction").asString()).isEqualTo("Forward");
              long visited =
                  node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong(0);
              assertThat(visited).isBetween(1L, 32L);
            })
        .noneMatch(node -> "Sort".equals(node.path("Node Type").asString()))
        .anyMatch(
            node ->
                "Limit".equals(node.path("Node Type").asString())
                    && node.path("Actual Rows").asLong() == 32);
    assertThat(json).doesNotContain("raw_payload\":", "payload_hash\":", "request_hash\":");
  }

  private int executeAfter(CountDownLatch start, int batch) throws InterruptedException {
    start.await(5, TimeUnit.SECONDS);
    return repository.execute(command(false, batch)).purgedCount();
  }

  private SnapshotRetentionCommand command(boolean dryRun, int batchSize) {
    return new SnapshotRetentionCommand(NOW, PROVIDERS, batchSize, dryRun);
  }

  private MapSqlParameterSource parameters(int batchSize) {
    return new MapSqlParameterSource()
        .addValue("now", Timestamp.from(NOW))
        .addValue("providers", PROVIDERS)
        .addValue("batchSize", batchSize);
  }

  private Fixture insertFixture(
      int sequence,
      String provider,
      String runStatus,
      Instant purgeAfter,
      boolean payloadPresent,
      Instant purgedAt) {
    UUID runId = uuid(1, sequence);
    UUID snapshotId = uuid(2, sequence);
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          finished_at, error_code, error_message, parser_version, schema_version, sync_mode,
          scope_key, request_fingerprint, idempotency_key, source_provider, source_service
        ) values (?, ?, 'issue-164', 'retentionFixture', 'v1', ?, ?, ?, ?, ?, 'issue-164-v1',
                  'issue-164-v1', 'snapshot', ?, ?, ?, ?, ?)
        """,
        runId,
        sourceKind(provider),
        runStatus,
        Timestamp.from(NOW.minusSeconds(3600)),
        "running".equals(runStatus) ? null : Timestamp.from(NOW.minusSeconds(1800)),
        "failed".equals(runStatus) ? "PROVIDER_UNAVAILABLE" : null,
        "failed".equals(runStatus) ? "sanitized" : null,
        "scope-" + sequence,
        "%064x".formatted(sequence),
        "issue-164-" + sequence,
        provider,
        service(provider));
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at, purge_after, purged_at
        ) values (?, ?, ?, ?, 'retentionFixture', ?, ?, ?, ?, 'issue-164-v1', ?, '{}'::jsonb,
                  ?::jsonb, ?, 'issue-164-v1', 'JSON', 'parsed', 'parsed', ?, ?, ?)
        """,
        snapshotId,
        runId,
        provider,
        service(provider),
        "scope-" + sequence,
        "%064x".formatted(sequence + 10_000),
        "page-" + sequence,
        Timestamp.from(NOW.minusSeconds(1000)),
        "%064x".formatted(sequence + 20_000),
        payloadPresent ? "{}" : null,
        payloadPresent ? 2 : 0,
        Timestamp.from(NOW.minusSeconds(999)),
        Timestamp.from(purgeAfter),
        purgedAt == null ? null : Timestamp.from(purgedAt));
    return new Fixture(runId, snapshotId);
  }

  private Map<String, Object> row(UUID snapshotId) {
    return jdbc.queryForMap(
        "select raw_payload,purged_at from public.external_api_snapshots where id=?", snapshotId);
  }

  private Object rawPayload(UUID snapshotId) {
    return row(snapshotId).get("raw_payload");
  }

  private int purgedCount() {
    return jdbc.queryForObject(
        "select count(*) from public.external_api_snapshots where parser_version='issue-164-v1' and purged_at is not null",
        Integer.class);
  }

  private Map<String, Object> auditRow(UUID snapshotId) {
    return jdbc.queryForMap(
        "select id,import_run_id,source_provider,source_service,source_operation,scope_key,"
            + "request_hash,payload_hash,parser_version,parse_status,parsed_at,fetched_at,purge_after "
            + "from public.external_api_snapshots where id=?",
        snapshotId);
  }

  private static List<JsonNode> flatten(JsonNode root) {
    ArrayList<JsonNode> nodes = new ArrayList<>();
    collect(root, nodes);
    return List.copyOf(nodes);
  }

  private static void collect(JsonNode node, List<JsonNode> nodes) {
    nodes.add(node);
    for (JsonNode child : node) {
      collect(child, nodes);
    }
  }

  private static String sourceKind(String provider) {
    return switch (provider) {
      case "tour-api" -> "tour_api";
      case "TAGO" -> "tago";
      case "kma" -> "weather_api";
      default -> "directions_api";
    };
  }

  private static String service(String provider) {
    return switch (provider) {
      case "tour-api" -> "KorService2";
      case "TAGO" -> "BusArrivalService";
      case "kma" -> "VilageFcstInfoService_2.0";
      default -> "routes";
    };
  }

  private static UUID uuid(int group, int sequence) {
    return UUID.fromString("16400000-0000-0000-%04d-%012d".formatted(group, sequence));
  }

  private record Fixture(UUID runId, UUID snapshotId) {}
}
