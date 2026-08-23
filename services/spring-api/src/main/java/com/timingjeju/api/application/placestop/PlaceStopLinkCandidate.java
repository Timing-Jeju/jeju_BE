package com.timingjeju.api.application.placestop;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlaceStopLinkCandidate(
    UUID stopId, int distanceMeters, int walkMinutes, Instant expiresAt, boolean fresh) {

  public PlaceStopLinkCandidate {
    stopId = Objects.requireNonNull(stopId, "stopId는 필수입니다.");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    if (distanceMeters < 0 || walkMinutes < 0) {
      throw new IllegalArgumentException("거리와 도보 시간은 음수일 수 없습니다.");
    }
  }
}
