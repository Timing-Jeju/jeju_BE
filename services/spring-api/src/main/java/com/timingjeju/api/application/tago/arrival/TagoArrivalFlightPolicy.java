package com.timingjeju.api.application.tago.arrival;

import java.time.Duration;
import java.util.Objects;

public record TagoArrivalFlightPolicy(
    Duration deadline,
    Duration backoff,
    Duration providerHardTimeout,
    Duration lease,
    Duration retain,
    Duration quarantine) {
  public TagoArrivalFlightPolicy {
    deadline = positive(deadline, "deadline");
    backoff = positive(backoff, "backoff");
    providerHardTimeout = positive(providerHardTimeout, "providerHardTimeout");
    lease = positive(lease, "lease");
    retain = positive(retain, "retain");
    quarantine = positive(quarantine, "quarantine");
    if (backoff.compareTo(deadline) > 0
        || lease.compareTo(providerHardTimeout) <= 0
        || retain.compareTo(providerHardTimeout) <= 0
        || quarantine.compareTo(providerHardTimeout) <= 0) {
      throw new IllegalArgumentException("flight 시간 정책이 올바르지 않습니다.");
    }
  }

  private static Duration positive(Duration value, String name) {
    Objects.requireNonNull(value, name + "은 필수입니다.");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + "은 양수여야 합니다.");
    }
    return value;
  }
}
