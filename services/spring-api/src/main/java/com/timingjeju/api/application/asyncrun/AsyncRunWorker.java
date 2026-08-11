package com.timingjeju.api.application.asyncrun;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;

public final class AsyncRunWorker {

  private static final String UNEXPECTED_ERROR_CODE = "ASYNC_RUN_EXECUTION_FAILED";

  private final String workerId;
  private final RunLeaseRepository repository;
  private final AsyncRunExecutor executor;
  private final RunExecutionSupervisor supervisor;
  private final RunExecutionPolicy policy;
  private final Clock clock;
  private final DoubleSupplier jitter;
  private final AtomicBoolean acceptingClaims = new AtomicBoolean(true);

  public AsyncRunWorker(
      String workerId,
      RunLeaseRepository repository,
      AsyncRunExecutor executor,
      RunExecutionSupervisor supervisor,
      RunExecutionPolicy policy,
      Clock clock,
      DoubleSupplier jitter) {
    if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
      throw new IllegalArgumentException("workerId는 1~100자의 비공백 값이어야 합니다.");
    }
    this.workerId = workerId;
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
    this.supervisor = Objects.requireNonNull(supervisor, "supervisor는 필수입니다.");
    this.policy = Objects.requireNonNull(policy, "policy는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.jitter = Objects.requireNonNull(jitter, "jitter는 필수입니다.");
  }

  public void pollOnce() {
    if (!acceptingClaims.get()) {
      return;
    }
    Instant claimedAt = clock.instant();
    List<RunLease> claims =
        repository.claimAvailable(
            workerId, claimedAt, claimedAt.plus(policy.leaseDuration()), policy.claimBatchSize());
    for (RunLease lease : claims) {
      submit(lease, claimedAt);
    }
  }

  public void shutdown() {
    if (acceptingClaims.compareAndSet(true, false)) {
      supervisor.shutdown(policy.executionDeadline());
    }
  }

  private void submit(RunLease lease, Instant claimedAt) {
    try {
      supervisor
          .supervise(
              lease,
              claimedAt.plus(policy.executionDeadline()),
              policy.heartbeatInterval(),
              executor,
              () -> heartbeat(lease))
          .whenComplete((ignored, failure) -> complete(lease, failure));
    } catch (RuntimeException exception) {
      complete(lease, exception);
    }
  }

  private void complete(RunLease lease, Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause == null) {
      repository.succeed(lease, clock.instant());
    } else if (cause instanceof RunLeaseLostException) {
      // A newer fencing token owns the run. This worker must not write any terminal state.
    } else if (cause instanceof RetryableRunException exception) {
      handleRetryableFailure(lease, exception);
    } else {
      repository.fail(lease, clock.instant(), UNEXPECTED_ERROR_CODE);
    }
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private boolean heartbeat(RunLease lease) {
    Instant now = clock.instant();
    return repository.heartbeat(lease, now, now.plus(policy.leaseDuration()));
  }

  private void handleRetryableFailure(RunLease lease, RetryableRunException exception) {
    Instant failedAt = clock.instant();
    if (lease.attempt() >= policy.maxAttempts()) {
      repository.fail(lease, failedAt, exception.stableErrorCode());
      return;
    }
    repository.retry(
        lease,
        failedAt.plus(policy.retryDelay(lease.attempt(), jitter)),
        exception.stableErrorCode());
  }
}
