package com.timingjeju.api.domain.weather;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/** Selects only KMA forecast bases whose publication delay has elapsed. */
public final class ForecastBaseTimeResolver {

  private static final ZoneId KOREA_STANDARD_TIME = ZoneId.of("Asia/Seoul");
  private static final ForecastSchedule ULTRA_SHORT_SCHEDULE =
      new ForecastSchedule(hourlyHalfHours(), Duration.ofMinutes(15));
  private static final ForecastSchedule VILLAGE_SCHEDULE =
      new ForecastSchedule(
          List.of(
              LocalTime.of(2, 0),
              LocalTime.of(5, 0),
              LocalTime.of(8, 0),
              LocalTime.of(11, 0),
              LocalTime.of(14, 0),
              LocalTime.of(17, 0),
              LocalTime.of(20, 0),
              LocalTime.of(23, 0)),
          Duration.ofMinutes(10));

  public ForecastBaseTime resolve(ForecastType forecastType, Instant evaluatedAt) {
    Objects.requireNonNull(forecastType, "forecastType은 필수입니다.");
    Objects.requireNonNull(evaluatedAt, "evaluatedAt은 필수입니다.");
    ZonedDateTime nowInKorea = evaluatedAt.atZone(KOREA_STANDARD_TIME);
    ForecastSchedule schedule =
        switch (forecastType) {
          case ULTRA_SHORT -> ULTRA_SHORT_SCHEDULE;
          case VILLAGE -> VILLAGE_SCHEDULE;
        };
    return schedule.latestPublishedAt(nowInKorea.toLocalDateTime());
  }

  public ForecastBaseTime previous(ForecastType forecastType, ForecastBaseTime current) {
    Objects.requireNonNull(forecastType, "forecastType은 필수입니다.");
    Objects.requireNonNull(current, "current는 필수입니다.");
    ForecastSchedule schedule =
        switch (forecastType) {
          case ULTRA_SHORT -> ULTRA_SHORT_SCHEDULE;
          case VILLAGE -> VILLAGE_SCHEDULE;
        };
    return schedule.previous(current);
  }

  private static List<LocalTime> hourlyHalfHours() {
    return java.util.stream.IntStream.range(0, 24)
        .mapToObj(hour -> LocalTime.of(hour, 30))
        .toList();
  }

  private record ForecastSchedule(List<LocalTime> baseTimes, Duration publicationDelay) {

    private ForecastBaseTime latestPublishedAt(LocalDateTime now) {
      LocalDateTime latestEligibleBase = now.minus(publicationDelay);
      LocalTime latestBaseTime =
          baseTimes.reversed().stream()
              .filter(baseTime -> !baseTime.isAfter(latestEligibleBase.toLocalTime()))
              .findFirst()
              .orElse(baseTimes.getLast());
      boolean belongsToPreviousDate = latestBaseTime.isAfter(latestEligibleBase.toLocalTime());
      return new ForecastBaseTime(
          belongsToPreviousDate
              ? latestEligibleBase.toLocalDate().minusDays(1)
              : latestEligibleBase.toLocalDate(),
          latestBaseTime);
    }

    private ForecastBaseTime previous(ForecastBaseTime current) {
      int index = baseTimes.indexOf(current.baseTime());
      if (index < 0) {
        throw new IllegalArgumentException("현재 발표 시각이 예보 일정에 없습니다.");
      }
      if (index > 0) {
        return new ForecastBaseTime(current.baseDate(), baseTimes.get(index - 1));
      }
      return new ForecastBaseTime(current.baseDate().minusDays(1), baseTimes.getLast());
    }
  }
}
