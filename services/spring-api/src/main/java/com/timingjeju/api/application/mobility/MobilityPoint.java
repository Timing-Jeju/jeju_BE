package com.timingjeju.api.application.mobility;

public record MobilityPoint(double latitude, double longitude) {
  public MobilityPoint {
    if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
      throw new IllegalArgumentException("latitude가 올바르지 않습니다.");
    }
    if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
      throw new IllegalArgumentException("longitude가 올바르지 않습니다.");
    }
  }
}
