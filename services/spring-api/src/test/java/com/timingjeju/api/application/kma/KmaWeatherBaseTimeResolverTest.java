package com.timingjeju.api.application.kma;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class KmaWeatherBaseTimeResolverTest {

  @Test
  void resolvesVillageOfficialBaseAndPreviousPublicationAcrossDateBoundary() {
    KmaWeatherBaseTimeResolver resolver =
        new KmaWeatherBaseTimeResolver(
            Clock.fixed(Instant.parse("2026-08-15T16:09:59Z"), ZoneOffset.UTC));

    ForecastBaseTime latest = resolver.latest(KmaWeatherOperation.VILLAGE_FORECAST);

    assertThat(latest).isEqualTo(base("2026-08-15", "23:00"));
    assertThat(resolver.previous(KmaWeatherOperation.VILLAGE_FORECAST, latest))
        .isEqualTo(base("2026-08-15", "20:00"));
  }

  @Test
  void currentUsesPreviousHourBeforeTenMinutePublicationBoundary() {
    assertThat(resolverAt("2026-08-16T00:09:59+09:00").latest(KmaWeatherOperation.ULTRA_CURRENT))
        .isEqualTo(base("2026-08-15", "23:00"));
  }

  @Test
  void currentUsesCurrentHourAtTenMinutePublicationBoundary() {
    assertThat(resolverAt("2026-08-16T00:10:00+09:00").latest(KmaWeatherOperation.ULTRA_CURRENT))
        .isEqualTo(base("2026-08-16", "00:00"));
  }

  @Test
  void forecastReusesHalfHourAndFifteenMinutePublicationContract() {
    assertThat(resolverAt("2026-08-16T00:44:59+09:00").latest(KmaWeatherOperation.ULTRA_FORECAST))
        .isEqualTo(base("2026-08-15", "23:30"));
    assertThat(resolverAt("2026-08-16T00:45:00+09:00").latest(KmaWeatherOperation.ULTRA_FORECAST))
        .isEqualTo(base("2026-08-16", "00:30"));
  }

  @Test
  void previousBaseMovesExactlyOnePublicationAcrossDateBoundary() {
    KmaWeatherBaseTimeResolver resolver = resolverAt("2026-08-16T00:45:00+09:00");

    assertThat(resolver.previous(KmaWeatherOperation.ULTRA_CURRENT, base("2026-08-16", "00:00")))
        .isEqualTo(base("2026-08-15", "23:00"));
    assertThat(resolver.previous(KmaWeatherOperation.ULTRA_FORECAST, base("2026-08-16", "00:30")))
        .isEqualTo(base("2026-08-15", "23:30"));
  }

  private static KmaWeatherBaseTimeResolver resolverAt(String value) {
    return new KmaWeatherBaseTimeResolver(
        Clock.fixed(java.time.OffsetDateTime.parse(value).toInstant(), ZoneOffset.UTC));
  }

  private static ForecastBaseTime base(String date, String time) {
    return new ForecastBaseTime(LocalDate.parse(date), LocalTime.parse(time));
  }
}
