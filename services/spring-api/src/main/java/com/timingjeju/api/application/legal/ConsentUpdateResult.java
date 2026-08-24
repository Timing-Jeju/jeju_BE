package com.timingjeju.api.application.legal;

import java.time.Instant;
import java.util.Objects;

public record ConsentUpdateResult(boolean requiredConsentsSatisfied, Instant updatedAt) {

  public ConsentUpdateResult {
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}
