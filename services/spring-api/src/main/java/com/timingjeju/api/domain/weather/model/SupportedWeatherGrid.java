package com.timingjeju.api.domain.weather.model;

import com.timingjeju.api.domain.weather.KmaGridPoint;
import java.util.Objects;

public record SupportedWeatherGrid(KmaGridPoint gridPoint, String regionName) {

  public SupportedWeatherGrid {
    Objects.requireNonNull(gridPoint, "gridPoint는 필수입니다.");
  }
}
