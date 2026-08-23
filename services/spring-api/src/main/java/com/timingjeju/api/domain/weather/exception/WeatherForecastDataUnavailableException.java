package com.timingjeju.api.domain.weather.exception;

public final class WeatherForecastDataUnavailableException extends RuntimeException {

  public WeatherForecastDataUnavailableException() {
    super(null, null, false, false);
  }
}
