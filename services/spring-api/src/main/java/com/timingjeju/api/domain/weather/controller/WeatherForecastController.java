package com.timingjeju.api.domain.weather.controller;

import com.timingjeju.api.domain.weather.controller.docs.WeatherForecastApiDocs;
import com.timingjeju.api.domain.weather.dto.request.WeatherForecastQuery;
import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import com.timingjeju.api.domain.weather.service.WeatherForecastQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/weather/forecast")
public class WeatherForecastController implements WeatherForecastApiDocs {

  private static final Set<String> ALLOWED_PARAMETERS = Set.of("lat", "lng", "dateTime");

  private final WeatherForecastQueryService service;

  public WeatherForecastController(WeatherForecastQueryService service) {
    this.service = service;
  }

  @Override
  @GetMapping
  public WeatherForecastResponse forecast(
      @RequestParam(required = false) String lat,
      @RequestParam(required = false) String lng,
      @RequestParam(required = false) String dateTime,
      HttpServletRequest request) {
    validateParameterShape(request);
    return service.forecast(WeatherForecastQuery.parse(lat, lng, dateTime));
  }

  private static void validateParameterShape(HttpServletRequest request) {
    if (!ALLOWED_PARAMETERS.containsAll(request.getParameterMap().keySet())
        || ALLOWED_PARAMETERS.stream()
            .anyMatch(
                name ->
                    request.getParameterValues(name) == null
                        || request.getParameterValues(name).length != 1)) {
      throw new WeatherForecastException("INVALID_WEATHER_FORECAST_QUERY");
    }
  }
}
