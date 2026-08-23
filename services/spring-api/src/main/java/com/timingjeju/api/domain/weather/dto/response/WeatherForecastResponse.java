package com.timingjeju.api.domain.weather.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@JsonPropertyOrder({
  "contractVersion",
  "grid",
  "provider",
  "providerApiVersion",
  "forecastType",
  "baseDate",
  "baseTime",
  "forecastedAt",
  "validAt",
  "temperatureC",
  "precipitationProbabilityPercent",
  "precipitationAmountMm",
  "precipitationType",
  "skyCode",
  "humidityPercent",
  "windSpeedMps",
  "observedAt",
  "expiresAt",
  "stale",
  "fallbackUsed"
})
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record WeatherForecastResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "1.0.0")
        String contractVersion,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WeatherGridResponse grid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "KMA") String provider,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = "VilageFcstInfoService_2.0")
        String providerApiVersion,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"ultra_short", "village"})
        String forecastType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate baseDate,
    @JsonFormat(pattern = "HH:mm")
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^(?:[01]\\d|2[0-3]):(?:00|30)$")
        LocalTime baseTime,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime forecastedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime validAt,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minimum = "-100",
            maximum = "100")
        BigDecimal temperatureC,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minimum = "0",
            maximum = "100")
        Integer precipitationProbabilityPercent,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0")
        BigDecimal precipitationAmountMm,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {
              "none",
              "rain",
              "rain_snow",
              "snow",
              "shower",
              "raindrop",
              "raindrop_snowflake",
              "snowflake"
            })
        String precipitationType,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"clear", "mostly_cloudy", "cloudy"})
        String skyCode,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            minimum = "0",
            maximum = "100")
        Integer humidityPercent,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0")
        BigDecimal windSpeedMps,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime observedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime expiresAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean fallbackUsed) {}
