package com.timingjeju.api.domain.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT, instant("2026-08-16T00:44:59+09:00")))
        .isEqualTo(base("2026-08-15", "23:30"));
  }

  @Test
  void selectsCurrentUltraShortBaseAtPublicationBoundary() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT, instant("2026-08-16T00:45:00+09:00")))
        .isEqualTo(base("2026-08-16", "00:30"));
  }

  @Test
  void selectsPreviousVillageBaseBeforePublicationDelayEnds() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-08-16T02:09:59+09:00")))
        .isEqualTo(base("2026-08-15", "23:00"));
  }

  @Test
  void selectsCurrentVillageBaseAtPublicationBoundary() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-08-16T02:10:00+09:00")))
        .isEqualTo(base("2026-08-16", "02:00"));
  }

  @Test
  void selectsLatestVillageBaseBetweenScheduledPublications() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-08-16T16:59:59+09:00")))
        .isEqualTo(base("2026-08-16", "14:00"));
  }

  @Test
  void appliesPublicationDelayToLastVillageBaseOfDay() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();
    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-08-16T23:09:59+09:00")))
        .isEqualTo(base("2026-08-16", "20:00"));
    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-08-16T23:10:00+09:00")))
        .isEqualTo(base("2026-08-16", "23:00"));
  }

  @Test
  void crossesYearBoundaryUsingKoreaStandardTime() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.VILLAGE, instant("2026-01-01T00:05:00+09:00")))
        .isEqualTo(base("2025-12-31", "23:00"));
  }

  @Test
  void previousBaseCrossesMidnightForBothForecastSchedules() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.previous(ForecastType.ULTRA_SHORT, base("2026-08-16", "00:30")))
        .isEqualTo(base("2026-08-15", "23:30"));
    assertThat(resolver.previous(ForecastType.VILLAGE, base("2026-08-16", "02:00")))
        .isEqualTo(base("2026-08-15", "23:00"));
  }

  @Test
  void convertsTheClockInstantToAsiaSeoulRegardlessOfClockZone() {
    Instant instant = Instant.parse("2026-02-28T15:44:59Z");
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(resolver.resolve(ForecastType.ULTRA_SHORT, instant))
        .isEqualTo(base("2026-02-28", "23:30"));
  }

  @Test
  void asiaSeoulHasNoDaylightSavingOffsetChange() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();

    assertThat(
            resolver
                .resolve(ForecastType.ULTRA_SHORT, instant("2026-01-01T12:45:00+09:00"))
                .baseTime())
        .isEqualTo(LocalTime.of(12, 30));
    assertThat(
            resolver
                .resolve(ForecastType.ULTRA_SHORT, instant("2026-07-01T12:45:00+09:00"))
                .baseTime())
        .isEqualTo(LocalTime.of(12, 30));
    assertThat(SEOUL.getRules().getOffset(Instant.parse("2026-01-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(9));
    assertThat(SEOUL.getRules().getOffset(Instant.parse("2026-07-01T00:00:00Z")))
        .isEqualTo(ZoneOffset.ofHours(9));
  }

  @Test
  void requiresEvaluationInstantAndForecastType() {
    ForecastBaseTimeResolver resolver = new ForecastBaseTimeResolver();
    assertThatNullPointerException()
        .isThrownBy(() -> resolver.resolve(null, instant("2026-08-16T12:00:00+09:00")));
    assertThatNullPointerException().isThrownBy(() -> resolver.resolve(ForecastType.VILLAGE, null));
  }

  @Test
  void requiresBaseDateAndTime() {
    assertThatNullPointerException().isThrownBy(() -> new ForecastBaseTime(null, LocalTime.NOON));
    assertThatNullPointerException()
        .isThrownBy(() -> new ForecastBaseTime(LocalDate.of(2026, 8, 16), null));
  }

  private static Instant instant(String dateTime) {
    return java.time.OffsetDateTime.parse(dateTime).toInstant();
  }

  private static ForecastBaseTime base(String date, String time) {
    return new ForecastBaseTime(LocalDate.parse(date), LocalTime.parse(time));
  }
}
