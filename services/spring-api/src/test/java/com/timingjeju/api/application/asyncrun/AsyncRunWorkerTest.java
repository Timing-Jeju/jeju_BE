package com.timingjeju.api.application.asyncrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AsyncRunWorkerTest {

  private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
  private static final UUID RUN_ID = UUID.fromString("74000000-0000-0000-0000-000000000001");

  @Test
  void poll은_최대_50개를_claim하고_성공한_run을_fencing_token으로_완료한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 7, 1)));
    RecordingExecutor executor = new RecordingExecutor();
    TestWorker worker = worker(repository, executor, () -> 0.5d);

    worker.pollOnce();

    assertThat(repository.claimLimit).isEqualTo(50);
    assertThat(repository.claimLeaseUntil).isEqualTo(NOW.plusSeconds(30));
    assertThat(executor.deadline).isEqualTo(NOW.plusSeconds(60));
    assertThat(repository.heartbeatLeaseUntil).isEqualTo(NOW.plusSeconds(30));
    assertThat(repository.succeeded).containsExactly(new RunLease(RUN_ID, 7, 1));
  }

  @Test
  void retryable_실패는_full_jitter_backoff로_queued에_되돌린다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 8, 3)));
    RecordingExecutor executor = new RecordingExecutor();
    executor.failure = new RetryableRunException("MCP_TEMPORARY");

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.retriedAt).isEqualTo(NOW.plusSeconds(2));
    assertThat(repository.errorCode).isEqualTo("MCP_TEMPORARY");
    assertThat(repository.failed).isEmpty();
  }

  @Test
  void 다섯번째_시도에서_retryable_실패하면_terminal_failed로_전이한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 9, 5)));
    RecordingExecutor executor = new RecordingExecutor();
    executor.failure = new RetryableRunException("MCP_TEMPORARY");

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.failed).containsExactly(new RunLease(RUN_ID, 9, 5));
    assertThat(repository.retriedAt).isNull();
  }

  @Test
  void shutdown을_시작하면_새_run을_claim하지_않는다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 10, 1)));
    TestWorker worker = worker(repository, new RecordingExecutor(), () -> 0.5d);

    worker.shutdown();
    worker.pollOnce();

    assertThat(repository.claimCalls).isZero();
    assertThat(worker.supervisor.shutdownCalls).isEqualTo(1);
  }

  @Test
  void repository가_stale_fencing_완료를_거부하면_worker는_권한을_되살리지_않는다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 11, 1)));
    repository.terminalAccepted = false;

    worker(repository, new RecordingExecutor(), () -> 0.5d).pollOnce();

    assertThat(repository.succeeded).containsExactly(new RunLease(RUN_ID, 11, 1));
    assertThat(repository.forceWrites).isZero();
  }

  private static TestWorker worker(
      RecordingRepository repository, RecordingExecutor executor, DoubleSupplier jitter) {
    RecordingSupervisor supervisor = new RecordingSupervisor();
    return new TestWorker(
        new AsyncRunWorker(
            "worker-74",
            repository,
            executor,
            supervisor,
            RunExecutionPolicy.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            jitter),
        supervisor);
  }

  private record TestWorker(AsyncRunWorker worker, RecordingSupervisor supervisor) {
    private void pollOnce() {
      worker.pollOnce();
    }

    private void shutdown() {
      worker.shutdown();
    }
  }

  private static final class RecordingSupervisor implements RunExecutionSupervisor {
    private int shutdownCalls;

    @Override
    public java.util.concurrent.CompletableFuture<Void> supervise(
        RunLease lease,
        Instant deadline,
        java.time.Duration heartbeatInterval,
        AsyncRunExecutor executor,
        java.util.function.BooleanSupplier heartbeat) {
      try {
        heartbeat.getAsBoolean();
        executor.execute(lease, deadline);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
      } catch (RuntimeException failure) {
        return java.util.concurrent.CompletableFuture.failedFuture(failure);
      }
    }

    @Override
    public void shutdown(java.time.Duration drainTimeout) {
      shutdownCalls++;
    }
  }

  private static final class RecordingExecutor implements AsyncRunExecutor {
    private Instant deadline;
    private RuntimeException failure;

    @Override
    public void execute(RunLease lease, Instant deadline) {
      this.deadline = deadline;
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static final class RecordingRepository implements RunLeaseRepository {
    private final List<RunLease> claims;
    private final List<RunLease> succeeded = new ArrayList<>();
    private final List<RunLease> failed = new ArrayList<>();
    private int claimCalls;
    private int claimLimit;
    private Instant claimLeaseUntil;
    private Instant retriedAt;
    private Instant heartbeatLeaseUntil;
    private String errorCode;
    private boolean terminalAccepted = true;
    private int forceWrites;

    private RecordingRepository(List<RunLease> claims) {
      this.claims = claims;
    }

    @Override
    public List<RunLease> claimAvailable(
        String workerId, Instant now, Instant leaseUntil, int limit) {
      claimCalls++;
      claimLimit = limit;
      claimLeaseUntil = leaseUntil;
      return claims;
    }

    @Override
    public boolean heartbeat(RunLease lease, Instant now, Instant leaseUntil) {
      heartbeatLeaseUntil = leaseUntil;
      return true;
    }

    @Override
    public boolean succeed(RunLease lease, Instant completedAt) {
      succeeded.add(lease);
      return terminalAccepted;
    }

    @Override
    public boolean retry(RunLease lease, Instant nextAttemptAt, String stableErrorCode) {
      retriedAt = nextAttemptAt;
      errorCode = stableErrorCode;
      return terminalAccepted;
    }

    @Override
    public boolean fail(RunLease lease, Instant completedAt, String stableErrorCode) {
      failed.add(lease);
      errorCode = stableErrorCode;
      return terminalAccepted;
    }
  }
}
