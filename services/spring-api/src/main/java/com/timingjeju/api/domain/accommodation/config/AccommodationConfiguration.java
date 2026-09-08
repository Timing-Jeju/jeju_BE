package com.timingjeju.api.domain.accommodation.config;

import com.timingjeju.api.application.accommodation.AccommodationIdentityGenerator;
import com.timingjeju.api.application.accommodation.AccommodationStore;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AccommodationConfiguration {
  @Bean
  AccommodationIdentityGenerator accommodationIdentityGenerator() {
    return UUID::randomUUID;
  }

  @Bean
  AccommodationService accommodationService(
      AccommodationStore store,
      AccommodationIdentityGenerator identities,
      Clock clock,
      ObjectMapper objectMapper) {
    return new AccommodationService(store, identities, clock, objectMapper);
  }
}
