package com.timingjeju.api.domain.places.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.places.nearby-stops")
public record PlaceNearbyStopsProperties(@DefaultValue("500") int maxDistanceMeters) {

  public PlaceNearbyStopsProperties {
    if (maxDistanceMeters < 1 || maxDistanceMeters > 500) {
      throw new IllegalArgumentException("주변 정류장 거리 상한은 1m 이상 500m 이하여야 합니다.");
    }
  }
}
