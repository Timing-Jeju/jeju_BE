package com.timingjeju.api.application.kma;

import com.timingjeju.api.domain.weather.ForecastBaseTime;

@FunctionalInterface
public interface KmaWeatherSource {
  KmaWeatherSourceResponse fetch(
      KmaWeatherOperation operation, ForecastBaseTime baseTime, int nx, int ny);
}
