package com.timingjeju.api.application.snapshot;

import java.util.List;

public final class PersistedSnapshotProviderCatalog {
  private static final List<String> PROVIDERS = List.of("TAGO", "kma", "tour-api");

  private PersistedSnapshotProviderCatalog() {}

  public static List<String> providers() {
    return PROVIDERS;
  }

  public static boolean allows(String provider) {
    return PROVIDERS.contains(provider);
  }
}
