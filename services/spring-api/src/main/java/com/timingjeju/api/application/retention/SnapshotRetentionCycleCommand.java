package com.timingjeju.api.application.retention;

import java.time.Duration;
import java.util.Objects;

public record SnapshotRetentionCycleCommand(
    boolean dryRun, int batchSize, int maxBatches, int retryAttempts, Duration initialBackoff) {

  public SnapshotRetentionCycleCommand {
    Objects.requireNonNull(initialBackoff, "initialBackoff은 필수입니다.");
    if (batchSize < 1
        || batchSize > 500
        || maxBatches < 1
        || maxBatches > 10
        || retryAttempts < 1
        || retryAttempts > 3
        || initialBackoff.compareTo(Duration.ofMillis(1)) < 0
        || initialBackoff.compareTo(Duration.ofSeconds(1)) > 0
        || initialBackoff.multipliedBy(1L << (retryAttempts - 1)).compareTo(Duration.ofSeconds(1))
            > 0) {
      throw new IllegalArgumentException("snapshot retention cycle 설정이 올바르지 않습니다.");
    }
  }
}
