package com.timingjeju.api.application.asyncrun;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class ThreadedRunExecutionSupervisor implements RunExecutionSupervisor {

  private static final String DEADLINE_EXCEEDED = "ASYNC_RUN_DEADLINE_EXCEEDED";

  private final Clock clock;
  private final ExecutorService taskExecutor;
  private final ScheduledExecutorService heartbeatScheduler;
  private final AtomicBoolean shutdown = new AtomicBoolean();

  ThreadedRunExecutionSupervisor(
      Clock clock, ExecutorService taskExecutor, ScheduledExecutorService heartbeatScheduler) {
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor는 필수입니다.");
    this.heartbeatScheduler =
        Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler는 필수입니다.");
  }

  public static ThreadedRunExecutionSupervisor create(Clock clock, int concurrency) {
    if (concurrency <= 0 || concurrency > 50) {
      throw new IllegalArgumentException("concurrency는 1~50이어야 합니다.");
    }
    return new ThreadedRunExecutionSupervisor(
        clock,
        Executors.newFixedThreadPool(concurrency, namedThreads("async-run-task-")),
        Executors.newScheduledThreadPool(concurrency, namedThreads("async-run-heartbeat-")));
  }

  @Override
  public CompletableFuture<Void> supervise(
      RunLease lease,
      Instant deadline,
      Duration heartbeatInterval,
      AsyncRunExecutor executor,
      BooleanSupplier heartbeat) {
    if (shutdown.get()) {
      throw new IllegalStateException("shutdown 이후에는 run 실행을 시작할 수 없습니다.");
    }
    Objects.requireNonNull(lease, "lease는 필수입니다.");
    Objects.requireNonNull(deadline, "deadline은 필수입니다.");
    Objects.requireNonNull(executor, "executor는 필수입니다.");
    Objects.requireNonNull(heartbeat, "heartbeat은 필수입니다.");
    if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
      throw new IllegalArgumentException("heartbeatInterval은 양수여야 합니다.");
    }

    long remainingNanos = Duration.between(clock.instant(), deadline).toNanos();
    if (remainingNanos <= 0) {
      return CompletableFuture.failedFuture(new RetryableRunException(DEADLINE_EXCEEDED));
    }

    CompletableFuture<Void> completion = new CompletableFuture<>();
    Future<?> execution =
        taskExecutor.submit(
            () -> {
              try {
                executor.execute(lease, deadline);
                completion.complete(null);
              } catch (Throwable failure) {
                completion.completeExceptionally(failure);
              }
            });
    long heartbeatNanos = heartbeatInterval.toNanos();
    ScheduledFuture<?> heartbeatTask =
        heartbeatScheduler.scheduleAtFixedRate(
            () -> {
              try {
                if (heartbeat.getAsBoolean()) {
                  return;
                }
                completion.completeExceptionally(new RunLeaseLostException());
                execution.cancel(true);
              } catch (RuntimeException failure) {
                completion.completeExceptionally(new RunLeaseLostException());
                execution.cancel(true);
              }
            },
            heartbeatNanos,
            heartbeatNanos,
            TimeUnit.NANOSECONDS);
    ScheduledFuture<?> deadlineTask =
        heartbeatScheduler.schedule(
            () -> {
              completion.completeExceptionally(new RetryableRunException(DEADLINE_EXCEEDED));
              execution.cancel(true);
            },
            remainingNanos,
            TimeUnit.NANOSECONDS);
    completion.whenComplete(
        (ignored, failure) -> {
          heartbeatTask.cancel(false);
          deadlineTask.cancel(false);
        });
    return completion;
  }

  @Override
  public void shutdown(Duration drainTimeout) {
    if (!shutdown.compareAndSet(false, true)) {
      return;
    }
    if (drainTimeout == null || drainTimeout.isNegative()) {
      throw new IllegalArgumentException("drainTimeout은 0 이상이어야 합니다.");
    }
    taskExecutor.shutdown();
    try {
      if (!taskExecutor.awaitTermination(drainTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
        taskExecutor.shutdownNow();
      }
    } catch (InterruptedException exception) {
      taskExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      heartbeatScheduler.shutdownNow();
    }
  }

  private static ThreadFactory namedThreads(String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
