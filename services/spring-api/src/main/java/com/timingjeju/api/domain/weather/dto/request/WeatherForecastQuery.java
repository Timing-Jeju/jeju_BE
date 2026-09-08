package com.timingjeju.api.domain.weather.dto.request;

import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

public record WeatherForecastQuery(
    String regionCode, UUID placeId, UUID tripItemId, OffsetDateTime dateTime) {
  private static final Pattern REGION = Pattern.compile("[a-z0-9][a-z0-9_-]{0,49}");
  private static final Pattern ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern TIME =
      Pattern.compile(
          "[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):00:00\\+09:00");

  public WeatherForecastQuery {
    int selectors =
        (regionCode == null ? 0 : 1) + (placeId == null ? 0 : 1) + (tripItemId == null ? 0 : 1);
    if (selectors != 1
        || (regionCode != null && !REGION.matcher(regionCode).matches())
        || dateTime == null
        || !ZoneOffset.ofHours(9).equals(dateTime.getOffset())
        || dateTime.getYear() < 1
        || dateTime.getYear() > 9999
        || dateTime.getMinute() != 0
        || dateTime.getSecond() != 0
        || dateTime.getNano() != 0) {
      throw invalid();
    }
  }

  public static WeatherForecastQuery parse(
      String regionCode, String placeId, String tripItemId, String dateTime) {
    if (dateTime == null || !TIME.matcher(dateTime).matches()) {
      throw invalid();
    }
    try {
      return new WeatherForecastQuery(
          regionCode, uuid(placeId), uuid(tripItemId), OffsetDateTime.parse(dateTime));
    } catch (DateTimeParseException failure) {
      throw invalid();
    }
  }

  private static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    if (!ID.matcher(value).matches()) {
      throw invalid();
    }
    return UUID.fromString(value);
  }

  private static WeatherForecastException invalid() {
    return new WeatherForecastException("INVALID_WEATHER_SELECTOR");
  }
}
