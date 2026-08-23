package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class CompletedProviderDataHealthIndicator implements HealthIndicator {
  private final CompletedProviderDataHealthService service;

  public CompletedProviderDataHealthIndicator(CompletedProviderDataHealthService service) {
    this.service = Objects.requireNonNull(service, "service는 필수입니다.");
  }

  @Override
  public Health health() {
    try {
      boolean healthy =
          service.collect().stream()
              .allMatch(
                  item ->
                      item.status() == ProviderDataHealthStatus.FRESH
                          || item.status() == ProviderDataHealthStatus.DISABLED);
      return healthy ? Health.up().build() : Health.down().build();
    } catch (ProviderDataHealthException failure) {
      if (failure.code() == ProviderDataHealthException.Code.DATA_HEALTH_UNAVAILABLE) {
        return Health.down().build();
      }
      throw failure;
    }
  }
}
