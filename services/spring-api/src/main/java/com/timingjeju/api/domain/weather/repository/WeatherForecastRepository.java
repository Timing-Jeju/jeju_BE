package com.timingjeju.api.domain.weather.repository;

import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.model.PublicWeatherAnchor;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.model.WeatherForecastSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface WeatherForecastRepository {

  Optional<SupportedWeatherGrid> findRegionGrid(String regionCode);

  Optional<PublicWeatherAnchor> findPublicPlace(UUID placeId);

  Optional<PublicWeatherAnchor> findOwnedTripItem(UUID tripItemId, UUID ownerId);

  Optional<SupportedWeatherGrid> findSupportedGrid(KmaGridPoint gridPoint);

  Optional<WeatherForecastSnapshot> find(WeatherForecastLookup lookup);
}
