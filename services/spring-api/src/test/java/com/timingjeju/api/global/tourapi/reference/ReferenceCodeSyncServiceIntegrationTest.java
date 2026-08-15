package com.timingjeju.api.global.tourapi.reference;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tourapi.reference.ReferenceCode;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeOperation;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeRepository;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSource;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSourceResponse;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncCommand;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncResult;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncService;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class ReferenceCodeSyncServiceIntegrationTest {

  @Autowired private ImportRunLifecycleService runService;
  @Autowired private SnapshotStoreService snapshotService;
  @Autowired private ReferenceCodeRepository actualRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Clock clock;

  @BeforeEach
  void setUp() {
    clean();
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void 완료된_run을_같은_command로_replay하면_저장상태와_terminal을_그대로_반환한다() {
    Counters counters = new Counters();
    ReferenceCodeSyncService service = service(counters, operation -> response());
    ReferenceCodeSyncCommand command = command("issue-25-terminal-replay");

    ReferenceCodeSyncResult first = service.sync(command);
    Map<String, Object> state = state();
    ReferenceCodeSyncResult replay = service.sync(command);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.runId()).isEqualTo(first.runId());
    assertThat(state()).isEqualTo(state);
    assertSingleExecution(counters);
  }

  @Test
  void running_run을_두_thread가_replay해도_두번째는_외부와_DB와_terminal을_재실행하지_않는다() throws Exception {
    Counters counters = new Counters();
    CountDownLatch sourceEntered = new CountDownLatch(1);
    CountDownLatch releaseSource = new CountDownLatch(1);
    ReferenceCodeSource blockingSource =
        operation -> {
          sourceEntered.countDown();
          try {
            if (!releaseSource.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("source release timeout");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("source interrupted", interrupted);
          }
          return response();
        };
    ReferenceCodeSyncService service = service(counters, blockingSource);
    ReferenceCodeSyncCommand command = command("issue-25-running-replay");
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstFuture = executor.submit(() -> service.sync(command));
      assertThat(sourceEntered.await(10, TimeUnit.SECONDS)).isTrue();
      var replayFuture = executor.submit(() -> service.sync(command));
      ReferenceCodeSyncResult replay = replayFuture.get(10, TimeUnit.SECONDS);
      assertThat(replay.replayed()).isTrue();
      assertThat(
              jdbcTemplate.queryForObject(
                  "select status from public.data_import_runs", String.class))
          .isEqualTo("running");
      assertThat(tableCount("external_api_snapshots")).isZero();
      releaseSource.countDown();
      ReferenceCodeSyncResult first = firstFuture.get(30, TimeUnit.SECONDS);
      assertThat(first.replayed()).isFalse();
      assertThat(replay.runId()).isEqualTo(first.runId());
    } finally {
      releaseSource.countDown();
      executor.shutdownNow();
    }

    assertSingleExecution(counters);
    assertThat(tableCount("data_import_runs")).isEqualTo(1);
    assertThat(tableCount("external_api_snapshots")).isEqualTo(1);
    assertThat(tableCount("external_reference_codes")).isEqualTo(1);
    assertThat(tableCount("tour_api_operation_provenance")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select status from public.data_import_runs", String.class))
        .isEqualTo("succeeded");
  }

  private ReferenceCodeSyncService service(Counters counters, ReferenceCodeSource source) {
    return new ReferenceCodeSyncService(
        operation -> {
          counters.source.incrementAndGet();
          return source.fetch(operation);
        },
        (operation, format, payload) -> {
          counters.parser.incrementAndGet();
          return List.of(
              new ReferenceCode("ldong-region", "50", null, "제주특별자치도", "제주특별자치도", Map.of()));
        },
        upsert -> {
          counters.repository.incrementAndGet();
          return actualRepository.upsert(upsert);
        },
        runService,
        snapshotService,
        clock);
  }

  private static ReferenceCodeSourceResponse response() {
    return new ReferenceCodeSourceResponse(
        "{\"response\":{}}".getBytes(), SnapshotPayloadFormat.JSON);
  }

  private static ReferenceCodeSyncCommand command(String idempotencyKey) {
    return new ReferenceCodeSyncCommand(
        ReferenceCodeOperation.LDONG, LocalDate.of(2026, 1, 12), null, idempotencyKey);
  }

  private void assertSingleExecution(Counters counters) {
    assertThat(counters.source).hasValue(1);
    assertThat(counters.parser).hasValue(1);
    assertThat(counters.repository).hasValue(1);
  }

  private Map<String, Object> state() {
    return Map.of(
        "runs",
            jdbcTemplate.queryForList(
                "select id, status, row_count, fetched_count, inserted_count, updated_count, skipped_count, finished_at from public.data_import_runs"),
        "snapshots",
            jdbcTemplate.queryForList(
                "select id, import_run_id, parse_status, parsed_at, payload_hash from public.external_api_snapshots"),
        "codes",
            jdbcTemplate.queryForList(
                "select id, code_name, source_snapshot_id, import_run_id, last_seen_at, updated_at from public.external_reference_codes"),
        "provenance",
            jdbcTemplate.queryForList(
                "select normalized_row_id, operation_key, request_fingerprint, source_snapshot_id, import_run_id, created_at from public.tour_api_operation_provenance"));
  }

  private int tableCount(String table) {
    return jdbcTemplate.queryForObject("select count(*) from public." + table, Integer.class);
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.external_reference_codes");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }

  private static final class Counters {
    private final AtomicInteger source = new AtomicInteger();
    private final AtomicInteger parser = new AtomicInteger();
    private final AtomicInteger repository = new AtomicInteger();
  }
}
