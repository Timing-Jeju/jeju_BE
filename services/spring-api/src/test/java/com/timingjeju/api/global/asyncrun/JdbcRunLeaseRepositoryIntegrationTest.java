package com.timingjeju.api.global.asyncrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.asyncrun.RunLease;
import com.timingjeju.api.application.asyncrun.RunResultSource;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
class JdbcRunLeaseRepositoryIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
  private static final UUID SESSION_ID = UUID.fromString("74000000-0000-0000-0000-000000000010");
  private static final UUID PLAN_ID = UUID.fromString("74000000-0000-0000-0000-000000000020");
  private static final UUID DAY_ID = UUID.fromString("74000000-0000-0000-0000-000000000030");
  private static final UUID VERSION_ID = UUID.fromString("74000000-0000-0000-0000-000000000040");

  @Autowired private JdbcRunLeaseRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update(
        "insert into public.app_sessions (id, public_token) values (?, ?)",
        SESSION_ID,
        "async-run-session");
    jdbcTemplate.update(
        """
        insert into public.trip_plans (
          id, session_id, public_token, start_date, end_date, source_mode, data_version
        ) values (?, ?, ?, '2026-08-03', '2026-08-03', 'fixture', 'async-run-v1')
        """,
        PLAN_ID,
        SESSION_ID,
        "async-run-plan");
    jdbcTemplate.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, '2026-08-03')",
        DAY_ID,
        PLAN_ID);
    jdbcTemplate.update(
        """
        insert into public.trip_schedule_versions (
          id, trip_plan_id, version_no, status, source_type
        ) values (?, ?, 1, 'draft', 'initial')
        """,
        VERSION_ID,
        PLAN_ID);
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from public.trip_plans where id = ?", PLAN_ID);
    jdbcTemplate.update("delete from public.app_sessions where id = ?", SESSION_ID);
  }

  @Test
  void 두_worker가_동시에_claim해도_하나의_fencing_token만_발급된다() throws Exception {
    UUID runId = insertQueuedRun("concurrent");
    CountDownLatch start = new CountDownLatch(1);
    Callable<List<RunLease>> claim =
        () -> {
          start.await(5, TimeUnit.SECONDS);
          return repository.claimAvailable(
              "worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(30), 50);
        };

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(claim);
      var second = pool.submit(claim);
      start.countDown();
      List<RunLease> all = new java.util.ArrayList<>();
      all.addAll(first.get(10, TimeUnit.SECONDS));
      all.addAll(second.get(10, TimeUnit.SECONDS));

      assertThat(all).containsExactly(new RunLease(runId, 1, 1));
    }
  }

  @Test
  void lease_직전에는_takeover하지_않고_만료_시점에는_새_token으로_stuck_run을_복구한다() {
    UUID runId = insertQueuedRun("expiry");
    RunLease first = repository.claimAvailable("worker-old", Duration.ofSeconds(30), 50).getFirst();

    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() + interval '1 second' where id = ?",
        runId);
    assertThat(repository.claimAvailable("worker-new", Duration.ofSeconds(30), 50)).isEmpty();
    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() where id = ?",
        runId);
    assertThat(repository.claimAvailable("worker-new", Duration.ofSeconds(30), 50))
        .containsExactly(new RunLease(runId, first.fencingToken() + 1, 2));
  }

  @Test
  void stale_fencing_token은_heartbeat와_terminal_전이를_할_수_없다() {
    UUID runId = insertQueuedRun("fencing");
    RunLease stale = repository.claimAvailable("worker-old", Duration.ofSeconds(30), 50).getFirst();
    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() where id = ?",
        runId);
    RunLease current =
        repository.claimAvailable("worker-new", Duration.ofSeconds(30), 50).getFirst();

    assertThat(repository.heartbeat(stale, Duration.ofSeconds(30))).isFalse();
    assertThat(repository.succeed(stale, RunResultSource.COMPUTED)).isFalse();
    assertThat(repository.succeed(current, RunResultSource.COMPUTED)).isTrue();
    assertThat(status(runId)).isEqualTo("succeeded");
  }

  @Test
  void retry는_예약시각까지_claim되지_않고_재시작_worker가_같은_run을_resume한다() {
    UUID runId = insertQueuedRun("restart");
    RunLease failedAttempt =
        repository.claimAvailable("worker-old", Duration.ofSeconds(30), 50).getFirst();
    assertThat(repository.retry(failedAttempt, Duration.ofSeconds(4), "MCP_TEMPORARY")).isTrue();

    assertThat(repository.claimAvailable("worker-restarted", Duration.ofSeconds(30), 50)).isEmpty();
    jdbcTemplate.update(
        "update public.compute_runs set next_attempt_at = statement_timestamp() where id = ?",
        runId);
    assertThat(repository.claimAvailable("worker-restarted", Duration.ofSeconds(30), 50))
        .containsExactly(new RunLease(runId, 2, 2));
  }

  @Test
  void heartbeat은_현재_token과_만료되지_않은_lease에서만_30초를_연장한다() {
    insertQueuedRun("heartbeat");
    RunLease lease = repository.claimAvailable("worker", Duration.ofSeconds(30), 50).getFirst();

    assertThat(repository.heartbeat(lease, Duration.ofSeconds(30))).isTrue();
    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() where id = ?",
        lease.runId());
    assertThat(repository.heartbeat(lease, Duration.ofSeconds(30))).isFalse();
    assertThatThrownBy(() -> repository.heartbeat(lease, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 다섯번째_attempt의_lease가_만료되면_재claim하지_않고_terminal_failed로_복구한다() {
    UUID runId = insertQueuedRun("exhausted");
    jdbcTemplate.update("update public.compute_runs set attempt_count = 4 where id = ?", runId);
    assertThat(repository.claimAvailable("worker-old", Duration.ofSeconds(30), 50))
        .containsExactly(new RunLease(runId, 1, 5));

    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() where id = ?",
        runId);
    assertThat(repository.claimAvailable("worker-recovery", Duration.ofSeconds(30), 50)).isEmpty();
    assertThat(status(runId)).isEqualTo("failed");
    assertThat(
            jdbcTemplate.queryForObject(
                "select error_code from public.compute_runs where id = ?", String.class, runId))
        .isEqualTo("ASYNC_RUN_RETRY_EXHAUSTED");
  }

  @Test
  void 현재_fencing_token만_안정적인_error_code로_terminal_failed를_기록한다() {
    UUID runId = insertQueuedRun("terminal-failure");
    RunLease lease = repository.claimAvailable("worker", Duration.ofSeconds(30), 50).getFirst();

    assertThat(repository.fail(lease, "ASYNC_RUN_EXECUTION_FAILED")).isTrue();
    assertThat(status(runId)).isEqualTo("failed");
    assertThat(
            jdbcTemplate.queryForObject(
                "select error_code from public.compute_runs where id = ?", String.class, runId))
        .isEqualTo("ASYNC_RUN_EXECUTION_FAILED");
    assertThat(repository.fail(lease, "STALE_WRITE")).isFalse();
  }

  @Test
  void claim은_DB시각으로_started와_provenance를_원자적으로_확정한다() {
    UUID runId = insertQueuedRun("provenance");

    repository.claimAvailable("worker", Duration.ofSeconds(30), 50);

    var phase =
        jdbcTemplate.queryForMap(
            "select status, started_at, facts_snapshot_at, source_data_version from public.compute_runs where id = ?",
            runId);
    assertThat(phase.get("status")).isEqualTo("running");
    assertThat(phase.get("started_at")).isNotNull();
    assertThat(phase.get("facts_snapshot_at")).isNotNull();
    assertThat(phase.get("source_data_version")).isEqualTo("async-run-v1");
  }

  @Test
  void DB_phase_CHECK는_queued와_running_succeeded의_provenance_불변조건을_거부한다() {
    UUID runId = insertQueuedRun("phase-check");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.compute_runs set started_at = statement_timestamp() where id = ?",
                    runId))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.compute_runs set status = 'running', lease_owner = 'worker', heartbeat_at = statement_timestamp(), lease_expires_at = statement_timestamp() + interval '30 seconds' where id = ?",
                    runId))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
  }

  @Test
  void computed와_fallback_성공을_fencing_transaction에서_구분해_기록한다() {
    UUID computed = insertQueuedRun("computed-result");
    RunLease computedLease =
        repository.claimAvailable("worker-computed", Duration.ofSeconds(30), 1).getFirst();
    assertThat(repository.succeed(computedLease, RunResultSource.COMPUTED)).isTrue();
    assertThat(resultSource(computed)).isEqualTo("computed");

    UUID fallback = insertQueuedRun("fallback-result");
    RunLease fallbackLease =
        repository.claimAvailable("worker-fallback", Duration.ofSeconds(30), 1).getFirst();
    assertThat(repository.succeed(fallbackLease, RunResultSource.FALLBACK)).isTrue();
    assertThat(resultSource(fallback)).isEqualTo("fallback");
  }

  @Test
  void DB에서_lease가_만료되면_현재_token이어도_success_retry_fail을_거부한다() {
    UUID runId = insertQueuedRun("expired-terminal");
    RunLease lease = repository.claimAvailable("worker", Duration.ofSeconds(30), 50).getFirst();
    jdbcTemplate.update(
        "update public.compute_runs set lease_expires_at = statement_timestamp() where id = ?",
        runId);

    assertThat(repository.succeed(lease, RunResultSource.COMPUTED)).isFalse();
    assertThat(repository.retry(lease, Duration.ofSeconds(1), "TEMPORARY")).isFalse();
    assertThat(repository.fail(lease, "TERMINAL")).isFalse();
    assertThat(status(runId)).isEqualTo("running");
  }

  @Test
  void migration은_lease_fencing_retry_컬럼만_추가하고_payload나_token을_저장하지_않는다() {
    List<String> columns =
        jdbcTemplate.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'compute_runs'
            """,
            String.class);

    assertThat(columns)
        .contains(
            "attempt_count",
            "fencing_token",
            "lease_owner",
            "lease_expires_at",
            "heartbeat_at",
            "next_attempt_at")
        .doesNotContain("payload", "authorization", "provider_token", "location");
  }

  private UUID insertQueuedRun(String suffix) {
    UUID runId =
        UUID.nameUUIDFromBytes(
            ("issue-74-" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    jdbcTemplate.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version, facts_snapshot_at, source_data_version,
          created_at, next_attempt_at
        ) values (
          ?, ?, ?, ?, 'feasibility', 'queued', ?, 'contract-v1', 'algorithm-v1', null, null, ?, ?
        )
        """,
        runId,
        PLAN_ID,
        DAY_ID,
        VERSION_ID,
        "hash-" + suffix,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    return runId;
  }

  private String status(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select status from public.compute_runs where id = ?", String.class, runId);
  }

  private String resultSource(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select result_source from public.compute_runs where id = ?", String.class, runId);
  }
}
