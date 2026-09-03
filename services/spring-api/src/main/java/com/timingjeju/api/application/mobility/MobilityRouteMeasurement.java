package com.timingjeju.api.application.mobility;

import java.time.Duration;
import java.util.Objects;

public record MobilityRouteMeasurement(
    MobilityMode mode,
    int distanceMeters,
    MobilityDurationComponents duration,
    Integer fareKrw,
    Duration validFor) {
  private static final int MAX_DISTANCE_METERS = 1_000_000;
  private static final int MAX_FARE_KRW = 10_000_000;
  private static final Duration MAX_WALK_TTL = Duration.ofHours(23).plusMinutes(50);
  private static final Duration MAX_DRIVING_TTL = Duration.ofMinutes(5);
  private static final Duration MAX_TRANSIT_TTL = Duration.ofHours(24);

  public MobilityRouteMeasurement {
    Objects.requireNonNull(mode, "mode는 필수입니다.");
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    Objects.requireNonNull(validFor, "validFor는 필수입니다.");
    if (distanceMeters < 0 || distanceMeters > MAX_DISTANCE_METERS) {
      throw new IllegalArgumentException("distanceMeters가 허용 범위를 벗어났습니다.");
    }
    if (fareKrw != null && (fareKrw < 0 || fareKrw > MAX_FARE_KRW)) {
      throw new IllegalArgumentException("fareKrw가 허용 범위를 벗어났습니다.");
    }
    if (validFor.isZero() || validFor.isNegative() || validFor.compareTo(maxTtl(mode)) > 0) {
      throw new IllegalArgumentException("mode별 TTL이 허용 범위를 벗어났습니다.");
    }
  }

  public int durationMinutes() {
    return duration.totalMinutes();
  }

  private static Duration maxTtl(MobilityMode mode) {
    return switch (mode) {
      case WALK -> MAX_WALK_TTL;
      case RENTAL_CAR, TAXI -> MAX_DRIVING_TTL;
      case PUBLIC_TRANSIT -> MAX_TRANSIT_TTL;
    };
  }
}
