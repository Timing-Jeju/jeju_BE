package com.timingjeju.api.application.kma;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record KmaWeatherObservation(
    Instant observedAt,
    LocalDate baseDate,
    LocalTime baseTime,
    BigDecimal temperatureC,
    BigDecimal precipitationMm,
    String precipitationType,
    int humidityPercent,
    BigDecimal windSpeedMps,
    int windDirectionDeg) {}
