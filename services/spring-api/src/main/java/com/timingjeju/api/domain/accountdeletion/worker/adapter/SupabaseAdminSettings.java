package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import java.net.URI;
import java.time.Duration;

public record SupabaseAdminSettings(
    URI baseUrl,
    String serviceRoleKey,
    Duration connectTimeout,
    Duration readTimeout,
    int storagePageSize,
    int storageMaximumPages) {

  private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

  public SupabaseAdminSettings {
    if (baseUrl == null
        || !allowedScheme(baseUrl)
        || baseUrl.getHost() == null
        || baseUrl.getRawUserInfo() != null
        || baseUrl.getRawQuery() != null
        || baseUrl.getRawFragment() != null) {
      throw new IllegalStateException("Supabase Admin URL이 runtime 보안 정책에 맞지 않습니다.");
    }
    if (serviceRoleKey == null || serviceRoleKey.isBlank()) {
      throw new IllegalStateException("Supabase service-role credential은 필수입니다.");
    }
    validateTimeout(connectTimeout);
    validateTimeout(readTimeout);
    if (storagePageSize < 1 || storagePageSize > 1000) {
      throw new IllegalStateException("Storage page size는 1~1000이어야 합니다.");
    }
    if (storageMaximumPages < 1 || storageMaximumPages > 1000) {
      throw new IllegalStateException("Storage maximum pages는 1~1000이어야 합니다.");
    }
  }

  private static boolean allowedScheme(URI baseUrl) {
    if ("https".equalsIgnoreCase(baseUrl.getScheme())) {
      return true;
    }
    if (!"http".equalsIgnoreCase(baseUrl.getScheme())) {
      return false;
    }
    String host = baseUrl.getHost();
    return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
  }

  public static SupabaseAdminSettings enabled(
      URI baseUrl,
      String serviceRoleKey,
      Duration connectTimeout,
      Duration readTimeout,
      int storagePageSize,
      int storageMaximumPages) {
    return new SupabaseAdminSettings(
        baseUrl, serviceRoleKey, connectTimeout, readTimeout, storagePageSize, storageMaximumPages);
  }

  private static void validateTimeout(Duration timeout) {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalStateException("Supabase Admin timeout 범위가 올바르지 않습니다.");
    }
  }

  @Override
  public String toString() {
    return "SupabaseAdminSettings[baseUrl="
        + baseUrl
        + ",serviceRoleKey=<redacted>,connectTimeout="
        + connectTimeout
        + ",readTimeout="
        + readTimeout
        + ",storagePageSize="
        + storagePageSize
        + ",storageMaximumPages="
        + storageMaximumPages
        + "]";
  }
}
