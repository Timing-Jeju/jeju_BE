package com.timingjeju.api.domain.weather.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class WeatherForecastQueryTest {

  @Test
  void acceptsOnlyCanonicalAsciiDecimalCoordinatesAndKoreaWholeHour() {
    WeatherForecastQuery query =
        WeatherForecastQuery.parse("33.458111", "126.941516", "2026-08-03T15:00:00+09:00");

    assertThat(query.lat()).isEqualTo(33.458111);
    assertThat(query.lng()).isEqualTo(126.941516);
    assertThat(query.dateTime()).isEqualTo(OffsetDateTime.parse("2026-08-03T15:00:00+09:00"));
  }

  @ParameterizedTest
  @MethodSource("invalidCoordinateLexemes")
  void rejectsNonCanonicalCoordinateLexemes(String value) {
    assertThatThrownBy(
            () -> WeatherForecastQuery.parse(value, "126.941516", "2026-08-03T15:00:00+09:00"))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("INVALID_WEATHER_FORECAST_QUERY");
    assertThatThrownBy(
            () -> WeatherForecastQuery.parse("33.458111", value, "2026-08-03T15:00:00+09:00"))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("INVALID_WEATHER_FORECAST_QUERY");
  }

  @ParameterizedTest
  @MethodSource("invalidDateTimeLexemes")
  void rejectsNonCanonicalDateTimeLexemes(String value) {
    assertThatThrownBy(() -> WeatherForecastQuery.parse("33.458111", "126.941516", value))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("INVALID_WEATHER_FORECAST_QUERY");
  }

  private static Stream<String> invalidCoordinateLexemes() {
    return Stream.of(" 33.458111", "33.458111 ", "0x1.0p0", "33.0d", "33e0", "３３.５");
  }

  private static Stream<String> invalidDateTimeLexemes() {
    return Stream.of(
        " 2026-08-03T15:00:00+09:00",
        "2026-08-03T15:00+09:00",
        "2026-08-03T15:00:00.000+09:00",
        "2026-08-03T15:00:00Z",
        "2026-08-03T15:00:00+09:00 ");
  }
}
