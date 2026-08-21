package com.timingjeju.api.application.kma;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastBaseTimeResolver;
import com.timingjeju.api.domain.weather.ForecastType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

public final class KmaWeatherBaseTimeResolver {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private final Clock clock;
  private final ForecastBaseTimeResolver forecastResolver;

  public KmaWeatherBaseTimeResolver(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.forecastResolver = new ForecastBaseTimeResolver(clock);
  }

  public ForecastBaseTime latest(KmaWeatherOperation operation) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    if (operation == KmaWeatherOperation.ULTRA_FORECAST)
      return forecastResolver.resolve(ForecastType.ULTRA_SHORT);
    if (operation == KmaWeatherOperation.VILLAGE_FORECAST)
      return forecastResolver.resolve(ForecastType.VILLAGE);
    LocalDateTime eligible = clock.instant().atZone(KOREA).toLocalDateTime().minusMinutes(10);
    return new ForecastBaseTime(eligible.toLocalDate(), LocalTime.of(eligible.getHour(), 0));
  }

  public ForecastBaseTime previous(KmaWeatherOperation operation, ForecastBaseTime base) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(base, "base는 필수입니다.");
    if (operation == KmaWeatherOperation.VILLAGE_FORECAST) {
      LocalDateTime justBefore = LocalDateTime.of(base.baseDate(), base.baseTime()).minusNanos(1);
      Clock previousClock = Clock.fixed(justBefore.atZone(KOREA).toInstant(), KOREA);
      return new ForecastBaseTimeResolver(previousClock).resolve(ForecastType.VILLAGE);
    }
    LocalDateTime previous =
        LocalDateTime.of(base.baseDate(), base.baseTime()).minus(Duration.ofHours(1));
    return new ForecastBaseTime(previous.toLocalDate(), previous.toLocalTime());
  }
}
