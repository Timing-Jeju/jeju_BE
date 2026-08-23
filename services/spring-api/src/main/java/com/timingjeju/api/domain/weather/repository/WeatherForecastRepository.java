package com.timingjeju.api.domain.weather.repository;

import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.model.WeatherForecastSnapshot;
import java.util.Optional;

public interface WeatherForecastRepository {

  Optional<SupportedWeatherGrid> findSupportedGrid(KmaGridPoint gridPoint);

  Optional<WeatherForecastSnapshot> find(WeatherForecastLookup lookup);
}
