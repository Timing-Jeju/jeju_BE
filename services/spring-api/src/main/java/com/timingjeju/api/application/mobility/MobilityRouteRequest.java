package com.timingjeju.api.application.mobility;

import java.time.Instant;
import java.util.Objects;

public record MobilityRouteRequest(
    MobilityPoint origin, MobilityPoint destination, MobilityMode mode, Instant departureAt) {
  public MobilityRouteRequest {
    Objects.requireNonNull(origin, "origin은 필수입니다.");
    Objects.requireNonNull(destination, "destination은 필수입니다.");
    Objects.requireNonNull(mode, "mode는 필수입니다.");
    Objects.requireNonNull(departureAt, "departureAt은 필수입니다.");
  }
}
