package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.asyncrun.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.DoubleSupplier;

/** 단일 생성 claim을 supervision 아래 실행하고 후보·성공을 원자 저장 경계에 위임한다. */
public final class GenerationWorker {
  private final String workerId;
  private final GenerationRunLeases leases;
  private final GenerationPlanExecutor planner;
  private final GenerationCompletionStore completions;
  private final RunExecutionSupervisor supervisor;
  private final RunExecutionPolicy policy;
  private final Clock clock;
  private final DoubleSupplier jitter;
  private final Object lifecycle = new Object();
  private boolean accepting = true;
  private ActiveRun inFlight;

  public GenerationWorker(
      String workerId,
      GenerationRunLeases leases,
      GenerationPlanExecutor planner,
      GenerationCompletionStore completions,
      RunExecutionSupervisor supervisor,
      RunExecutionPolicy policy,
      Clock clock,
      DoubleSupplier jitter) {
    if (workerId == null || !workerId.matches("[A-Za-z0-9._:-]{1,100}"))
      throw new IllegalArgumentException("생성 worker identity가 필요합니다.");
    this.workerId = workerId;
    this.leases = Objects.requireNonNull(leases);
    this.planner = Objects.requireNonNull(planner);
    this.completions = Objects.requireNonNull(completions);
    this.supervisor = Objects.requireNonNull(supervisor);
    this.policy = Objects.requireNonNull(policy);
    if (policy.claimBatchSize() != 1
        || policy.maxAttempts() != 3
        || policy.leaseDuration().compareTo(java.time.Duration.ofSeconds(165)) < 0)
      throw new IllegalArgumentException("생성 전용 실행 정책이 필요합니다.");
    this.clock = Objects.requireNonNull(clock);
    this.jitter = Objects.requireNonNull(jitter);
  }

  public void pollOnce() {
    synchronized (lifecycle) {
      if (!accepting || inFlight != null) return;
      var claimedAt = clock.instant();
      var claims = leases.claimAvailable(workerId, policy.leaseDuration(), 1);
      if (claims.isEmpty()) return;
      var lease = claims.getFirst();
      var active = new ActiveRun();
      inFlight = active;
      try {
        supervisor
            .supervise(
                lease,
                claimedAt.plus(policy.executionDeadline()),
                policy.heartbeatInterval(),
                (claimed, deadline) -> executeTracked(active, claimed, deadline),
                () -> leases.heartbeat(lease, policy.leaseDuration()))
            .whenComplete((result, failure) -> finish(active, lease, result, failure));
      } catch (RuntimeException failure) {
        finish(active, lease, null, failure);
      }
    }
  }

  private RunResultSource executeTracked(ActiveRun active, RunLease lease, Instant deadline) {
    synchronized (lifecycle) {
      if (active.terminal) throw new RunLeaseLostException();
      active.running = true;
    }
    try {
      return execute(active, lease, deadline);
    } finally {
      synchronized (lifecycle) {
        active.running = false;
        release(active);
      }
    }
  }

  private RunResultSource execute(ActiveRun active, RunLease lease, Instant deadline) {
    checkDeadline(deadline);
    boolean owned;
    try {
      owned = leases.heartbeat(lease, policy.leaseDuration());
    } catch (RuntimeException failure) {
      throw new RunLeaseLostException();
    }
    if (!owned) throw new RunLeaseLostException();
    var result = Objects.requireNonNull(planner.execute(lease.runId(), deadline));
    checkDeadline(deadline);
    synchronized (lifecycle) {
      if (active.terminal) throw new RunLeaseLostException();
    }
    if (!completions.complete(lease, result, deadline)) throw new RunLeaseLostException();
    // 내부 supervision 완료 marker일 뿐 DB 성공 전이가 아니다. 저장 port가 이미 commit했다.
    return RunResultSource.COMPUTED;
  }

  private void finish(ActiveRun active, RunLease lease, RunResultSource result, Throwable failure) {
    synchronized (lifecycle) {
      active.terminal = true;
    }
    try {
      var cause = failure;
      while (cause instanceof CompletionException && cause.getCause() != null)
        cause = cause.getCause();
      if (cause instanceof RunLeaseLostException) return;
      if (cause == null && result == RunResultSource.COMPUTED) return;
      if (cause instanceof RetryableRunException retryable) {
        if (lease.attempt() >= policy.maxAttempts())
          leases.fail(lease, retryable.stableErrorCode());
        else
          leases.retry(
              lease, policy.retryDelay(lease.attempt(), jitter), retryable.stableErrorCode());
      } else {
        leases.fail(
            lease,
            cause instanceof GenerationException generation
                ? generation.code()
                : "GENERATION_EXECUTION_FAILED");
      }
    } finally {
      synchronized (lifecycle) {
        active.finalized = true;
        release(active);
      }
    }
  }

  private void release(ActiveRun active) {
    if (inFlight == active && active.finalized && !active.running) inFlight = null;
  }

  private static final class ActiveRun {
    private boolean running;
    private boolean terminal;
    private boolean finalized;
  }

  private void checkDeadline(Instant deadline) {
    if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline))
      throw new RetryableRunException("ASYNC_RUN_DEADLINE_EXCEEDED");
  }

  public void shutdown() {
    synchronized (lifecycle) {
      if (!accepting) return;
      accepting = false;
    }
    supervisor.shutdown(policy.executionDeadline());
  }
}
