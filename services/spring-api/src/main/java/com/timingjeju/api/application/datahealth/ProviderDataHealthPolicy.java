package com.timingjeju.api.application.datahealth;

import java.time.Duration;
import java.util.Objects;

public record ProviderDataHealthPolicy(ProviderDataHealthKey key, Duration ttl, boolean enabled) {
  private static final Duration MAX_TTL = Duration.ofHours(24);

  public ProviderDataHealthPolicy {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(ttl, "ttl은 필수입니다.");
    if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("ttl은 0초 초과 24시간 이하여야 합니다.");
    }
  }
}
