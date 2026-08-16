package com.timingjeju.api.application.kma;

import java.math.BigDecimal;
import java.time.Instant;

public record KmaWeatherForecast(
    Instant forecastedAt,
    Instant validAt,
    String forecastType,
    String forecastVersion,
    String skyCode,
    String precipitationType,
    Integer precipitationProbabilityPercent,
    BigDecimal precipitationAmountMm,
    BigDecimal temperatureC,
    BigDecimal minTemperatureC,
    BigDecimal maxTemperatureC,
    int humidityPercent,
    BigDecimal windSpeedMps,
    Integer precipitationIntensityCode,
    Integer windStrengthCode) {

  public KmaWeatherForecast(
      Instant forecastedAt,
      Instant validAt,
      String forecastType,
      String forecastVersion,
      String skyCode,
      String precipitationType,
      Integer precipitationProbabilityPercent,
      BigDecimal precipitationAmountMm,
      BigDecimal temperatureC,
      BigDecimal minTemperatureC,
      BigDecimal maxTemperatureC,
      int humidityPercent,
      BigDecimal windSpeedMps) {
    this(
        forecastedAt,
        validAt,
        forecastType,
        forecastVersion,
        skyCode,
        precipitationType,
        precipitationProbabilityPercent,
        precipitationAmountMm,
        temperatureC,
        minTemperatureC,
        maxTemperatureC,
        humidityPercent,
        windSpeedMps,
        null,
        null);
  }

  public KmaWeatherForecast(
      Instant forecastedAt,
      Instant validAt,
      String forecastType,
      String skyCode,
      String precipitationType,
      BigDecimal precipitationAmountMm,
      BigDecimal temperatureC,
      int humidityPercent,
      BigDecimal windSpeedMps) {
    this(
        forecastedAt,
        validAt,
        forecastType,
        null,
        skyCode,
        precipitationType,
        null,
        precipitationAmountMm,
        temperatureC,
        null,
        null,
        humidityPercent,
        windSpeedMps,
        null,
        null);
  }
}
