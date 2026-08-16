package com.timingjeju.api.domain.weather;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record ForecastBaseTime(LocalDate baseDate, LocalTime baseTime) {

  public ForecastBaseTime {
    Objects.requireNonNull(baseDate, "baseDate는 필수입니다.");
    Objects.requireNonNull(baseTime, "baseTime은 필수입니다.");
  }
}
