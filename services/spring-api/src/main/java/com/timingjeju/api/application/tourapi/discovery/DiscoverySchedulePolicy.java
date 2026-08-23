package com.timingjeju.api.application.tourapi.discovery;

import java.time.Duration;
import java.util.Objects;

public record DiscoverySchedulePolicy(
    boolean enabled, int maxPagesPerRun, int maxProviderCallsPerDay, Duration minimumInterval) {

  public DiscoverySchedulePolicy {
    if (maxPagesPerRun < 1 || maxPagesPerRun > 100) {
      throw new IllegalArgumentException("maxPagesPerRun이 올바르지 않습니다.");
    }
    if (maxProviderCallsPerDay < maxPagesPerRun) {
      throw new IllegalArgumentException("일일 호출 한도가 실행 한도보다 작을 수 없습니다.");
    }
    minimumInterval = Objects.requireNonNull(minimumInterval, "minimumInterval은 필수입니다.");
    if (minimumInterval.isNegative() || minimumInterval.isZero()) {
      throw new IllegalArgumentException("minimumInterval은 양수여야 합니다.");
    }
  }

  public static DiscoverySchedulePolicy safeDefault() {
    return new DiscoverySchedulePolicy(false, 10, 100, Duration.ofHours(1));
  }

  public void requireAllowed(int requestedPages, int callsUsedToday) {
    if (requestedPages < 1
        || requestedPages > maxPagesPerRun
        || callsUsedToday < 0
        || callsUsedToday + requestedPages > maxProviderCallsPerDay) {
      throw DiscoveryImportException.quotaExceeded();
    }
  }
}
