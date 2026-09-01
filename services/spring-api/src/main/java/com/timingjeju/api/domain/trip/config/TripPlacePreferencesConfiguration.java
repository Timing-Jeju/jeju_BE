package com.timingjeju.api.domain.trip.config;

import com.timingjeju.api.application.trip.TripPlacePreferencesStore;
import com.timingjeju.api.application.trip.service.TripPlacePreferencesService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TripPlacePreferencesConfiguration {
  @Bean
  TripPlacePreferencesService tripPlacePreferencesService(
      TripPlacePreferencesStore store, Clock clock) {
    return new TripPlacePreferencesService(store, clock);
  }
}
