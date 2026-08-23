package com.timingjeju.api.application.datahealth;

import java.time.Instant;
import java.util.Objects;

public record ProviderDataHealthHistory(
    ProviderDataHealthKey key,
    Instant lastAttemptAt,
    ProviderDataHealthAttemptStatus latestStatus,
    Instant lastSuccessAt,
    Instant factsAsOf) {

  public ProviderDataHealthHistory {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(lastAttemptAt, "lastAttemptAt은 필수입니다.");
    Objects.requireNonNull(latestStatus, "latestStatus는 필수입니다.");
    if ((lastSuccessAt == null) != (factsAsOf == null)) {
      throw ProviderDataHealthException.unavailable();
    }
    if (lastSuccessAt != null
        && (lastSuccessAt.isAfter(lastAttemptAt) || factsAsOf.isAfter(lastSuccessAt))) {
      throw ProviderDataHealthException.unavailable();
    }
  }
}
