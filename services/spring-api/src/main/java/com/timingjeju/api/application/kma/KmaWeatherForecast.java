package com.timingjeju.api.application.kma;

import java.math.BigDecimal;
import java.time.Instant;

public record KmaWeatherForecast(
    Instant forecastedAt,
    Instant validAt,
    String forecastType,
    String skyCode,
    String precipitationType,
    BigDecimal precipitationAmountMm,
    BigDecimal temperatureC,
    int humidityPercent,
    BigDecimal windSpeedMps) {}
