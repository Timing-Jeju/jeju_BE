package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.datahealth.ProviderDataHealthAttemptStatus;
import com.timingjeju.api.application.datahealth.ProviderDataHealthHistory;
import com.timingjeju.api.application.datahealth.ProviderDataHealthKey;
import com.timingjeju.api.application.datahealth.ProviderDataHealthPolicy;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReader;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JdbcCompletedProviderDataHealthReaderIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final ProviderDataHealthKey KEY =
      new ProviderDataHealthKey("tour-api", "KorService2", "areaBasedSyncList2");
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

  @Autowired private ProviderDataHealthReader reader;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate namedJdbc;

  @Test
  void parsed_succeeded_history의_attempt_success_facts를_읽는다() {
    insertRun(1, KEY, "succeeded", NOW.minusSeconds(60), NOW.minusSeconds(50));
    insertSnapshot(1, KEY, "parsed", NOW.minusSeconds(55), NOW.minusSeconds(120));

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.latestStatus()).isEqualTo(ProviderDataHealthAttemptStatus.SUCCEEDED);
    assertThat(history.lastAttemptAt()).isEqualTo(NOW.minusSeconds(50));
    assertThat(history.lastSuccessAt()).isEqualTo(NOW.minusSeconds(50));
    assertThat(history.factsAsOf()).isEqualTo(NOW.minusSeconds(120));
  }

  @Test
  void latest_failed와_이전_success_facts를_동시에_보존한다() {
    insertRun(2, KEY, "succeeded", NOW.minusSeconds(90), NOW.minusSeconds(80));
    insertSnapshot(2, KEY, "parsed", NOW.minusSeconds(85), NOW.minusSeconds(100));
    insertRun(3, KEY, "failed", NOW.minusSeconds(20), NOW.minusSeconds(10));
    insertSnapshot(3, KEY, "rejected", NOW.minusSeconds(15), null);

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.latestStatus()).isEqualTo(ProviderDataHealthAttemptStatus.FAILED);
    assertThat(history.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(history.lastSuccessAt()).isEqualTo(NOW.minusSeconds(80));
    assertThat(history.factsAsOf()).isEqualTo(NOW.minusSeconds(100));
  }

  @Test
  void 같은_startedAt은_UUID_desc로_latest_attempt를_결정한다() {
    Instant startedAt = NOW.minusSeconds(30);
    insertRun(10, KEY, "succeeded", startedAt, NOW.minusSeconds(20));
    insertSnapshot(10, KEY, "parsed", NOW.minusSeconds(25), NOW.minusSeconds(60));
    insertRun(11, KEY, "failed", startedAt, NOW.minusSeconds(10));

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.latestStatus()).isEqualTo(ProviderDataHealthAttemptStatus.FAILED);
    assertThat(history.lastAttemptAt()).isEqualTo(NOW.minusSeconds(10));
  }

  @Test
  void rejected와_tombstoned_snapshot은_last_success_facts에_포함하지_않는다() {
    insertRun(20, KEY, "succeeded", NOW.minusSeconds(100), NOW.minusSeconds(90));
    insertSnapshot(20, KEY, "parsed", NOW.minusSeconds(95), NOW.minusSeconds(120));
    insertSnapshot(21, 20, KEY, "tombstoned", NOW.minusSeconds(80), null);
    insertRun(21, KEY, "failed", NOW.minusSeconds(30), NOW.minusSeconds(20));
    insertSnapshot(22, 21, KEY, "rejected", NOW.minusSeconds(25), null);

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.latestStatus()).isEqualTo(ProviderDataHealthAttemptStatus.FAILED);
    assertThat(history.factsAsOf()).isEqualTo(NOW.minusSeconds(120));
  }

  @Test
  void newer_succeeded에_valid_parsed_facts가_없으면_older_valid_success를_선택한다() {
    insertRun(23, KEY, "succeeded", NOW.minusSeconds(100), NOW.minusSeconds(90));
    insertSnapshot(23, KEY, "parsed", NOW.minusSeconds(95), NOW.minusSeconds(120));
    insertRun(24, KEY, "succeeded", NOW.minusSeconds(30), NOW.minusSeconds(20));
    insertSnapshot(24, KEY, "tombstoned", NOW.minusSeconds(25), null);

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.lastAttemptAt()).isEqualTo(NOW.minusSeconds(20));
    assertThat(history.lastSuccessAt()).isEqualTo(NOW.minusSeconds(90));
    assertThat(history.factsAsOf()).isEqualTo(NOW.minusSeconds(120));
  }

  @Test
  void valid_facts가_recent_terminal_window_32번째면_선택한다() {
    insertRun(60, KEY, "succeeded", NOW.minusSeconds(320), NOW.minusSeconds(319));
    insertSnapshot(60, KEY, "parsed", NOW.minusSeconds(319), NOW.minusSeconds(400));
    insertRecentInvalidTerminals(61, 31);

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.lastSuccessAt()).isEqualTo(NOW.minusSeconds(319));
    assertThat(history.factsAsOf()).isEqualTo(NOW.minusSeconds(400));
  }

  @Test
  void valid_facts가_recent_terminal_window_33번째면_NO_RECENT로_truthful하게_제외한다() {
    insertRun(100, KEY, "succeeded", NOW.minusSeconds(330), NOW.minusSeconds(329));
    insertSnapshot(100, KEY, "parsed", NOW.minusSeconds(329), NOW.minusSeconds(400));
    insertRecentInvalidTerminals(101, 32);

    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(history.lastSuccessAt()).isNull();
    assertThat(history.factsAsOf()).isNull();
    assertThat(
            com.timingjeju.api.application.datahealth.ProviderDataHealthEvaluator.evaluate(
                    new ProviderDataHealthPolicy(KEY, Duration.ofHours(1), true), history, NOW)
                .status())
        .isEqualTo(ProviderDataHealthStatus.NO_RECENT_VALID_FACTS);
  }

  @Test
  void allowlist_밖_history는_결과에_섞이지_않는다() {
    ProviderDataHealthKey outside = new ProviderDataHealthKey("tmap", "routes", "transitRoute");
    insertRun(30, outside, "succeeded", NOW.minusSeconds(30), NOW.minusSeconds(20));
    insertSnapshot(30, outside, "parsed", NOW.minusSeconds(25), NOW.minusSeconds(40));

    assertThat(reader.read(List.of(KEY))).isEmpty();
  }

  @Test
  void actual_DB_history의_TTL_equality는_STALE이다() {
    insertRun(40, KEY, "succeeded", NOW.minusSeconds(70), NOW.minusSeconds(60));
    insertSnapshot(40, KEY, "parsed", NOW.minusSeconds(65), NOW.minusSeconds(120));
    ProviderDataHealthHistory history = reader.read(List.of(KEY)).getFirst();

    assertThat(
            com.timingjeju.api.application.datahealth.ProviderDataHealthEvaluator.evaluate(
                    new ProviderDataHealthPolicy(KEY, Duration.ofSeconds(120), true), history, NOW)
                .status())
        .isEqualTo(ProviderDataHealthStatus.STALE);
  }

  @Test
  @Transactional
  void 많은_distractor에서도_ordered_covering_index로_bounded_row만_읽는다() throws Exception {
    for (int sequence = 1000; sequence < 1400; sequence++) {
      insertRun(
          sequence, KEY, "failed", NOW.minusSeconds(sequence), NOW.minusSeconds(sequence - 1));
    }
    insertRun(50, KEY, "succeeded", NOW.minusSeconds(30), NOW.minusSeconds(20));
    insertSnapshot(50, KEY, "parsed", NOW.minusSeconds(25), NOW.minusSeconds(40));
    jdbc.execute("analyze public.data_import_runs");
    jdbc.execute("set local enable_seqscan=off");

    String planJson =
        namedJdbc.queryForObject(
            "explain (analyze, buffers, format json) "
                + JdbcCompletedProviderDataHealthReader.SELECT_HEALTH,
            JdbcCompletedProviderDataHealthReader.parameters(List.of(KEY)),
            (resultSet, rowNumber) -> resultSet.getString(1));

    JsonNode plan = new ObjectMapper().readTree(planJson);
    List<JsonNode> nodes = flatten(plan);
    List<JsonNode> indexScans =
        nodes.stream()
            .filter(
                node ->
                    "idx_data_import_runs_completed_health_latest"
                        .equals(node.path("Index Name").asString()))
            .toList();
    assertThat(indexScans)
        .isNotEmpty()
        .allSatisfy(
            node -> {
              assertThat(node.path("Index Cond").asString())
                  .contains("source_provider", "source_service", "source_operation");
              long visitedRows =
                  node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong(0);
              assertThat(node.path("Actual Loops").asLong()).isPositive();
              assertThat(visitedRows).isBetween(1L, 32L);
            });
    assertThat(nodes)
        .noneMatch(node -> "Sort".equals(node.path("Node Type").asString()))
        .anyMatch(
            node ->
                "Limit".equals(node.path("Node Type").asString())
                    && node.path("Actual Rows").asLong() == 32);
    assertThat(planJson)
        .doesNotContain("raw_payload", "error_message", "request_metadata_redacted");
  }

  private static List<JsonNode> flatten(JsonNode root) {
    java.util.ArrayList<JsonNode> nodes = new java.util.ArrayList<>();
    collect(root, nodes);
    return List.copyOf(nodes);
  }

  private static void collect(JsonNode node, List<JsonNode> nodes) {
    nodes.add(node);
    for (JsonNode child : node) {
      collect(child, nodes);
    }
  }

  private void insertRun(
      int sequence,
      ProviderDataHealthKey key,
      String status,
      Instant startedAt,
      Instant finishedAt) {
    String errorCode = "succeeded".equals(status) ? null : "PROVIDER_UNAVAILABLE";
    String errorMessage = "succeeded".equals(status) ? null : "sanitized fixture failure";
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status,
          started_at, finished_at, error_code, error_message,
          parser_version, schema_version, sync_mode, scope_key,
          request_fingerprint, idempotency_key, source_provider, source_service,
          owner_token, fencing_token
        ) values (?, ?, 'issue-160', ?, 'v1', ?, ?, ?, ?, ?,
                  'issue-160-v1', 'issue-160-v1', 'snapshot', ?, ?, ?, ?, ?, ?, 1)
        """,
        runId(sequence),
        sourceKind(key),
        key.operation(),
        status,
        Timestamp.from(startedAt),
        Timestamp.from(finishedAt),
        errorCode,
        errorMessage,
        "scope-" + sequence,
        "%064x".formatted(sequence),
        "issue-160-" + sequence,
        key.provider(),
        key.service(),
        ownerId(sequence));
  }

  private void insertRecentInvalidTerminals(int firstSequence, int count) {
    for (int offset = 0; offset < count; offset++) {
      int sequence = firstSequence + offset;
      String status =
          switch (offset % 4) {
            case 0 -> "failed";
            case 1 -> "partial";
            case 2 -> "cancelled";
            default -> "succeeded";
          };
      Instant startedAt = NOW.minusSeconds(300L - offset * 5L);
      insertRun(sequence, KEY, status, startedAt, startedAt.plusSeconds(1));
      if ("succeeded".equals(status)) {
        insertSnapshot(sequence, KEY, "tombstoned", startedAt.plusSeconds(1), null);
      }
    }
  }

  private void insertSnapshot(
      int sequence,
      ProviderDataHealthKey key,
      String parseStatus,
      Instant fetchedAt,
      Instant sourceModifiedAt) {
    insertSnapshot(sequence, sequence, key, parseStatus, fetchedAt, sourceModifiedAt);
  }

  private void insertSnapshot(
      int sequence,
      int runSequence,
      ProviderDataHealthKey key,
      String parseStatus,
      Instant fetchedAt,
      Instant sourceModifiedAt) {
    boolean parsed = "parsed".equals(parseStatus);
    boolean rejected = "rejected".equals(parseStatus);
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, source_modified_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, initial_error_code, parse_status, parsed_at, error_code
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'issue-160-v1', ?, '{}'::jsonb,
                  '{}'::jsonb, 2, 'test-v1', 'JSON', ?, ?, ?, ?, ?)
        """,
        snapshotId(sequence),
        runId(runSequence),
        key.provider(),
        key.service(),
        key.operation(),
        "scope-" + runSequence,
        "%064x".formatted(sequence + 1000),
        "page-" + sequence,
        Timestamp.from(fetchedAt),
        sourceModifiedAt == null ? null : Timestamp.from(sourceModifiedAt),
        "%064x".formatted(sequence + 2000),
        parseStatus,
        rejected ? "INVALID_PROVIDER_RESPONSE" : null,
        parseStatus,
        parsed ? Timestamp.from(fetchedAt) : null,
        rejected ? "INVALID_PROVIDER_RESPONSE" : null);
  }

  private static String sourceKind(ProviderDataHealthKey key) {
    return switch (key.provider()) {
      case "tour-api" -> "tour_api";
      case "TAGO" -> "tago";
      case "kma" -> "weather_api";
      default -> "directions_api";
    };
  }

  private static UUID runId(int sequence) {
    return UUID.fromString("16000000-0000-0000-0001-%012d".formatted(sequence));
  }

  private static UUID snapshotId(int sequence) {
    return UUID.fromString("16000000-0000-0000-0002-%012d".formatted(sequence));
  }

  private static UUID ownerId(int sequence) {
    return UUID.fromString("16000000-0000-0000-0003-%012d".formatted(sequence));
  }
}
