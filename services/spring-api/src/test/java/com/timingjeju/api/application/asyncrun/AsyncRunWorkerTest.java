package com.timingjeju.api.application.asyncrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AsyncRunWorkerTest {

  private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
  private static final UUID RUN_ID = UUID.fromString("74000000-0000-0000-0000-000000000001");

  @Test
  void worker_id는_비공백_100자_이하여야_한다() {
    RecordingRepository repository = new RecordingRepository(List.of());
    RecordingExecutor executor = new RecordingExecutor();
    RecordingSupervisor supervisor = new RecordingSupervisor();

    assertThatThrownBy(
            () -> newWorker("", repository, executor, supervisor, RunExecutionPolicy.defaults()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                newWorker(
                    "x".repeat(101),
                    repository,
                    executor,
                    supervisor,
                    RunExecutionPolicy.defaults()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void poll은_최대_50개를_claim하고_성공한_run을_fencing_token으로_완료한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 7, 1)));
    RecordingExecutor executor = new RecordingExecutor();

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.claimLimit).isEqualTo(50);
    assertThat(repository.claimLeaseDuration).isEqualTo(Duration.ofSeconds(30));
    assertThat(executor.deadline).isEqualTo(NOW.plusSeconds(60));
    assertThat(repository.heartbeatLeaseDuration).isEqualTo(Duration.ofSeconds(30));
    assertThat(repository.succeeded).containsExactly(new RunLease(RUN_ID, 7, 1));
    assertThat(repository.resultSource).isEqualTo(RunResultSource.COMPUTED);
  }

  @Test
  void executor가_fallback을_반환하면_fencing_완료에_fallback_source를_전달한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 12, 1)));
    RecordingExecutor executor = new RecordingExecutor();
    executor.resultSource = RunResultSource.FALLBACK;

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.resultSource).isEqualTo(RunResultSource.FALLBACK);
  }

  @Test
  void retryable_실패는_full_jitter_backoff_duration으로_queued에_되돌린다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 8, 3)));
    RecordingExecutor executor = new RecordingExecutor();
    executor.failure = new RetryableRunException("MCP_TEMPORARY");

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.retryDelay).isEqualTo(Duration.ofSeconds(2));
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
    assertThat(repository.retryDelay).isNull();
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
  void shutdown은_한번만_supervisor를_닫는다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 10, 1)));
    TestWorker worker = worker(repository, new RecordingExecutor(), () -> 0.5d);

    worker.shutdown();
    worker.shutdown();

    assertThat(worker.supervisor.shutdownCalls).isEqualTo(1);
  }

  @Test
  void poll이_claim중일_때_shutdown은_claim된_run의_submit이_끝난_뒤_supervisor를_닫는다() throws Exception {
    BlockingClaimRepository repository = new BlockingClaimRepository();
    RecordingSupervisor supervisor = new RecordingSupervisor();
    AsyncRunWorker worker = worker(repository, new RecordingExecutor(), supervisor, () -> 0.5d);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      var poll = pool.submit(worker::pollOnce);
      assertThat(repository.claimEntered.await(1, TimeUnit.SECONDS)).isTrue();
      var shutdown = pool.submit(worker::shutdown);
      repository.releaseClaim.countDown();
      poll.get(1, TimeUnit.SECONDS);
      shutdown.get(1, TimeUnit.SECONDS);
    }

    assertThat(supervisor.superviseCalls).isEqualTo(1);
    assertThat(supervisor.superviseAfterShutdown).isFalse();
    assertThat(supervisor.shutdownCalls).isEqualTo(1);
  }

  @Test
  void repository가_stale_fencing_완료를_거부하면_worker는_권한을_되살리지_않는다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 11, 1)));
    repository.terminalAccepted = false;

    worker(repository, new RecordingExecutor(), () -> 0.5d).pollOnce();

    assertThat(repository.succeeded).containsExactly(new RunLease(RUN_ID, 11, 1));
    assertThat(repository.forceWrites).isZero();
  }

  @Test
  void supervisor가_submit을_거부하면_예상치_못한_실패로_terminal_failed를_시도한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 14, 1)));
    RecordingSupervisor supervisor = new RecordingSupervisor();
    supervisor.superviseFailure = new IllegalStateException("closed");

    worker(repository, new RecordingExecutor(), supervisor, () -> 0.5d).pollOnce();

    assertThat(repository.failed).containsExactly(new RunLease(RUN_ID, 14, 1));
    assertThat(repository.errorCode).isEqualTo("ASYNC_RUN_EXECUTION_FAILED");
  }

  @Test
  void supervisor가_null_result로_완료하면_terminal_failed를_시도한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 15, 1)));
    RecordingSupervisor supervisor = new RecordingSupervisor();
    supervisor.forcedFuture = CompletableFuture.completedFuture(null);

    worker(repository, new RecordingExecutor(), supervisor, () -> 0.5d).pollOnce();

    assertThat(repository.failed).containsExactly(new RunLease(RUN_ID, 15, 1));
    assertThat(repository.errorCode).isEqualTo("ASYNC_RUN_EXECUTION_FAILED");
  }

  @Test
  void completion_exception으로_감싼_retryable_실패도_unwrap해서_retry한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 16, 2)));
    RecordingSupervisor supervisor = new RecordingSupervisor();
    supervisor.forcedFuture =
        CompletableFuture.failedFuture(
            new CompletionException(new RetryableRunException("MCP_TEMPORARY")));

    worker(repository, new RecordingExecutor(), supervisor, () -> 0.5d).pollOnce();

    assertThat(repository.retryDelay).isEqualTo(Duration.ofSeconds(1));
    assertThat(repository.errorCode).isEqualTo("MCP_TEMPORARY");
    assertThat(repository.failed).isEmpty();
  }

  @Test
  void lease를_잃은_완료는_terminal_상태를_쓰지_않는다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 17, 1)));
    RecordingSupervisor supervisor = new RecordingSupervisor();
    supervisor.forcedFuture = CompletableFuture.failedFuture(new RunLeaseLostException());

    worker(repository, new RecordingExecutor(), supervisor, () -> 0.5d).pollOnce();

    assertThat(repository.succeeded).isEmpty();
    assertThat(repository.failed).isEmpty();
    assertThat(repository.retryDelay).isNull();
  }

  @Test
  void 예상하지_못한_executor_예외는_terminal_failed를_시도한다() {
    RecordingRepository repository = new RecordingRepository(List.of(new RunLease(RUN_ID, 18, 1)));
    RecordingExecutor executor = new RecordingExecutor();
    executor.failure = new IllegalStateException("boom");

    worker(repository, executor, () -> 0.5d).pollOnce();

    assertThat(repository.failed).containsExactly(new RunLease(RUN_ID, 18, 1));
    assertThat(repository.errorCode).isEqualTo("ASYNC_RUN_EXECUTION_FAILED");
  }

  private static TestWorker worker(
      RecordingRepository repository, RecordingExecutor executor, DoubleSupplier jitter) {
    RecordingSupervisor supervisor = new RecordingSupervisor();
    return new TestWorker(worker(repository, executor, supervisor, jitter), supervisor);
  }

  private static AsyncRunWorker worker(
      RecordingRepository repository,
      RecordingExecutor executor,
      RecordingSupervisor supervisor,
      DoubleSupplier jitter) {
    return newWorker(
        "worker-74", repository, executor, supervisor, RunExecutionPolicy.defaults(), jitter);
  }

  private static AsyncRunWorker newWorker(
      String workerId,
      RecordingRepository repository,
      RecordingExecutor executor,
      RecordingSupervisor supervisor,
      RunExecutionPolicy policy) {
    return newWorker(workerId, repository, executor, supervisor, policy, () -> 0.5d);
  }

  private static AsyncRunWorker newWorker(
      String workerId,
      RecordingRepository repository,
      RecordingExecutor executor,
      RecordingSupervisor supervisor,
      RunExecutionPolicy policy,
      DoubleSupplier jitter) {
    return new AsyncRunWorker(
        workerId,
        repository,
        executor,
        supervisor,
        policy,
        Clock.fixed(NOW, ZoneOffset.UTC),
        jitter);
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
    private int superviseCalls;
    private boolean closed;
    private boolean superviseAfterShutdown;
    private RuntimeException superviseFailure;
    private CompletableFuture<RunResultSource> forcedFuture;

    @Override
    public CompletableFuture<RunResultSource> supervise(
        RunLease lease,
        Instant deadline,
        Duration heartbeatInterval,
        AsyncRunExecutor executor,
        java.util.function.BooleanSupplier heartbeat) {
      superviseCalls++;
      superviseAfterShutdown |= closed;
      if (superviseFailure != null) {
        throw superviseFailure;
      }
      if (forcedFuture != null) {
        return forcedFuture;
      }
      try {
        heartbeat.getAsBoolean();
        return CompletableFuture.completedFuture(executor.execute(lease, deadline));
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }

    @Override
    public void shutdown(Duration drainTimeout) {
      closed = true;
      shutdownCalls++;
    }
  }

  private static final class RecordingExecutor implements AsyncRunExecutor {
    private Instant deadline;
    private RuntimeException failure;
    private RunResultSource resultSource = RunResultSource.COMPUTED;

    @Override
    public RunResultSource execute(RunLease lease, Instant deadline) {
      this.deadline = deadline;
      if (failure != null) {
        throw failure;
      }
      return resultSource;
    }
  }

  private static class RecordingRepository implements RunLeaseRepository {
    private final List<RunLease> claims;
    private final List<RunLease> succeeded = new ArrayList<>();
    private final List<RunLease> failed = new ArrayList<>();
    private int claimCalls;
    private int claimLimit;
    private Duration claimLeaseDuration;
    private Duration retryDelay;
    private Duration heartbeatLeaseDuration;
    private String errorCode;
    private RunResultSource resultSource;
    private boolean terminalAccepted = true;
    private int forceWrites;

    private RecordingRepository(List<RunLease> claims) {
      this.claims = claims;
    }

    @Override
    public List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit) {
      claimCalls++;
      claimLimit = limit;
      claimLeaseDuration = leaseDuration;
      return claims;
    }

    @Override
    public boolean heartbeat(RunLease lease, Duration leaseDuration) {
      heartbeatLeaseDuration = leaseDuration;
      return true;
    }

    @Override
    public boolean succeed(RunLease lease, RunResultSource resultSource) {
      succeeded.add(lease);
      this.resultSource = resultSource;
      return terminalAccepted;
    }

    @Override
    public boolean retry(RunLease lease, Duration retryDelay, String stableErrorCode) {
      this.retryDelay = retryDelay;
      errorCode = stableErrorCode;
      return terminalAccepted;
    }

    @Override
    public boolean fail(RunLease lease, String stableErrorCode) {
      failed.add(lease);
      errorCode = stableErrorCode;
      return terminalAccepted;
    }
  }

  private static final class BlockingClaimRepository extends RecordingRepository {
    private final CountDownLatch claimEntered = new CountDownLatch(1);
    private final CountDownLatch releaseClaim = new CountDownLatch(1);

    private BlockingClaimRepository() {
      super(List.of(new RunLease(RUN_ID, 13, 1)));
    }

    @Override
    public List<RunLease> claimAvailable(String workerId, Duration leaseDuration, int limit) {
      claimEntered.countDown();
      try {
        if (!releaseClaim.await(1, TimeUnit.SECONDS)) {
          throw new AssertionError("claim release timeout");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
      return super.claimAvailable(workerId, leaseDuration, limit);
    }
  }
}
