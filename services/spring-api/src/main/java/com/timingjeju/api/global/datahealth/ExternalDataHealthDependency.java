package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.ProviderDataHealthItem;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReason;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.time.Instant;
import java.util.Objects;

public record ExternalDataHealthDependency(
    String provider,
    String service,
    String operation,
    ProviderDataHealthStatus status,
    Instant lastAttemptAt,
    Instant lastSuccessAt,
    Instant factsAsOf,
    boolean stale,
    ProviderDataHealthReason reasonCode,
    ExternalDataFallbackCode fallbackCode) {

  public ExternalDataHealthDependency {
    provider = required(provider);
    service = required(service);
    operation = required(operation);
    Objects.requireNonNull(status, "status는 필수입니다.");
    Objects.requireNonNull(reasonCode, "reasonCode는 필수입니다.");
    Objects.requireNonNull(fallbackCode, "fallbackCode는 필수입니다.");
  }

  static ExternalDataHealthDependency from(ProviderDataHealthItem item) {
    Objects.requireNonNull(item, "item은 필수입니다.");
    return new ExternalDataHealthDependency(
        item.key().provider(),
        item.key().service(),
        item.key().operation(),
        item.status(),
        item.lastAttemptAt(),
        item.lastSuccessAt(),
        item.factsAsOf(),
        item.stale(),
        item.reasonCode(),
        ExternalDataFallbackCode.대체_미사용);
  }

  static ExternalDataHealthDependency disabledMobility() {
    return new ExternalDataHealthDependency(
        "mobility-route",
        "provider-neutral",
        "route",
        ProviderDataHealthStatus.DISABLED,
        null,
        null,
        null,
        false,
        ProviderDataHealthReason.PROVIDER_DISABLED,
        ExternalDataFallbackCode.대체_미사용);
  }

  private static String required(String value) {
    Objects.requireNonNull(value, "진단 식별자는 필수입니다.");
    if (value.isBlank() || !value.equals(value.trim()) || value.length() > 128) {
      throw new IllegalArgumentException("진단 식별자가 올바르지 않습니다.");
    }
    return value;
  }
}
