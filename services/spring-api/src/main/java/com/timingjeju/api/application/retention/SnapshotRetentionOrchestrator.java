package com.timingjeju.api.application.retention;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public class SnapshotRetentionOrchestrator {
  private final SnapshotRetentionService service;
  private final SnapshotRetentionSleeper sleeper;
  private final SnapshotRetentionCycleMetrics metrics;
  private final LongSupplier monotonicTime;

  public SnapshotRetentionOrchestrator(
      SnapshotRetentionService service,
      SnapshotRetentionSleeper sleeper,
      SnapshotRetentionCycleMetrics metrics,
      LongSupplier monotonicTime) {
    this.service = Objects.requireNonNull(service, "service는 필수입니다.");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper는 필수입니다.");
    this.metrics = Objects.requireNonNull(metrics, "metrics는 필수입니다.");
    this.monotonicTime = Objects.requireNonNull(monotonicTime, "monotonicTime은 필수입니다.");
  }

  public SnapshotRetentionCycleResult execute(SnapshotRetentionCycleCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    long started = monotonicTime.getAsLong();
    if (Thread.currentThread().isInterrupted()) {
      return complete(started, 0, 0, 0, 0, INTERRUPTED, command.dryRun());
    }

    int batchCount = 0;
    int attemptCount = 0;
    int candidateCount = 0;
    int purgedCount = 0;
    while (batchCount < command.maxBatches()) {
      SnapshotRetentionResult batch = null;
      for (int attempt = 1; attempt <= command.retryAttempts(); attempt++) {
        if (Thread.currentThread().isInterrupted()) {
          return complete(
              started,
              batchCount,
              attemptCount,
              candidateCount,
              purgedCount,
              INTERRUPTED,
              command.dryRun());
        }
        attemptCount++;
        try {
          batch = service.execute(command.dryRun(), command.batchSize());
          break;
        } catch (SnapshotRetentionException exception) {
          if (exception.code() != SnapshotRetentionException.Code.SNAPSHOT_RETENTION_UNAVAILABLE) {
            throw exception;
          }
          if (attempt == command.retryAttempts()) {
            return complete(
                started,
                batchCount,
                attemptCount,
                candidateCount,
                purgedCount,
                FAILED,
                command.dryRun());
          }
          try {
            sleeper.sleep(backoff(command.initialBackoff(), attempt));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return complete(
                started,
                batchCount,
                attemptCount,
                candidateCount,
                purgedCount,
                INTERRUPTED,
                command.dryRun());
          }
        }
      }

      batchCount++;
      candidateCount += batch.candidateCount();
      purgedCount += batch.purgedCount();
      if (command.dryRun() || batch.purgedCount() < command.batchSize()) {
        return complete(
            started,
            batchCount,
            attemptCount,
            candidateCount,
            purgedCount,
            SUCCESS,
            command.dryRun());
      }
    }

    return complete(
        started, batchCount, attemptCount, candidateCount, purgedCount, BOUNDED, command.dryRun());
  }

  private SnapshotRetentionCycleResult complete(
      long started,
      int batchCount,
      int attemptCount,
      int candidateCount,
      int purgedCount,
      SnapshotRetentionCycleOutcome outcome,
      boolean dryRun) {
    Duration elapsed = Duration.ofNanos(Math.max(0L, monotonicTime.getAsLong() - started));
    SnapshotRetentionCycleResult result =
        result(batchCount, attemptCount, candidateCount, purgedCount, elapsed, outcome, dryRun);
    metrics.record(result);
    return result;
  }

  private static Duration backoff(Duration initialBackoff, int failedAttempt) {
    return initialBackoff.multipliedBy(1L << (failedAttempt - 1));
  }

  private static SnapshotRetentionCycleResult result(
      int batchCount,
      int attemptCount,
      int candidateCount,
      int purgedCount,
      Duration duration,
      SnapshotRetentionCycleOutcome outcome,
      boolean dryRun) {
    return new SnapshotRetentionCycleResult(
        batchCount, attemptCount, candidateCount, purgedCount, duration, outcome, dryRun);
  }

  private static final SnapshotRetentionCycleOutcome SUCCESS =
      SnapshotRetentionCycleOutcome.SUCCESS;
  private static final SnapshotRetentionCycleOutcome BOUNDED =
      SnapshotRetentionCycleOutcome.BOUNDED;
  private static final SnapshotRetentionCycleOutcome FAILED = SnapshotRetentionCycleOutcome.FAILED;
  private static final SnapshotRetentionCycleOutcome INTERRUPTED =
      SnapshotRetentionCycleOutcome.INTERRUPTED;
}
