package com.timingjeju.api.application.placestop;

import java.time.Duration;
import java.util.Objects;

public record PlaceStopLinkPolicy(
    int radiusMeters, int maxCandidates, Duration linkTtl, Duration stopFreshnessTtl) {

  public static final int MAX_RADIUS_METERS = 500;
  public static final int MAX_CANDIDATES = 100;

  public PlaceStopLinkPolicy {
    if (radiusMeters < 1 || radiusMeters > MAX_RADIUS_METERS) {
      throw new IllegalArgumentException("radiusMeters는 1 이상 500 이하여야 합니다.");
    }
    if (maxCandidates < 1 || maxCandidates > MAX_CANDIDATES) {
      throw new IllegalArgumentException("maxCandidates는 1 이상 100 이하여야 합니다.");
    }
    requirePositive(linkTtl, "linkTtl");
    requirePositive(stopFreshnessTtl, "stopFreshnessTtl");
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + "은 필수입니다.");
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + "은 양수여야 합니다.");
    }
  }
}
