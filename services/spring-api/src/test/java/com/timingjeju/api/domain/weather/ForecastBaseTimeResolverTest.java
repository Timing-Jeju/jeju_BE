package com.timingjeju.api.domain.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ForecastBaseTimeResolverTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  @Test
  void selectsPreviousUltraShortBaseBeforePublicationDelayEnds() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-08-16T00:44:59+09:00");

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT)).isEqualTo(base("2026-08-15", "23:30"));
  }

  @Test
  void selectsCurrentUltraShortBaseAtPublicationBoundary() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-08-16T00:45:00+09:00");

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT)).isEqualTo(base("2026-08-16", "00:30"));
  }

  @Test
  void selectsPreviousVillageBaseBeforePublicationDelayEnds() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-08-16T02:09:59+09:00");

    assertThat(resolver.resolve(ForecastType.VILLAGE)).isEqualTo(base("2026-08-15", "23:00"));
  }

  @Test
  void selectsCurrentVillageBaseAtPublicationBoundary() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-08-16T02:10:00+09:00");

    assertThat(resolver.resolve(ForecastType.VILLAGE)).isEqualTo(base("2026-08-16", "02:00"));
  }

  @Test
  void selectsLatestVillageBaseBetweenScheduledPublications() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-08-16T16:59:59+09:00");

    assertThat(resolver.resolve(ForecastType.VILLAGE)).isEqualTo(base("2026-08-16", "14:00"));
  }

  @Test
  void appliesPublicationDelayToLastVillageBaseOfDay() {
    assertThat(resolverAt("2026-08-16T23:09:59+09:00").resolve(ForecastType.VILLAGE))
        .isEqualTo(base("2026-08-16", "20:00"));
    assertThat(resolverAt("2026-08-16T23:10:00+09:00").resolve(ForecastType.VILLAGE))
        .isEqualTo(base("2026-08-16", "23:00"));
  }

  @Test
  void crossesYearBoundaryUsingKoreaStandardTime() {
    ForecastBaseTimeResolver resolver = resolverAt("2026-01-01T00:05:00+09:00");

    assertThat(resolver.resolve(ForecastType.VILLAGE)).isEqualTo(base("2025-12-31", "23:00"));
  }

  @Test
  void convertsTheClockInstantToAsiaSeoulRegardlessOfClockZone() {
    Instant instant = Instant.parse("2026-02-28T15:44:59Z");
    Clock nonKoreanClock = Clock.fixed(instant, ZoneId.of("America/New_York"));
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver(nonKoreanClock);

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT)).isEqualTo(base("2026-02-28", "23:30"));
  }

  @Test
  void asiaSeoulHasNoDaylightSavingOffsetChange() {
    ForecastBaseTimeResolver winter = resolverAt("2026-01-01T12:45:00+09:00");
    ForecastBaseTimeResolver summer = resolverAt("2026-07-01T12:45:00+09:00");

    assertThat(winter.resolve(ForecastType.ULTRA_SHORT).baseTime()).isEqualTo(LocalTime.of(12, 30));
    assertThat(summer.resolve(ForecastType.ULTRA_SHORT).baseTime()).isEqualTo(LocalTime.of(12, 30));
    assertThat(SEOUL.getRules().getOffset(Instant.parse("2026-01-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(9));
    assertThat(SEOUL.getRules().getOffset(Instant.parse("2026-07-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(9));
  }

  @Test
  void requiresClockAndForecastType() {
    assertThatNullPointerException().isThrownBy(() -> new ForecastBaseTimeResolver(null));
    assertThatNullPointerException()
        .isThrownBy(() -> resolverAt("2026-08-16T12:00:00+09:00").resolve(null));
  }

  @Test
  void requiresBaseDateAndTime() {
    assertThatNullPointerException().isThrownBy(() -> new ForecastBaseTime(null, LocalTime.NOON));
    assertThatNullPointerException()
        .isThrownBy(() -> new ForecastBaseTime(LocalDate.of(2026, 8, 16), null));
  }

  private static ForecastBaseTimeResolver resolverAt(String dateTime) {
    Instant instant = java.time.OffsetDateTime.parse(dateTime).toInstant();
    return new ForecastBaseTimeResolver(Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static ForecastBaseTime base(String date, String time) {
    return new ForecastBaseTime(LocalDate.parse(date), LocalTime.parse(time));
  }
}
