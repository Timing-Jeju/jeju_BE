package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public class CommandLocationCleanupOrchestrator {
  private static final Duration MINIMUM_ATTEMPT_TIMEOUT = Duration.ofMillis(1);

  private final CommandLocationCleanupService service;
  private final CommandLocationCleanupSleeper sleeper;
  private final CommandLocationCleanupCycleMetrics metrics;
  private final LongSupplier monotonicTime;

  public CommandLocationCleanupOrchestrator(
      CommandLocationCleanupService service,
      CommandLocationCleanupSleeper sleeper,
      CommandLocationCleanupCycleMetrics metrics,
      LongSupplier monotonicTime) {
    this.service = Objects.requireNonNull(service, "service는 필수입니다.");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper는 필수입니다.");
    this.metrics = Objects.requireNonNull(metrics, "metrics는 필수입니다.");
    this.monotonicTime = Objects.requireNonNull(monotonicTime, "monotonicTime은 필수입니다.");
  }

  public CommandLocationCleanupCycleResult execute(CommandLocationCleanupCycleCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    long started = monotonicTime.getAsLong();
    long deadline = started + command.maxDuration().toNanos();
    int batches = 0;
    int attempts = 0;
    int redacted = 0;
    while (batches < command.maxBatches()) {
      if (Thread.currentThread().isInterrupted()) {
        return complete(
            started,
            batches,
            attempts,
            redacted,
            CommandLocationCleanupCycleOutcome.INTERRUPTED,
            null);
      }
      if (!canStartAttempt(deadline, command.maxDuration())) {
        return complete(
            started,
            batches,
            attempts,
            redacted,
            CommandLocationCleanupCycleOutcome.BOUNDED_DURATION,
            null);
      }

      CommandLocationCleanupResult batch = null;
      for (int retry = 1; retry <= command.retryAttempts(); retry++) {
        Duration remaining = remaining(deadline, command.maxDuration());
        if (remaining.compareTo(MINIMUM_ATTEMPT_TIMEOUT) < 0) {
          return complete(
              started,
              batches,
              attempts,
              redacted,
              CommandLocationCleanupCycleOutcome.BOUNDED_DURATION,
              null);
        }
        attempts++;
        try {
          batch = service.execute(command.batchSize(), remaining);
          break;
        } catch (CommandLocationCleanupException failure) {
          remaining = remaining(deadline, command.maxDuration());
          if (remaining.compareTo(MINIMUM_ATTEMPT_TIMEOUT) < 0) {
            return complete(
                started,
                batches,
                attempts,
                redacted,
                CommandLocationCleanupCycleOutcome.BOUNDED_DURATION,
                null);
          }
          if (retry == command.retryAttempts()) {
            return complete(
                started,
                batches,
                attempts,
                redacted,
                CommandLocationCleanupCycleOutcome.FAILED,
                failure.code());
          }
          try {
            Duration requestedBackoff = command.initialBackoff().multipliedBy(1L << (retry - 1));
            sleeper.sleep(shorter(requestedBackoff, remaining));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return complete(
                started,
                batches,
                attempts,
                redacted,
                CommandLocationCleanupCycleOutcome.INTERRUPTED,
                null);
          }
          if (!canStartAttempt(deadline, command.maxDuration())) {
            return complete(
                started,
                batches,
                attempts,
                redacted,
                CommandLocationCleanupCycleOutcome.BOUNDED_DURATION,
                null);
          }
        }
      }

      batches++;
      redacted += batch.redactedCount();
      if (batch.redactedCount() < command.batchSize()) {
        return complete(
            started, batches, attempts, redacted, CommandLocationCleanupCycleOutcome.SUCCESS, null);
      }
    }
    return complete(
        started,
        batches,
        attempts,
        redacted,
        CommandLocationCleanupCycleOutcome.BOUNDED_BATCHES,
        null);
  }

  private Duration elapsed(long started) {
    return Duration.ofNanos(Math.max(0L, monotonicTime.getAsLong() - started));
  }

  private boolean canStartAttempt(long deadline, Duration maxDuration) {
    return remaining(deadline, maxDuration).compareTo(MINIMUM_ATTEMPT_TIMEOUT) >= 0;
  }

  private Duration remaining(long deadline, Duration maxDuration) {
    long remainingNanos = deadline - monotonicTime.getAsLong();
    if (remainingNanos <= 0L) {
      return Duration.ZERO;
    }
    return Duration.ofNanos(Math.min(remainingNanos, maxDuration.toNanos()));
  }

  private static Duration shorter(Duration first, Duration second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private CommandLocationCleanupCycleResult complete(
      long started,
      int batches,
      int attempts,
      int redacted,
      CommandLocationCleanupCycleOutcome outcome,
      CommandLocationCleanupException.Code failureCode) {
    var result =
        new CommandLocationCleanupCycleResult(
            batches, attempts, redacted, elapsed(started), outcome, failureCode);
    metrics.record(result);
    return result;
  }
}
