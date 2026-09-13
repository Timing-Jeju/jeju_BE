package com.timingjeju.api.domain.accountdeletion.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public record DeletionWorkerPolicy(
    Duration leaseDuration,
    int claimBatchSize,
    int maxAttempts,
    Duration baseRetryDelay,
    Duration maxRetryDelay) {

  public DeletionWorkerPolicy {
    Objects.requireNonNull(leaseDuration, "leaseDuration은 필수입니다.");
    Objects.requireNonNull(baseRetryDelay, "baseRetryDelay는 필수입니다.");
    Objects.requireNonNull(maxRetryDelay, "maxRetryDelay는 필수입니다.");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration은 양수여야 합니다.");
    }
    if (claimBatchSize <= 0 || maxAttempts <= 0) {
      throw new IllegalArgumentException("claimBatchSize와 maxAttempts는 양수여야 합니다.");
    }
    if (baseRetryDelay.isNegative() || maxRetryDelay.compareTo(baseRetryDelay) < 0) {
      throw new IllegalArgumentException("retry delay 범위가 올바르지 않습니다.");
    }
  }

  public static DeletionWorkerPolicy defaults() {
    return new DeletionWorkerPolicy(
        Duration.ofSeconds(30), 50, 5, Duration.ofSeconds(1), Duration.ofSeconds(60));
  }

  public Duration retryDelay(int attempt, DoubleSupplier jitter) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt는 양수여야 합니다.");
    }
    double sample = Objects.requireNonNull(jitter, "jitter는 필수입니다.").getAsDouble();
    if (sample < 0.0d || sample > 1.0d) {
      throw new IllegalArgumentException("jitter는 0 이상 1 이하여야 합니다.");
    }
    long multiplier = 1L << Math.min(attempt - 1, 30);
    long uncappedMillis;
    try {
      uncappedMillis = Math.multiplyExact(baseRetryDelay.toMillis(), multiplier);
    } catch (ArithmeticException ignored) {
      uncappedMillis = Long.MAX_VALUE;
    }
    long cappedMillis = Math.min(uncappedMillis, maxRetryDelay.toMillis());
    return Duration.ofMillis((long) (cappedMillis * sample));
  }
}
