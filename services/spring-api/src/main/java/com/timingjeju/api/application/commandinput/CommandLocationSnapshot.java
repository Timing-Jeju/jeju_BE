package com.timingjeju.api.application.commandinput;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CommandLocationSnapshot(
    String canonicalCoarseLocation,
    Integer precisionMeters,
    String policyVersion,
    Instant observedAt,
    Instant expiresAtValue) {

  public CommandLocationSnapshot {
    if (canonicalCoarseLocation == null || canonicalCoarseLocation.isBlank()) {
      throw new IllegalArgumentException("canonical coarse location은 필수입니다.");
    }
    Objects.requireNonNull(policyVersion, "location policy version은 필수입니다.");
    Objects.requireNonNull(observedAt, "location observedAt은 필수입니다.");
  }

  public Optional<Instant> expiresAt() {
    return Optional.ofNullable(expiresAtValue);
  }

  Instant nullableExpiresAt() {
    return expiresAtValue;
  }
}
