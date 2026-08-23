package com.timingjeju.api.domain.weather.exception;

import com.timingjeju.api.domain.weather.controller.WeatherForecastController;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WeatherForecastController.class)
public class WeatherForecastProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public WeatherForecastProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(WeatherForecastException.class)
  void handle(
      WeatherForecastException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  void handleValidation(
      HandlerMethodValidationException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_WEATHER_FORECAST_QUERY");
  }
}
