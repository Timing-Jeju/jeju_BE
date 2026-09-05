package com.timingjeju.api.global.profile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.profile-image.maintenance")
record ProfileImageMaintenanceProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("PT1M") Duration cleanupFixedDelay,
    @DefaultValue("PT24H") Duration orphanFixedDelay,
    @DefaultValue("PT1M") Duration initialDelay) {

  ProfileImageMaintenanceProperties {
    requirePositive(cleanupFixedDelay, "cleanup fixed delay");
    requirePositive(orphanFixedDelay, "orphan fixed delay");
    requirePositive(initialDelay, "initial delay");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException("profile image maintenance " + name + " must be positive");
    }
  }
}
