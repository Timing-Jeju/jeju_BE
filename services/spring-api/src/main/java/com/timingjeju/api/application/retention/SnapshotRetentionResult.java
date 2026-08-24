package com.timingjeju.api.application.retention;

import java.time.Duration;
import java.util.Objects;

public record SnapshotRetentionResult(
    int candidateCount,
    int purgedCount,
    Duration duration,
    SnapshotRetentionOutcome outcome,
    boolean dryRun) {

  public SnapshotRetentionResult {
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    if (candidateCount < 0
        || purgedCount < 0
        || purgedCount > candidateCount
        || duration.isNegative()
        || (dryRun && purgedCount != 0)) {
      throw new IllegalArgumentException("snapshot retention 결과가 올바르지 않습니다.");
    }
  }
}
