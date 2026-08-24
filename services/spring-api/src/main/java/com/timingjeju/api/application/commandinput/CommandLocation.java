package com.timingjeju.api.application.commandinput;

import java.time.Instant;
import java.util.Objects;

public record CommandLocation(
    CoarseLocation coarseLocation,
    String policyVersion,
    Instant observedAt,
    Instant evaluatedAt,
    Instant terminalAt,
    Instant tripEndedAt) {

  public CommandLocation {
    Objects.requireNonNull(coarseLocation, "coarse location은 필수입니다.");
    requireText(policyVersion, "location policy version은 필수입니다.");
    Objects.requireNonNull(observedAt, "location observedAt은 필수입니다.");
    Objects.requireNonNull(evaluatedAt, "location evaluatedAt은 필수입니다.");
    if (observedAt.isAfter(evaluatedAt)) {
      throw new IllegalArgumentException("location observedAt은 evaluatedAt 이후일 수 없습니다.");
    }
  }

  private static void requireText(String value, String message) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException(message);
    }
  }
}
