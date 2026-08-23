package com.timingjeju.api.domain.weather.model;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record WeatherForecastSnapshot(
    ForecastType forecastType,
    ForecastBaseTime base,
    Instant forecastedAt,
    Instant validAt,
    BigDecimal temperatureC,
    Integer precipitationProbabilityPercent,
    BigDecimal precipitationAmountMm,
    String precipitationType,
    String skyCode,
    Integer humidityPercent,
    BigDecimal windSpeedMps,
    Instant observedAt,
    Instant expiresAt) {

  public WeatherForecastSnapshot {
    Objects.requireNonNull(forecastType, "forecastType은 필수입니다.");
    Objects.requireNonNull(base, "base는 필수입니다.");
    Objects.requireNonNull(forecastedAt, "forecastedAt은 필수입니다.");
    Objects.requireNonNull(validAt, "validAt은 필수입니다.");
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
  }
}
