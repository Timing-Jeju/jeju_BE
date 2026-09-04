package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record CommandLocationCleanupCommand(
    Instant evaluatedAt, int batchSize, Duration attemptTimeout) {
  public CommandLocationCleanupCommand(Instant evaluatedAt, int batchSize) {
    this(evaluatedAt, batchSize, Duration.ofSeconds(30));
  }

  public CommandLocationCleanupCommand {
    Objects.requireNonNull(evaluatedAt, "evaluatedAt은 필수입니다.");
    Objects.requireNonNull(attemptTimeout, "attemptTimeout은 필수입니다.");
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("batchSize는 1 이상 500 이하여야 합니다.");
    }
    if (attemptTimeout.compareTo(Duration.ofMillis(1)) < 0
        || attemptTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("attemptTimeout은 1ms 이상 1분 이하여야 합니다.");
    }
  }
}
