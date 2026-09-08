package com.timingjeju.api.domain.weather.exception;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Tag("unit")
class WeatherForecastProblemExceptionHandlerTest {
  @Test
  void MVC_검증_예외도_v2_선택자_오류로_고정한다() throws Exception {
    var writer = mock(ProblemResponseWriter.class);
    var request = mock(HttpServletRequest.class);
    var response = mock(HttpServletResponse.class);
    new WeatherForecastProblemExceptionHandler(writer)
        .handleValidation(mock(HandlerMethodValidationException.class), request, response);
    verify(writer).write(request, response, "INVALID_WEATHER_SELECTOR");
  }
}
