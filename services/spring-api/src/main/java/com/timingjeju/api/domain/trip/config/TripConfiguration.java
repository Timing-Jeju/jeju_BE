package com.timingjeju.api.domain.trip.config;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.trip.TripIdentityGenerator;
import com.timingjeju.api.application.trip.TripStore;
import com.timingjeju.api.application.trip.service.TripService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TripConfiguration {
  @Bean("tripCursorCodec")
  TripCursorCodec tripCursorCodec(
      @Value("${app.trips.cursor-signing-key:}") String configuredKey,
      @Qualifier("localSecurityRuntime") boolean localRuntime) {
    if (configuredKey != null && !configuredKey.isBlank()) {
      return new TripCursorCodec(CursorCodec.hmacSha256(configuredKey));
    }
    if (!localRuntime) {
      throw new IllegalStateException("운영 환경의 APP_TRIPS_CURSOR_SIGNING_KEY는 필수입니다.");
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return new TripCursorCodec(
        CursorCodec.hmacSha256(Base64.getUrlEncoder().withoutPadding().encodeToString(random)));
  }

  @Bean
  TripIdentityGenerator tripIdentityGenerator() {
    return UUID::randomUUID;
  }

  @Bean
  TripService tripService(
      CurrentUserProvisioningService provisioning,
      TripStore store,
      TripIdentityGenerator identities,
      @Qualifier("tripCursorCodec") TripCursorCodec tripCursorCodec,
      Clock clock) {
    return new TripService(provisioning, store, identities, tripCursorCodec.value(), clock);
  }

  record TripCursorCodec(CursorCodec value) {}
}
