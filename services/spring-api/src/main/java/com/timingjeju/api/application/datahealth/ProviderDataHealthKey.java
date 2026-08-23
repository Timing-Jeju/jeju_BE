package com.timingjeju.api.application.datahealth;

import java.util.Comparator;
import java.util.Objects;

public record ProviderDataHealthKey(String provider, String service, String operation)
    implements Comparable<ProviderDataHealthKey> {

  private static final int MAX_PART_LENGTH = 128;
  private static final Comparator<ProviderDataHealthKey> ORDER =
      Comparator.comparing(ProviderDataHealthKey::provider)
          .thenComparing(ProviderDataHealthKey::service)
          .thenComparing(ProviderDataHealthKey::operation);

  public ProviderDataHealthKey {
    provider = required(provider, "provider");
    service = required(service, "service");
    operation = required(operation, "operation");
  }

  @Override
  public int compareTo(ProviderDataHealthKey other) {
    return ORDER.compare(this, Objects.requireNonNull(other, "other는 필수입니다."));
  }

  private static String required(String value, String name) {
    Objects.requireNonNull(value, name + "는 필수입니다.");
    if (!value.equals(value.trim()) || value.isBlank() || value.length() > MAX_PART_LENGTH) {
      throw new IllegalArgumentException(name + "가 올바르지 않습니다.");
    }
    return value;
  }
}
