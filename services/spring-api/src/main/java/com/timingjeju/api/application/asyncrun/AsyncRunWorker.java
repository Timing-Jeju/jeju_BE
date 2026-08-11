package com.timingjeju.api.application.asyncrun;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
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
  private final Object lifecycleMonitor = new Object();
  private boolean acceptingClaims = true;

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
    synchronized (lifecycleMonitor) {
      if (!acceptingClaims) {
        return;
      }
      Instant claimedAt = clock.instant();
      List<RunLease> claims =
          repository.claimAvailable(workerId, policy.leaseDuration(), policy.claimBatchSize());
      for (RunLease lease : claims) {
        submit(lease, claimedAt);
      }
    }
  }

  public void shutdown() {
    synchronized (lifecycleMonitor) {
      if (!acceptingClaims) {
        return;
      }
      acceptingClaims = false;
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
          .whenComplete((resultSource, failure) -> complete(lease, resultSource, failure));
    } catch (RuntimeException exception) {
      complete(lease, null, exception);
    }
  }

  private void complete(RunLease lease, RunResultSource resultSource, Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause == null) {
      if (resultSource == null) {
        repository.fail(lease, UNEXPECTED_ERROR_CODE);
      } else {
        repository.succeed(lease, resultSource);
      }
    } else if (cause instanceof RunLeaseLostException) {
      // A newer fencing token owns the run. This worker must not write any terminal state.
    } else if (cause instanceof RetryableRunException exception) {
      handleRetryableFailure(lease, exception);
    } else {
      repository.fail(lease, UNEXPECTED_ERROR_CODE);
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
    return repository.heartbeat(lease, policy.leaseDuration());
  }

  private void handleRetryableFailure(RunLease lease, RetryableRunException exception) {
    if (lease.attempt() >= policy.maxAttempts()) {
      repository.fail(lease, exception.stableErrorCode());
      return;
    }
    repository.retry(
        lease, policy.retryDelay(lease.attempt(), jitter), exception.stableErrorCode());
  }
}
