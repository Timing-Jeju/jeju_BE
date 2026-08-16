package com.timingjeju.api.global.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleError;
import com.timingjeju.api.application.importing.ImportRunLifecycleException;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportSourceKind;
import com.timingjeju.api.application.importing.ImportSyncMode;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcImportRunStoreIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-13T01:02:03Z");

  @Autowired private JdbcImportRunStore store;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private ImportRunLifecycleService service;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from public.data_import_runs");
    service =
        new ImportRunLifecycleService(
            store, Clock.fixed(NOW, ZoneOffset.UTC), new UuidImportRunIdentityGenerator());
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from public.data_import_runs");
  }

  @Test
  void 시작은_source_scope_version_owner_fencing을_보존하고_같은_idempotency는_같은_run을_재사용한다() {
    ImportRunStartResult first = service.start(command("idem-1", null, "jeju"));
    ImportRunStartResult replay = service.start(command("idem-1", null, "jeju"));

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.status()).isEqualTo(ImportRunExecutionStatus.RUNNING);
    assertThat(replay.counts()).isEqualTo(ImportRunCounts.zero());
    assertThat(replay.lease()).isEqualTo(first.lease());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.data_import_runs", Integer.class))
        .isEqualTo(1);
    assertThat(row(first.lease().runId()))
        .containsEntry("source_provider", "tour-api")
        .containsEntry("source_service", "KorService2")
        .containsEntry("source_operation", "areaBasedList2")
        .containsEntry("scope_key", "jeju")
        .containsEntry("parser_version", "parser-v3")
        .containsEntry("schema_version", "tour-api-2026-01")
        .containsEntry("retry_count", 0)
        .containsEntry("fencing_token", 1L);
  }

  @Test
  void 같은_idempotency_key의_다른_request는_기존_run을_재사용하지_않는다() {
    service.start(command("idem-mismatch", null, "idem-mismatch"));
    ImportRunStartCommand changed =
        new ImportRunStartCommand(
            ImportSourceKind.TOUR_API,
            "한국관광공사",
            new ImportRunScope("tour-api", "KorService2", "areaBasedList2", "idem-mismatch"),
            "2026-08-13",
            "parser-v3",
            "tour-api-2026-01",
            ImportSyncMode.INCREMENTAL,
            "sha256:different-request",
            "idem-mismatch",
            null);

    assertThatThrownBy(() -> service.start(changed))
        .isInstanceOf(ImportRunLifecycleException.class)
        .extracting("code")
        .isEqualTo(ImportRunLifecycleError.INVALID_REQUEST);
  }

  @Test
  void 같은_idempotency_replay는_terminal_status와_counts를_그대로_반환한다() {
    ImportRunLease failed = service.start(command("matrix-failed", null, "matrix-failed")).lease();
    service.fail(failed, ImportRunFailure.PROVIDER_UNAVAILABLE);
    ImportRunStartResult failedReplay =
        service.start(command("matrix-failed", null, "matrix-failed"));
    assertThat(failedReplay.status()).isEqualTo(ImportRunExecutionStatus.FAILED);
    assertThat(failedReplay.counts()).isEqualTo(ImportRunCounts.zero());

    ImportRunLease partial =
        service.start(command("matrix-partial", null, "matrix-partial")).lease();
    ImportRunCounts partialCounts = new ImportRunCounts(4, 2, 1, 1, 1, 1, 0, 0);
    service.completePartial(partial, partialCounts, ImportRunFailure.PARSE_REJECTED);
    ImportRunStartResult partialReplay =
        service.start(command("matrix-partial", null, "matrix-partial"));
    assertThat(partialReplay.status()).isEqualTo(ImportRunExecutionStatus.PARTIAL);
    assertThat(partialReplay.counts()).isEqualTo(partialCounts);

    ImportRunLease succeeded =
        service.start(command("matrix-succeeded", null, "matrix-succeeded")).lease();
    ImportRunCounts succeededCounts = new ImportRunCounts(9, 3, 4, 2, 2, 0, 1, 0);
    service.succeed(succeeded, succeededCounts);
    ImportRunStartResult succeededReplay =
        service.start(command("matrix-succeeded", null, "matrix-succeeded"));
    assertThat(succeededReplay.status()).isEqualTo(ImportRunExecutionStatus.SUCCEEDED);
    assertThat(succeededReplay.counts()).isEqualTo(succeededCounts);

    ImportRunLease cancelled =
        service.start(command("matrix-cancelled", null, "matrix-cancelled")).lease();
    service.cancel(cancelled);
    ImportRunStartResult cancelledReplay =
        service.start(command("matrix-cancelled", null, "matrix-cancelled"));
    assertThat(cancelledReplay.status()).isEqualTo(ImportRunExecutionStatus.CANCELLED);
    assertThat(cancelledReplay.counts()).isEqualTo(ImportRunCounts.zero());
  }

  @Test
  void 같은_scope의_다른_idempotency를_동시에_시작하면_정확히_하나만_running이다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> startAfter(start, "concurrent-a"));
      var second = pool.submit(() -> startAfter(start, "concurrent-b"));
      start.countDown();

      int successes = 0;
      int conflicts = 0;
      for (var future : java.util.List.of(first, second)) {
        try {
          future.get(15, TimeUnit.SECONDS);
          successes++;
        } catch (java.util.concurrent.ExecutionException failure) {
          assertThat(failure.getCause())
              .isInstanceOf(ImportRunLifecycleException.class)
              .extracting("code")
              .isEqualTo(ImportRunLifecycleError.SCOPE_ALREADY_RUNNING);
          conflicts++;
        }
      }
      assertThat(successes).isEqualTo(1);
      assertThat(conflicts).isEqualTo(1);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.data_import_runs where status='running'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void grandfathered_running_scope도_새_run의_scope를_점유한다() {
    UUID legacyRunId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.execute("set local session_replication_role = replica");
          jdbcTemplate.update(
              """
              insert into public.data_import_runs (
                id, source_kind, source_name, source_operation, data_version, status,
                parser_version, schema_version, sync_mode, scope_key, idempotency_key,
                source_provider, source_service, running_scope_enforced
              ) values (?, 'tour_api', 'legacy', 'areaBasedList2', 'legacy-v1', 'running',
                        'legacy-parser', 'legacy-schema', 'incremental', 'legacy-running',
                        'legacy-running-key', 'tour-api', 'KorService2', false)
              """,
              legacyRunId);
        });

    transactionTemplate.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> service.start(command("new-run", null, "legacy-running")))
                .isInstanceOf(ImportRunLifecycleException.class)
                .extracting("code")
                .isEqualTo(ImportRunLifecycleError.SCOPE_ALREADY_RUNNING));
  }

  @Test
  void caller_transaction에서도_idempotent_replay와_mismatch를_구분한다() {
    transactionTemplate.executeWithoutResult(
        status -> {
          ImportRunStartResult first = service.start(command("transaction-replay", null, "tx"));
          ImportRunStartResult replay = service.start(command("transaction-replay", null, "tx"));

          assertThat(replay.replayed()).isTrue();
          assertThat(replay.lease()).isEqualTo(first.lease());
          assertThatThrownBy(
                  () ->
                      service.start(
                          new ImportRunStartCommand(
                              ImportSourceKind.TOUR_API,
                              "한국관광공사",
                              new ImportRunScope("tour-api", "KorService2", "areaBasedList2", "tx"),
                              "2026-08-13",
                              "parser-v3",
                              "tour-api-2026-01",
                              ImportSyncMode.INCREMENTAL,
                              "sha256:different-request",
                              "transaction-replay",
                              null)))
              .isInstanceOf(ImportRunLifecycleException.class)
              .extracting("code")
              .isEqualTo(ImportRunLifecycleError.INVALID_REQUEST);
        });
  }

  @Test
  void count_누적과_partial_terminal은_한_update로_원자적으로_기록된다() {
    ImportRunLease lease = service.start(command("partial", null, "partial-scope")).lease();
    service.addCounts(lease, new ImportRunCounts(2, 3, 1, 1, 0, 1, 0, 0));
    service.completePartial(
        lease, new ImportRunCounts(3, 4, 1, 1, 1, 1, 0, 0), ImportRunFailure.PARSE_REJECTED);

    assertThat(row(lease.runId()))
        .containsEntry("status", "partial")
        .containsEntry("row_count", 5)
        .containsEntry("fetched_count", 7)
        .containsEntry("inserted_count", 2)
        .containsEntry("updated_count", 2)
        .containsEntry("skipped_count", 1)
        .containsEntry("rejected_count", 2)
        .containsEntry("error_code", "IMPORT_PARSE_REJECTED")
        .containsEntry("error_message", "일부 원천 행을 안전하게 해석하지 못했습니다.");
    assertThat(row(lease.runId()).get("finished_at")).isEqualTo(java.sql.Timestamp.from(NOW));
  }

  @Test
  void succeeded_failed_cancelled와_illegal_transition을_fail_closed한다() {
    ImportRunLease succeeded = service.start(command("success", null, "success")).lease();
    service.succeed(succeeded, new ImportRunCounts(1, 1, 1, 0, 0, 0, 0, 0));
    assertThat(row(succeeded.runId())).containsEntry("status", "succeeded");
    assertThatThrownBy(() -> service.fail(succeeded, ImportRunFailure.PROVIDER_UNAVAILABLE))
        .isInstanceOf(ImportRunLifecycleException.class)
        .extracting("code")
        .isEqualTo(ImportRunLifecycleError.INVALID_TRANSITION);

    ImportRunLease failed = service.start(command("failed", null, "failed")).lease();
    service.fail(failed, ImportRunFailure.PROVIDER_UNAVAILABLE);
    assertThat(row(failed.runId()))
        .containsEntry("status", "failed")
        .containsEntry("error_code", "IMPORT_PROVIDER_UNAVAILABLE");

    ImportRunLease cancelled = service.start(command("cancelled", null, "cancelled")).lease();
    service.cancel(cancelled);
    assertThat(row(cancelled.runId()))
        .containsEntry("status", "cancelled")
        .containsEntry("error_code", "IMPORT_CANCELLED");
  }

  @Test
  void stale_owner와_fencing_token은_count와_terminal_쓰기를_할_수_없다() {
    ImportRunLease current = service.start(command("fencing", null, "fencing")).lease();
    ImportRunLease wrongOwner = new ImportRunLease(current.runId(), UUID.randomUUID(), 1);
    ImportRunLease staleFence = new ImportRunLease(current.runId(), current.ownerToken(), 2);

    for (ImportRunLease stale : java.util.List.of(wrongOwner, staleFence)) {
      assertThatThrownBy(() -> service.addCounts(stale, ImportRunCounts.zero()))
          .isInstanceOf(ImportRunLifecycleException.class)
          .extracting("code")
          .isEqualTo(ImportRunLifecycleError.OWNERSHIP_LOST);
      assertThatThrownBy(() -> service.succeed(stale, ImportRunCounts.zero()))
          .isInstanceOf(ImportRunLifecycleException.class)
          .extracting("code")
          .isEqualTo(ImportRunLifecycleError.OWNERSHIP_LOST);
    }
    assertThat(row(current.runId()))
        .containsEntry("status", "running")
        .containsEntry("row_count", 0);
  }

  @Test
  void count_overflow는_전체_statement를_rollback하고_running_marker를_유지한다() {
    ImportRunLease lease = service.start(command("overflow", null, "overflow")).lease();
    jdbcTemplate.update(
        "update public.data_import_runs set row_count=? where id=?",
        Integer.MAX_VALUE,
        lease.runId());

    assertThatThrownBy(
            () ->
                service.completePartial(
                    lease,
                    new ImportRunCounts(1, 1, 1, 0, 0, 0, 0, 0),
                    ImportRunFailure.PARSE_REJECTED))
        .isInstanceOf(ImportRunLifecycleException.class)
        .extracting("code")
        .isEqualTo(ImportRunLifecycleError.COUNT_OVERFLOW);
    assertThat(row(lease.runId()))
        .containsEntry("status", "running")
        .containsEntry("row_count", Integer.MAX_VALUE)
        .containsEntry("fetched_count", 0);
  }

  @Test
  void retry는_parent와_증가한_retry_count_및_새_parser_schema_version을_보존한다() {
    ImportRunLease parent = service.start(command("parent", null, "retry")).lease();
    service.fail(parent, ImportRunFailure.PROVIDER_UNAVAILABLE);

    ImportRunLease child = service.start(command("child", parent.runId(), "retry")).lease();
    assertThat(row(child.runId()))
        .containsEntry("parent_run_id", parent.runId())
        .containsEntry("retry_count", 1)
        .containsEntry("parser_version", "parser-v3")
        .containsEntry("schema_version", "tour-api-2026-01");
  }

  @Test
  void parent_scope가_다르거나_존재하지_않으면_retry를_거부한다() {
    ImportRunLease parent = service.start(command("parent-other", null, "parent-scope")).lease();
    service.fail(parent, ImportRunFailure.PROVIDER_UNAVAILABLE);

    for (UUID invalidParent : java.util.List.of(parent.runId(), UUID.randomUUID())) {
      assertThatThrownBy(
              () ->
                  service.start(command("invalid-" + invalidParent, invalidParent, "other-scope")))
          .isInstanceOf(ImportRunLifecycleException.class)
          .extracting("code")
          .isEqualTo(ImportRunLifecycleError.INVALID_PARENT);
    }
  }

  @Test
  void migration은_owner_fencing만_추가하고_secret_payload_PII_column을_만들지_않는다() {
    java.util.List<String> columns =
        jdbcTemplate.queryForList(
            "select column_name from information_schema.columns where table_schema='public' and table_name='data_import_runs'",
            String.class);
    assertThat(columns)
        .contains("owner_token", "fencing_token")
        .doesNotContain("api_key", "authorization", "provider_token", "raw_payload", "email");
  }

  @Test
  void 기존_fixture_insert는_DB_default로_유효한_owner와_fencing을_받는다() {
    UUID runId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, finished_at,
          source_provider, source_service, scope_key
        ) values (?, 'fixture', 'legacy fixture', 'seed', 'v1', 'succeeded', now(),
                  'fixture', 'legacy', 'fixture:legacy')
        """,
        runId);

    assertThat(row(runId).get("owner_token")).isInstanceOf(UUID.class);
    assertThat(row(runId)).containsEntry("fencing_token", 1L);
  }

  @Test
  void DB도_owner와_fencing_token_변경을_거부한다() {
    ImportRunLease lease =
        service.start(command("immutable-lease", null, "immutable-lease")).lease();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.data_import_runs set owner_token=? where id=?",
                    UUID.randomUUID(),
                    lease.runId()))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThat(row(lease.runId()))
        .containsEntry("owner_token", lease.ownerToken())
        .containsEntry("fencing_token", lease.fencingToken());
  }

  private ImportRunStartResult startAfter(CountDownLatch start, String key) throws Exception {
    start.await(5, TimeUnit.SECONDS);
    return service.start(command(key, null, "concurrent"));
  }

  private java.util.Map<String, Object> row(UUID runId) {
    return jdbcTemplate.queryForMap("select * from public.data_import_runs where id=?", runId);
  }

  private static ImportRunStartCommand command(String idempotencyKey, UUID parent, String scope) {
    return new ImportRunStartCommand(
        ImportSourceKind.TOUR_API,
        "한국관광공사",
        new ImportRunScope("tour-api", "KorService2", "areaBasedList2", scope),
        "2026-08-13",
        "parser-v3",
        "tour-api-2026-01",
        ImportSyncMode.INCREMENTAL,
        "sha256:fixture-request",
        idempotencyKey,
        parent);
  }
}
