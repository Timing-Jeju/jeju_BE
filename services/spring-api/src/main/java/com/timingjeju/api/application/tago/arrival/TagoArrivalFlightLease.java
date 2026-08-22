package com.timingjeju.api.application.tago.arrival;

import java.util.Objects;
import java.util.UUID;

public record TagoArrivalFlightLease(String fingerprint, long generation, UUID ownerToken) {
  public TagoArrivalFlightLease {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("flight fingerprint가 올바르지 않습니다.");
    }
    if (generation < 1) throw new IllegalArgumentException("flight generation은 양수입니다.");
    Objects.requireNonNull(ownerToken, "flight ownerToken은 필수입니다.");
  }
}
