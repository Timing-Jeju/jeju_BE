package com.timingjeju.api.application.mobility;

import java.time.Instant;
import java.util.Objects;

public record MobilityRouteFact(
    String requestHash,
    String sourceId,
    MobilityMode mode,
    int distanceMeters,
    MobilityDurationComponents duration,
    Integer fareKrw,
    Instant observedAt,
    Instant expiresAt,
    boolean stale,
    boolean estimated,
    MobilityRouteReason reason) {
  public MobilityRouteFact {
    if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestHash가 올바르지 않습니다.");
    }
    sourceId = MobilityRouteRequestHasher.requireSourceId(sourceId);
    Objects.requireNonNull(mode, "mode는 필수입니다.");
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    Objects.requireNonNull(reason, "reason은 필수입니다.");
    if (expiresAt.isBefore(observedAt) || stale) {
      throw new IllegalArgumentException("route fact의 freshness가 올바르지 않습니다.");
    }
    new MobilityRouteMeasurement(
        mode, distanceMeters, duration, fareKrw, java.time.Duration.between(observedAt, expiresAt));
    if (estimated != (reason == MobilityRouteReason.ESTIMATED_WALK_TIME)) {
      throw new IllegalArgumentException("route fact의 추정 reason 조합이 올바르지 않습니다.");
    }
  }

  public int durationMinutes() {
    return duration.totalMinutes();
  }
}
