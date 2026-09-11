package com.timingjeju.api.domain.weather.controller;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
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

  private static final Set<String> ALLOWED_PARAMETERS =
      Set.of("regionCode", "placeId", "tripItemId", "dateTime");

  private final WeatherForecastQueryService service;

  private final CurrentUserAccessor currentUsers;

  public WeatherForecastController(
      WeatherForecastQueryService service, CurrentUserAccessor currentUsers) {
    this.service = service;
    this.currentUsers = currentUsers;
  }

  @Override
  @GetMapping
  public WeatherForecastResponse forecast(
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false) String placeId,
      @RequestParam(required = false) String tripItemId,
      @RequestParam(required = false) String dateTime,
      HttpServletRequest request) {
    validateParameterShape(request);
    return service.forecast(
        WeatherForecastQuery.parse(regionCode, placeId, tripItemId, dateTime),
        currentUsers.getOptional().map(CurrentUser::userId).orElse(null));
  }

  private static void validateParameterShape(HttpServletRequest request) {
    if (!ALLOWED_PARAMETERS.containsAll(request.getParameterMap().keySet())
        || request.getParameterMap().keySet().stream()
            .anyMatch(name -> request.getParameterValues(name).length != 1)) {
      throw new WeatherForecastException("INVALID_WEATHER_SELECTOR");
    }
  }
}
