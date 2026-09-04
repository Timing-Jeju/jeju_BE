package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;
import java.util.Objects;

public record CommandLocationCleanupCycleCommand(
    int batchSize,
    int maxBatches,
    int retryAttempts,
    Duration initialBackoff,
    Duration maxDuration) {
  public CommandLocationCleanupCycleCommand {
    Objects.requireNonNull(initialBackoff, "initialBackoff은 필수입니다.");
    Objects.requireNonNull(maxDuration, "maxDuration은 필수입니다.");
    if (batchSize < 1
        || batchSize > 500
        || maxBatches < 1
        || maxBatches > 10
        || retryAttempts < 1
        || retryAttempts > 3
        || initialBackoff.isNegative()
        || initialBackoff.isZero()
        || initialBackoff.compareTo(Duration.ofSeconds(1)) > 0
        || maxDuration.compareTo(Duration.ofSeconds(1)) < 0
        || maxDuration.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("command location cleanup cycle 설정이 올바르지 않습니다.");
    }
  }
}
