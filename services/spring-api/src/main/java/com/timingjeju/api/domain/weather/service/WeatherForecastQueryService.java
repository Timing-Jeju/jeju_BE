package com.timingjeju.api.domain.weather.service;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastBaseTimeResolver;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridConverter;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.dto.request.WeatherForecastQuery;
import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.domain.weather.dto.response.WeatherGridResponse;
import com.timingjeju.api.domain.weather.exception.WeatherForecastDataUnavailableException;
import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.model.WeatherForecastSnapshot;
import com.timingjeju.api.domain.weather.repository.WeatherForecastRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

public final class WeatherForecastQueryService {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private static final Duration ULTRA_SHORT_HORIZON = Duration.ofHours(6);
  private static final Duration MAX_HORIZON = Duration.ofDays(10);
  private static final Map<String, String> PRECIPITATION_TYPES =
      Map.of(
          "0", "none",
          "1", "rain",
          "2", "rain_snow",
          "3", "snow",
          "4", "shower",
          "5", "raindrop",
          "6", "raindrop_snowflake",
          "7", "snowflake");
  private static final Map<String, String> SKY_CODES =
      Map.of("1", "clear", "3", "mostly_cloudy", "4", "cloudy");

  private final WeatherForecastRepository repository;
  private final KmaGridConverter grids;
  private final ForecastBaseTimeResolver bases;
  private final Clock clock;

  public WeatherForecastQueryService(
      WeatherForecastRepository repository,
      KmaGridConverter grids,
      ForecastBaseTimeResolver bases,
      Clock clock) {
    this.repository = repository;
    this.grids = grids;
    this.bases = bases;
    this.clock = clock;
  }

  public WeatherForecastResponse forecast(WeatherForecastQuery query) {
    Instant evaluatedAt = clock.instant();
    ForecastType type = forecastType(query, evaluatedAt);
    SupportedWeatherGrid grid = supportedGrid(query);
    ForecastBaseTime current = bases.resolve(type, evaluatedAt);
    try {
      Optional<WeatherForecastResponse> latest =
          repository
              .find(lookup(grid.gridPoint(), type, current, query))
              .flatMap(snapshot -> response(grid, snapshot, evaluatedAt, false));
      if (latest.isPresent()) {
        return latest.get();
      }
      ForecastBaseTime previous = bases.previous(type, current);
      return repository
          .find(lookup(grid.gridPoint(), type, previous, query))
          .flatMap(snapshot -> response(grid, snapshot, evaluatedAt, true))
          .orElseThrow(() -> unavailable());
    } catch (WeatherForecastDataUnavailableException failure) {
      throw unavailable();
    }
  }

  private SupportedWeatherGrid supportedGrid(WeatherForecastQuery query) {
    KmaGridPoint point;
    try {
      point = grids.convert(query.lat(), query.lng());
    } catch (IllegalArgumentException failure) {
      throw new WeatherForecastException("WEATHER_LOCATION_NOT_SUPPORTED");
    }
    try {
      return repository
          .findSupportedGrid(point)
          .orElseThrow(() -> new WeatherForecastException("WEATHER_LOCATION_NOT_SUPPORTED"));
    } catch (WeatherForecastDataUnavailableException failure) {
      throw unavailable();
    }
  }

  private static ForecastType forecastType(WeatherForecastQuery query, Instant evaluatedAt) {
    ZonedDateTime acceptanceHour = evaluatedAt.atZone(KOREA).truncatedTo(ChronoUnit.HOURS);
    ZonedDateTime requested = query.dateTime().atZoneSameInstant(KOREA);
    if (requested.isBefore(acceptanceHour) || requested.isAfter(acceptanceHour.plus(MAX_HORIZON))) {
      throw new WeatherForecastException("WEATHER_FORECAST_HORIZON_NOT_SUPPORTED");
    }
    Duration horizon = Duration.between(acceptanceHour, requested);
    return horizon.compareTo(ULTRA_SHORT_HORIZON) <= 0
        ? ForecastType.ULTRA_SHORT
        : ForecastType.VILLAGE;
  }

  private static WeatherForecastLookup lookup(
      KmaGridPoint grid, ForecastType type, ForecastBaseTime base, WeatherForecastQuery query) {
    return new WeatherForecastLookup(grid, type, base, query.dateTime().toInstant());
  }

  private static Optional<WeatherForecastResponse> response(
      SupportedWeatherGrid grid,
      WeatherForecastSnapshot snapshot,
      Instant evaluatedAt,
      boolean fallback) {
    String precipitation = nullableCode(PRECIPITATION_TYPES, snapshot.precipitationType());
    String sky = nullableCode(SKY_CODES, snapshot.skyCode());
    if ((snapshot.precipitationType() != null && precipitation == null)
        || (snapshot.skyCode() != null && sky == null)) {
      return Optional.empty();
    }
    return Optional.of(
        new WeatherForecastResponse(
            "1.0.0",
            new WeatherGridResponse(
                grid.gridPoint().nx(), grid.gridPoint().ny(), grid.regionName()),
            "KMA",
            "VilageFcstInfoService_2.0",
            snapshot.forecastType() == ForecastType.ULTRA_SHORT ? "ultra_short" : "village",
            snapshot.base().baseDate(),
            snapshot.base().baseTime(),
            atKorea(snapshot.forecastedAt()),
            atKorea(snapshot.validAt()),
            snapshot.temperatureC(),
            snapshot.precipitationProbabilityPercent(),
            snapshot.precipitationAmountMm(),
            precipitation,
            sky,
            snapshot.humidityPercent(),
            snapshot.windSpeedMps(),
            atKorea(snapshot.observedAt()),
            atKorea(snapshot.expiresAt()),
            fallback || !evaluatedAt.isBefore(snapshot.expiresAt()),
            fallback));
  }

  private static String nullableCode(Map<String, String> codes, String value) {
    return value == null ? null : codes.get(value);
  }

  private static OffsetDateTime atKorea(Instant instant) {
    return instant.atZone(KOREA).toOffsetDateTime();
  }

  private static WeatherForecastException unavailable() {
    return new WeatherForecastException("WEATHER_FORECAST_UNAVAILABLE");
  }
}
