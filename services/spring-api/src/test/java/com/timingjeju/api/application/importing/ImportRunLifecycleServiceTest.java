package com.timingjeju.api.application.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ImportRunLifecycleServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
  private static final UUID RUN_ID = UUID.fromString("22000000-0000-0000-0000-000000000001");
  private static final UUID OWNER_TOKEN = UUID.fromString("22000000-0000-0000-0000-000000000002");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN_ID, OWNER_TOKEN, 1);

  @Test
  void 시작은_고정_clock과_identity_generator를_port에_전달한다() {
    RecordingStore store = new RecordingStore();
    ImportRunLifecycleService service = service(store);

    ImportRunStartResult result = service.start(command(null, "request-1"));

    assertThat(result).isEqualTo(new ImportRunStartResult(LEASE, false));
    assertThat(store.starts)
        .containsExactly(new StartCall(command(null, "request-1"), RUN_ID, OWNER_TOKEN, NOW));
  }

  @Test
  void count는_음수와_덧셈_overflow를_입력_경계에서_거부한다() {
    assertThatThrownBy(() -> new ImportRunCounts(-1, 0, 0, 0, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ImportRunCounts(Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0)
                    .plus(new ImportRunCounts(1, 0, 0, 0, 0, 0, 0, 0)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void 허용하지_않는_terminal과_stale_owner는_안정적인_도메인_code로_변환한다() {
    RecordingStore store = new RecordingStore();
    ImportRunLifecycleService service = service(store);
    store.outcome = ImportRunMutationOutcome.INVALID_TRANSITION;

    assertThatThrownBy(() -> service.succeed(LEASE, ImportRunCounts.zero()))
        .isInstanceOf(ImportRunLifecycleException.class)
        .extracting("code")
        .isEqualTo(ImportRunLifecycleError.INVALID_TRANSITION);

    store.outcome = ImportRunMutationOutcome.OWNERSHIP_LOST;
    assertThatThrownBy(() -> service.addCounts(LEASE, ImportRunCounts.zero()))
        .isInstanceOf(ImportRunLifecycleException.class)
        .extracting("code")
        .isEqualTo(ImportRunLifecycleError.OWNERSHIP_LOST);
  }

  @Test
  void partial_failed_cancelled는_원문_예외가_아닌_고정_failure만_port에_전달한다() {
    RecordingStore store = new RecordingStore();
    ImportRunLifecycleService service = service(store);
    ImportRunCounts delta = new ImportRunCounts(3, 5, 1, 1, 1, 2, 0, 0);

    service.completePartial(LEASE, delta, ImportRunFailure.PARSE_REJECTED);
    service.fail(LEASE, ImportRunFailure.PROVIDER_UNAVAILABLE);
    service.cancel(LEASE);

    assertThat(store.terminals)
        .containsExactly(
            new TerminalCall(
                LEASE, ImportRunStatus.PARTIAL, delta, ImportRunFailure.PARSE_REJECTED, NOW),
            new TerminalCall(
                LEASE,
                ImportRunStatus.FAILED,
                ImportRunCounts.zero(),
                ImportRunFailure.PROVIDER_UNAVAILABLE,
                NOW),
            new TerminalCall(
                LEASE,
                ImportRunStatus.CANCELLED,
                ImportRunCounts.zero(),
                ImportRunFailure.CANCELLED,
                NOW));
    assertThat(ImportRunFailure.PROVIDER_UNAVAILABLE.detail())
        .doesNotContain("exception", "token", "http", "@", "?");
  }

  @Test
  void retry_command는_parent와_parser_schema_version을_그대로_보존한다() {
    UUID parent = UUID.fromString("22000000-0000-0000-0000-000000000099");
    ImportRunStartCommand retry = command(parent, "retry-key");

    assertThat(retry.parentRunId()).contains(parent);
    assertThat(retry.parserVersion()).isEqualTo("parser-v3");
    assertThat(retry.schemaVersion()).isEqualTo("tour-api-2026-01");
  }

  @Test
  void start_command는_필수_source_scope_version_idempotency_공백을_거부한다() {
    assertThatThrownBy(
            () ->
                new ImportRunStartCommand(
                    ImportSourceKind.TOUR_API,
                    " ",
                    new ImportRunScope("tour-api", "KorService2", "areaBasedList2", "jeju"),
                    "v1",
                    "parser-v1",
                    "schema-v1",
                    ImportSyncMode.FULL,
                    "fingerprint",
                    "key",
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ImportRunScope("tour-api", " ", "operation", "scope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ImportRunLifecycleService service(RecordingStore store) {
    return new ImportRunLifecycleService(
        store,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new ImportRunIdentityGenerator() {
          @Override
          public UUID newRunId() {
            return RUN_ID;
          }

          @Override
          public UUID newOwnerToken() {
            return OWNER_TOKEN;
          }
        });
  }

  private static ImportRunStartCommand command(UUID parentRunId, String idempotencyKey) {
    return new ImportRunStartCommand(
        ImportSourceKind.TOUR_API,
        "한국관광공사",
        new ImportRunScope("tour-api", "KorService2", "areaBasedList2", "jeju"),
        "2026-08-13",
        "parser-v3",
        "tour-api-2026-01",
        ImportSyncMode.INCREMENTAL,
        "sha256:fixture-request",
        idempotencyKey,
        parentRunId);
  }

  private static final class RecordingStore implements ImportRunStore {
    private final List<StartCall> starts = new ArrayList<>();
    private final List<TerminalCall> terminals = new ArrayList<>();
    private ImportRunMutationOutcome outcome = ImportRunMutationOutcome.UPDATED;

    @Override
    public ImportRunStartResult start(
        ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
      starts.add(new StartCall(command, runId, ownerToken, startedAt));
      return new ImportRunStartResult(LEASE, false);
    }

    @Override
    public ImportRunMutationOutcome addCounts(ImportRunLease lease, ImportRunCounts delta) {
      return outcome;
    }

    @Override
    public ImportRunMutationOutcome finish(
        ImportRunLease lease,
        ImportRunStatus status,
        ImportRunCounts delta,
        ImportRunFailure failure,
        Instant finishedAt) {
      terminals.add(new TerminalCall(lease, status, delta, failure, finishedAt));
      return outcome;
    }
  }

  private record StartCall(
      ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {}

  private record TerminalCall(
      ImportRunLease lease,
      ImportRunStatus status,
      ImportRunCounts counts,
      ImportRunFailure failure,
      Instant finishedAt) {}
}
