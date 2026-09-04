package com.timingjeju.api.application.commandinput.cleanup;

import java.time.Duration;
import java.util.Objects;

public record CommandLocationCleanupResult(
    int redactedCount, Duration duration, CommandLocationCleanupOutcome outcome) {
  public CommandLocationCleanupResult {
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(outcome, "outcome은 필수입니다.");
    if (redactedCount < 0 || redactedCount > 500 || duration.isNegative()) {
      throw new IllegalArgumentException("command location cleanup 결과가 올바르지 않습니다.");
    }
  }
}
