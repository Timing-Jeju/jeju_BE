package com.timingjeju.api.global.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
import com.timingjeju.api.application.importing.ImportCheckpointRepository;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest(properties = "timing-jeju.test.context=import-checkpoint")
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcImportCheckpointRepositoryIntegrationTest {

  @Autowired private ImportCheckpointRepository repository;
  @Autowired private ImportCheckpointService service;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterAll
  void cleanUpOwnerOnlyFixtures() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbcTemplate.execute(
                  "alter table public.data_import_checkpoints disable trigger trg_data_import_checkpoints_no_delete");
              jdbcTemplate.update(
                  "delete from public.data_import_checkpoints where scope_key like 'issue-24:%'");
              jdbcTemplate.execute(
                  "alter table public.data_import_checkpoints enable trigger trg_data_import_checkpoints_no_delete");
              jdbcTemplate.update(
                  "delete from public.data_import_runs where source_name='checkpoint-test'");
            });
  }

  @Test
  void checkpoint를_exact_scope로_읽고_기존_RPC로_CAS_전진한다() {
    ImportRunScope scope = scope("read");
    UUID runId = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:01:00Z"));
    insertCheckpoint(scope);

    assertThat(repository.find(scope)).get().extracting(ImportCheckpoint::version).isEqualTo(0L);
    assertThat(
            repository.find(
                new ImportRunScope("kto", scope.service(), scope.operation(), scope.scopeKey())))
        .isEmpty();

    ImportCheckpoint advanced =
        service.advance(command(scope, 0, runId, ImportRunStatus.SUCCEEDED, Map.of("page", 2)));

    assertThat(advanced.version()).isEqualTo(1);
    assertThat(advanced.checkpoint()).containsEntry("page", 2);
    assertThat(advanced.lastSucceededRunId()).isEqualTo(runId);
  }

  @Test
  void nested_JSON_tree를_jsonb로_round_trip하고_read_value를_재귀적으로_불변화한다() {
    ImportRunScope scope = scope("json-tree");
    UUID runId = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:01:30Z"));
    insertCheckpoint(scope);
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("null", null);
    nested.put("boolean", true);
    nested.put("string", "제주");
    nested.put("integer", 3);
    nested.put("bigInteger", new BigInteger("123456789012345678901234567890"));
    nested.put("decimal", new BigDecimal("123.4500"));
    nested.put("array", List.of(Map.of("page", 2), false));

    service.advance(
        command(
            scope,
            0,
            runId,
            ImportRunStatus.SUCCEEDED,
            new LinkedHashMap<>(Map.of("nested", nested))));
    ImportCheckpoint read = repository.find(scope).orElseThrow();

    Map<String, Object> expectedNested = new LinkedHashMap<>();
    expectedNested.put("null", null);
    expectedNested.put("boolean", true);
    expectedNested.put("string", "제주");
    expectedNested.put("integer", 3);
    expectedNested.put("bigInteger", new BigInteger("123456789012345678901234567890"));
    expectedNested.put("decimal", new BigDecimal("123.4500"));
    expectedNested.put("array", List.of(Map.of("page", 2), false));

    assertThat(read.checkpoint().get("nested")).isEqualTo(expectedNested);
    assertThatThrownBy(() -> nestedMap(read).put("blocked", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> nestedArray(read).add("blocked"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void 같은_version의_두_writer는_하나만_성공하고_다른_하나는_retryable이다() throws Exception {
    ImportRunScope scope = scope("concurrent");
    UUID runId = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:02:00Z"));
    insertCheckpoint(scope);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Object> first =
          executor.submit(() -> advanceInTransaction(scope, runId, "A", ready, start));
      Future<Object> second =
          executor.submit(() -> advanceInTransaction(scope, runId, "B", ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      java.util.List<Object> outcomes =
          java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(outcomes).filteredOn(ImportCheckpoint.class::isInstance).hasSize(1);
      assertThat(outcomes)
          .filteredOn(ImportCheckpointException.class::isInstance)
          .singleElement()
          .satisfies(
              value -> {
                ImportCheckpointException failure = (ImportCheckpointException) value;
                assertThat(failure.code()).isEqualTo(ImportCheckpointError.STALE_VERSION);
                assertThat(failure.retryable()).isTrue();
                assertThat(failure.getCause()).isNull();
              });
    }
    assertThat(repository.find(scope)).get().extracting(ImportCheckpoint::version).isEqualTo(1L);
  }

  @Test
  void failed와_partial_run_및_이전_succeeded_run은_DB에서도_전진을_거부한다() {
    ImportRunScope scope = scope("invalid-run");
    UUID previous = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:03:00Z"));
    UUID current = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:04:00Z"));
    UUID failed = insertRun(scope, "failed", Instant.parse("2026-08-14T00:05:00Z"));
    UUID partial = insertRun(scope, "partial", Instant.parse("2026-08-14T00:06:00Z"));
    insertCheckpoint(scope);
    repository.advance(command(scope, 0, current, ImportRunStatus.SUCCEEDED, Map.of("page", 4)));

    for (UUID invalidRun : java.util.List.of(previous, failed, partial)) {
      assertThatThrownBy(
              () ->
                  repository.advance(
                      command(scope, 1, invalidRun, ImportRunStatus.SUCCEEDED, Map.of("page", 5))))
          .isInstanceOf(ImportCheckpointException.class)
          .extracting("code")
          .isEqualTo(ImportCheckpointError.INVALID_ADVANCE);
    }
    assertThat(repository.find(scope))
        .get()
        .satisfies(
            checkpoint -> {
              assertThat(checkpoint.version()).isEqualTo(1);
              assertThat(checkpoint.lastSucceededRunId()).isEqualTo(current);
            });
  }

  @Test
  void 외부_transaction_rollback은_checkpoint_전진도_되돌린다() {
    ImportRunScope scope = scope("rollback");
    UUID runId = insertRun(scope, "succeeded", Instant.parse("2026-08-14T00:07:00Z"));
    insertCheckpoint(scope);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      service.advance(
                          command(scope, 0, runId, ImportRunStatus.SUCCEEDED, Map.of("page", 7)));
                      throw new RollbackProbeException();
                    }))
        .isInstanceOf(RollbackProbeException.class);
    assertThat(repository.find(scope))
        .get()
        .satisfies(
            checkpoint -> {
              assertThat(checkpoint.version()).isZero();
              assertThat(checkpoint.lastSucceededRunId()).isNull();
            });
  }

  @Test
  void service_role은_RPC만_실행하고_직접_DML_DELETE_TRUNCATE로_우회할수없다() {
    assertThat(privilege("UPDATE")).isFalse();
    assertThat(privilege("DELETE")).isFalse();
    assertThat(privilege("TRUNCATE")).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_function_privilege('service_role', 'public.advance_data_import_checkpoint(text,text,text,text,bigint,jsonb,timestamptz,uuid)', 'EXECUTE')",
                Boolean.class))
        .isTrue();

    for (String statement :
        java.util.List.of(
            "update public.data_import_checkpoints set checkpoint='{}'::jsonb",
            "delete from public.data_import_checkpoints",
            "truncate public.data_import_checkpoints")) {
      assertThatThrownBy(() -> asServiceRole(statement)).isInstanceOf(DataAccessException.class);
    }
  }

  private Object advanceInTransaction(
      ImportRunScope scope, UUID runId, String writer, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("동시성 시작 신호를 받지 못했습니다.");
      }
      return new TransactionTemplate(transactionManager)
          .execute(
              status ->
                  repository.advance(
                      command(
                          scope, 0, runId, ImportRunStatus.SUCCEEDED, Map.of("writer", writer))));
    } catch (ImportCheckpointException failure) {
      return failure;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시성 테스트가 중단되었습니다.");
    }
  }

  private void asServiceRole(String statement) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbcTemplate.execute("set local role service_role");
              jdbcTemplate.execute(statement);
            });
  }

  private boolean privilege(String privilege) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select has_table_privilege('service_role', 'public.data_import_checkpoints', ?)",
            Boolean.class,
            privilege));
  }

  private void insertCheckpoint(ImportRunScope scope) {
    jdbcTemplate.update(
        "insert into public.data_import_checkpoints(source_provider,source_service,source_operation,scope_key) values (?,?,?,?)",
        scope.provider(),
        scope.service(),
        scope.operation(),
        scope.scopeKey());
  }

  private UUID insertRun(ImportRunScope scope, String status, Instant finishedAt) {
    UUID runId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status,
          started_at, finished_at, error_code, error_message,
          source_provider, source_service, scope_key
        ) values (?, 'tour_api', 'checkpoint-test', ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        runId,
        scope.operation(),
        status,
        java.sql.Timestamp.from(finishedAt.minusSeconds(30)),
        java.sql.Timestamp.from(finishedAt),
        "succeeded".equals(status) ? null : "IMPORT_TEST_FAILURE",
        "succeeded".equals(status) ? null : "테스트 수집 실행이 완료되지 않았습니다.",
        scope.provider(),
        scope.service(),
        scope.scopeKey());
    return runId;
  }

  private static ImportRunScope scope(String suffix) {
    return new ImportRunScope("KTO", "TourAPI", "checkpoint", "issue-24:" + suffix);
  }

  private static ImportCheckpointAdvanceCommand command(
      ImportRunScope scope,
      long expectedVersion,
      UUID runId,
      ImportRunStatus status,
      Map<String, Object> checkpoint) {
    return new ImportCheckpointAdvanceCommand(
        scope, expectedVersion, checkpoint, Instant.parse("2026-08-14T00:00:00Z"), runId, status);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedMap(ImportCheckpoint checkpoint) {
    return (Map<String, Object>) checkpoint.checkpoint().get("nested");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> nestedArray(ImportCheckpoint checkpoint) {
    return (List<Object>) nestedMap(checkpoint).get("array");
  }

  private static final class RollbackProbeException extends RuntimeException {}
}
