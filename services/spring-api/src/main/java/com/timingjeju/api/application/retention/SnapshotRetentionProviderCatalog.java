package com.timingjeju.api.application.retention;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthCatalog;
import java.util.List;

public final class SnapshotRetentionProviderCatalog {
  private static final List<String> EXPECTED = List.of("TAGO", "kma", "tour-api");
  private static final List<String> PROVIDERS = derive();

  private SnapshotRetentionProviderCatalog() {}

  public static List<String> providers() {
    return PROVIDERS;
  }

  private static List<String> derive() {
    List<String> providers =
        CompletedProviderDataHealthCatalog.keys().stream()
            .map(key -> key.provider())
            .distinct()
            .sorted()
            .toList();
    if (!providers.equals(EXPECTED)) {
      throw new IllegalStateException("완료 공급자 snapshot retention catalog가 올바르지 않습니다.");
    }
    return providers;
  }
}
