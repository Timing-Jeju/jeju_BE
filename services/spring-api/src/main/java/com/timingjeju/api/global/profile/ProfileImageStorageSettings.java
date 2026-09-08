package com.timingjeju.api.global.profile;

import java.net.URI;
import java.time.Duration;

record ProfileImageStorageSettings(
    boolean enabled,
    URI baseUrl,
    String serviceRoleKey,
    Duration connectTimeout,
    Duration readTimeout) {

  private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(30);

  static ProfileImageStorageSettings from(ProfileImageProperties properties, boolean local) {
    validateTimeout(properties.connectTimeout(), "connect timeout");
    validateTimeout(properties.readTimeout(), "read timeout");
    boolean hasUrl = properties.supabaseUrl() != null;
    boolean hasKey = properties.serviceRoleKey() != null && !properties.serviceRoleKey().isBlank();
    if (hasUrl) {
      validateUrl(properties.supabaseUrl(), local);
    }
    if (!hasKey) {
      return new ProfileImageStorageSettings(
          false,
          properties.supabaseUrl(),
          null,
          properties.connectTimeout(),
          properties.readTimeout());
    }
    if (!hasUrl) {
      throw new IllegalStateException("프로필 이미지 Storage URL과 server credential은 함께 설정해야 합니다.");
    }
    return enabled(
        properties.supabaseUrl(),
        properties.serviceRoleKey(),
        properties.connectTimeout(),
        properties.readTimeout());
  }

  static ProfileImageStorageSettings enabled(
      URI baseUrl, String serviceRoleKey, Duration connectTimeout, Duration readTimeout) {
    return new ProfileImageStorageSettings(
        true, baseUrl, serviceRoleKey, connectTimeout, readTimeout);
  }

  private static void validateUrl(URI uri, boolean local) {
    String scheme = uri.getScheme();
    boolean allowedScheme =
        "https".equalsIgnoreCase(scheme) || (local && "http".equalsIgnoreCase(scheme));
    if (!allowedScheme
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalStateException("프로필 이미지 Storage URL이 runtime 보안 정책에 맞지 않습니다.");
    }
  }

  private static void validateTimeout(Duration timeout, String label) {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
      throw new IllegalStateException("프로필 이미지 Storage " + label + " 범위가 올바르지 않습니다.");
    }
  }

  @Override
  public String toString() {
    return "ProfileImageStorageSettings[enabled="
        + enabled
        + ",baseUrl="
        + baseUrl
        + ",serviceRoleKey=<redacted>,connectTimeout="
        + connectTimeout
        + ",readTimeout="
        + readTimeout
        + "]";
  }
}
