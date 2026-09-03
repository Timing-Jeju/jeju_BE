package com.timingjeju.api.application.trip;

import java.util.Objects;

public record TripMutationResult(
    TripAggregate trip, String scheduleEffect, boolean regenerationRequired) {
  public TripMutationResult {
    Objects.requireNonNull(trip);
    if (!java.util.Set.of("none", "maintained", "invalidated").contains(scheduleEffect)) {
      throw new IllegalArgumentException("지원하지 않는 scheduleEffect입니다.");
    }
  }
}
