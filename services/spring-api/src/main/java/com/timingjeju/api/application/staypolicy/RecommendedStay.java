package com.timingjeju.api.application.staypolicy;

import java.time.Instant;

public record RecommendedStay(
    Integer minutes,
    RecommendedStaySource source,
    String policyVersion,
    Instant effectiveAt,
    Instant updatedAt) {

  private static final RecommendedStay UNAVAILABLE =
      new RecommendedStay(null, RecommendedStaySource.UNAVAILABLE, null, null, null);

  public static RecommendedStay unavailable() {
    return UNAVAILABLE;
  }
}
