package com.timingjeju.api.domain.weather.exception;

public final class WeatherForecastException extends RuntimeException {

  private final String code;

  public WeatherForecastException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
