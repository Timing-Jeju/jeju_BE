package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;
import java.util.Objects;

public record CommandLocationCleanupCycleResult(
    int batchCount,
    int attemptCount,
    int redactedCount,
    Duration duration,
    CommandLocationCleanupCycleOutcome outcome,
    CommandLocationCleanupException.Code failureCode) {
  public CommandLocationCleanupCycleResult {
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    if (batchCount < 0 || attemptCount < 0 || redactedCount < 0 || duration.isNegative()) {
      throw new IllegalArgumentException("command location cleanup cycle 결과가 올바르지 않습니다.");
    }
    if ((outcome == CommandLocationCleanupCycleOutcome.FAILED) != (failureCode != null)) {
      throw new IllegalArgumentException("failureCode가 outcome과 일치하지 않습니다.");
    }
  }
}
