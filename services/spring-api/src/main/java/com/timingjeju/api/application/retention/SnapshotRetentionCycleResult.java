package com.timingjeju.api.application.retention;

import java.time.Duration;
import java.util.Objects;

public record SnapshotRetentionCycleResult(
    int batchCount,
    int attemptCount,
    int candidateCount,
    int purgedCount,
    Duration duration,
    SnapshotRetentionCycleOutcome outcome,
    boolean dryRun) {

  public SnapshotRetentionCycleResult {
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    if (batchCount < 0
        || attemptCount < 0
        || candidateCount < 0
        || purgedCount < 0
        || purgedCount > candidateCount
        || duration.isNegative()
        || (dryRun && purgedCount != 0)) {
      throw new IllegalArgumentException("snapshot retention cycle 결과가 올바르지 않습니다.");
    }
  }
}
