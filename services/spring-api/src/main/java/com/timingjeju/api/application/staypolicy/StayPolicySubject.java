package com.timingjeju.api.application.staypolicy;

import java.util.Objects;
import java.util.UUID;

public record StayPolicySubject(UUID placeId, String category) {
  public StayPolicySubject {
    Objects.requireNonNull(placeId);
    Objects.requireNonNull(category);
  }
}
