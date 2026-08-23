package com.timingjeju.api.domain.weather.dto.request;

import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public record WeatherForecastQuery(double lat, double lng, OffsetDateTime dateTime) {

  private static final ZoneOffset KOREA_OFFSET = ZoneOffset.ofHours(9);
  private static final Pattern ASCII_DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
  private static final Pattern KOREA_WHOLE_HOUR =
      Pattern.compile(
          "[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):00:00\\+09:00");

  public static WeatherForecastQuery parse(String lat, String lng, String dateTime) {
    try {
      if (lat == null || lng == null || dateTime == null) {
        throw invalidQuery();
      }
      if (!ASCII_DECIMAL.matcher(lat).matches()
          || !ASCII_DECIMAL.matcher(lng).matches()
          || !KOREA_WHOLE_HOUR.matcher(dateTime).matches()) {
        throw invalidQuery();
      }
      return of(Double.parseDouble(lat), Double.parseDouble(lng), OffsetDateTime.parse(dateTime));
    } catch (NumberFormatException | DateTimeParseException failure) {
      throw invalidQuery();
    }
  }

  public static WeatherForecastQuery of(double lat, double lng, OffsetDateTime dateTime) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lng)
        || lat <= -90.0
        || lat >= 90.0
        || lng < -180.0
        || lng > 180.0
        || dateTime == null
        || !KOREA_OFFSET.equals(dateTime.getOffset())
        || dateTime.getSecond() != 0
        || dateTime.getNano() != 0) {
      throw invalidQuery();
    }
    return new WeatherForecastQuery(lat, lng, dateTime);
  }

  private static WeatherForecastException invalidQuery() {
    return new WeatherForecastException("INVALID_WEATHER_FORECAST_QUERY");
  }
}
