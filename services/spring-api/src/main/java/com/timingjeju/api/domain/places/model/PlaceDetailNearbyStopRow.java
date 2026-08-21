package com.timingjeju.api.domain.places.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlaceDetailNearbyStopRow(
    UUID stopId,
    String stopName,
    long distanceMeters,
    Integer walkMinutes,
    String linkMethod,
    String provider,
    Instant observedAt,
    Instant expiresAt,
    boolean stale) {

  public PlaceDetailNearbyStopRow {
    stopId = Objects.requireNonNull(stopId, "stopId는 필수입니다.");
    stopName = requireText(stopName, "stopName");
    linkMethod = requireText(linkMethod, "linkMethod");
    provider = requireText(provider, "provider");
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    if (distanceMeters < 0 || (walkMinutes != null && walkMinutes < 0)) {
      throw new IllegalArgumentException("정류장 거리와 도보 시간은 음수일 수 없습니다.");
    }
    if (expiresAt.isBefore(observedAt)) {
      throw new IllegalArgumentException("정류장 만료 시각은 관측 시각보다 빠를 수 없습니다.");
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "은 필수입니다.");
    }
    return value;
  }
}
