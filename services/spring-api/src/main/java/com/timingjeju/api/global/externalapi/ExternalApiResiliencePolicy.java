package com.timingjeju.api.global.externalapi;

import java.time.Duration;

record ExternalApiResiliencePolicy(
    Duration connectTimeout,
    Duration readTimeout,
    Duration totalTimeout,
    int maxAttempts,
    Duration retryBaseDelay,
    Duration retryDelayCap,
    Duration retryAfterCap,
    long maximumDecompressedBodyBytes,
    int circuitWindowSize,
    int circuitMinimumCalls,
    double circuitFailureRate,
    Duration circuitOpenDuration,
    int circuitHalfOpenCalls) {

  static ExternalApiResiliencePolicy defaults() {
    return new ExternalApiResiliencePolicy(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(8),
        3,
        Duration.ofMillis(200),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        2L * 1024L * 1024L,
        20,
        10,
        0.5,
        Duration.ofSeconds(30),
        3);
  }

  ExternalApiResiliencePolicy {
    requirePositive(connectTimeout, "connectTimeout");
    requirePositive(readTimeout, "readTimeout");
    requirePositive(totalTimeout, "totalTimeout");
    requirePositive(retryBaseDelay, "retryBaseDelay");
    requirePositive(retryDelayCap, "retryDelayCap");
    requirePositive(retryAfterCap, "retryAfterCap");
    requirePositive(circuitOpenDuration, "circuitOpenDuration");
    if (maxAttempts < 1
        || maximumDecompressedBodyBytes < 1
        || circuitWindowSize < 1
        || circuitMinimumCalls < 1
        || circuitMinimumCalls > circuitWindowSize
        || circuitFailureRate <= 0.0
        || circuitFailureRate > 1.0
        || circuitHalfOpenCalls < 1) {
      throw new IllegalArgumentException("외부 API 복원력 정책 값이 유효하지 않습니다.");
    }
  }

  Duration retryDelay(int retryNumber, ExternalApiJitter jitter) {
    if (retryNumber < 1) {
      throw new IllegalArgumentException("retryNumber는 1 이상이어야 합니다.");
    }
    long baseMillis = retryBaseDelay.toMillis();
    long capMillis = retryDelayCap.toMillis();
    int shift = Math.min(retryNumber - 1, 62);
    long exponential;
    try {
      exponential = Math.multiplyExact(baseMillis, 1L << shift);
    } catch (ArithmeticException ignored) {
      exponential = Long.MAX_VALUE;
    }
    long upperBound = Math.min(capMillis, exponential);
    return Duration.ofMillis(jitter.nextLong(upperBound));
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + "은 양수여야 합니다.");
    }
  }
}
