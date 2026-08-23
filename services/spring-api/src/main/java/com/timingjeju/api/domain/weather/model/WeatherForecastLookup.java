package com.timingjeju.api.domain.weather.model;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import java.time.Instant;
import java.util.Objects;

public record WeatherForecastLookup(
    KmaGridPoint gridPoint, ForecastType forecastType, ForecastBaseTime base, Instant validAt) {

  public WeatherForecastLookup {
    Objects.requireNonNull(gridPoint, "gridPoint는 필수입니다.");
    Objects.requireNonNull(forecastType, "forecastType은 필수입니다.");
    Objects.requireNonNull(base, "base는 필수입니다.");
    Objects.requireNonNull(validAt, "validAt은 필수입니다.");
  }
}
