package com.timingjeju.api.application.asyncrun;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public record RunExecutionPolicy(
    Duration leaseDuration,
    Duration heartbeatInterval,
    int claimBatchSize,
    int maxAttempts,
    Duration backoffBase,
    Duration backoffCap,
    Duration executionDeadline) {

  public RunExecutionPolicy {
    requirePositive(leaseDuration, "leaseDuration");
    requirePositive(heartbeatInterval, "heartbeatInterval");
    requirePositive(backoffBase, "backoffBase");
    requirePositive(backoffCap, "backoffCap");
    requirePositive(executionDeadline, "executionDeadline");
    if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
      throw new IllegalArgumentException("heartbeatInterval은 leaseDuration보다 짧아야 합니다.");
    }
    if (claimBatchSize <= 0 || maxAttempts <= 0) {
      throw new IllegalArgumentException("claimBatchSize와 maxAttempts는 양수여야 합니다.");
    }
    if (backoffBase.compareTo(backoffCap) > 0) {
      throw new IllegalArgumentException("backoffBase는 backoffCap보다 클 수 없습니다.");
    }
  }

  public static RunExecutionPolicy defaults() {
    return new RunExecutionPolicy(
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        50,
        5,
        Duration.ofSeconds(1),
        Duration.ofSeconds(60),
        Duration.ofSeconds(60));
  }

  public Duration retryDelay(int attempt, DoubleSupplier jitter) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt는 양수여야 합니다.");
    }
    double sample = Objects.requireNonNull(jitter, "jitter는 필수입니다.").getAsDouble();
    if (sample < 0.0d || sample >= 1.0d) {
      throw new IllegalArgumentException("full jitter 표본은 0 이상 1 미만이어야 합니다.");
    }
    long multiplier = 1L << Math.min(attempt - 1, 62);
    long exponentialNanos;
    try {
      exponentialNanos = Math.multiplyExact(backoffBase.toNanos(), multiplier);
    } catch (ArithmeticException ignored) {
      exponentialNanos = Long.MAX_VALUE;
    }
    long upperBoundNanos = Math.min(backoffCap.toNanos(), exponentialNanos);
    return Duration.ofNanos((long) (upperBoundNanos * sample));
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + "은 필수입니다.");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + "은 양수여야 합니다.");
    }
  }
}
