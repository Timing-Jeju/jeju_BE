package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

final class ExternalApiPropertyValidation {

  private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);
  private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration MAXIMUM_READ_TIMEOUT = Duration.ofSeconds(30);

  private ExternalApiPropertyValidation() {}

  static void validate(ExternalApiProviderProperties properties) {
    validateTimeout(
        properties.provider(),
        "CONNECT_TIMEOUT",
        properties.connectTimeout(),
        MAXIMUM_CONNECT_TIMEOUT);
    validateTimeout(
        properties.provider(), "READ_TIMEOUT", properties.readTimeout(), MAXIMUM_READ_TIMEOUT);

    if (properties.baseUrl() != null && !properties.baseUrl().toString().isBlank()) {
      validateBaseUrl(properties.provider(), properties.baseUrl());
    }
    if (!properties.enabled()) {
      return;
    }
    validateKey(properties.provider(), properties.apiKey());
    validateBaseUrl(properties.provider(), properties.baseUrl());
  }

  private static void validateKey(ExternalApiProvider provider, String apiKey) {
    if (apiKey == null) {
      throw new IllegalArgumentException(
          provider.environmentName("API_KEY") + "는 활성화된 provider에서 필수입니다.");
    }
    String normalized = apiKey.trim();
    if (normalized.isEmpty() || isPlaceholder(normalized)) {
      throw new IllegalArgumentException(
          provider.environmentName("API_KEY") + "는 실제 발급값으로 설정해야 합니다.");
    }
  }

  private static boolean isPlaceholder(String value) {
    String lowercase = value.toLowerCase(Locale.ROOT);
    return lowercase.equals("changeme")
        || lowercase.equals("replace-me")
        || lowercase.startsWith("your-")
        || (lowercase.startsWith("<") && lowercase.endsWith(">"))
        || (lowercase.startsWith("${") && lowercase.endsWith("}"));
  }

  private static void validateBaseUrl(ExternalApiProvider provider, URI baseUrl) {
    if (baseUrl == null) {
      throw new IllegalArgumentException(
          provider.environmentName("BASE_URL") + "는 활성화된 provider에서 필수입니다.");
    }
    String scheme = baseUrl.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
        || !provider.allows(baseUrl)) {
      throw new IllegalArgumentException(
          provider.environmentName("BASE_URL") + "가 허용된 provider base URL이 아닙니다.");
    }
  }

  private static void validateTimeout(
      ExternalApiProvider provider, String suffix, Duration timeout, Duration maximum) {
    if (timeout == null
        || timeout.compareTo(MINIMUM_TIMEOUT) < 0
        || timeout.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          provider.environmentName(suffix)
              + "는 "
              + MINIMUM_TIMEOUT.toMillis()
              + "ms 이상 "
              + maximum.toMillis()
              + "ms 이하여야 합니다.");
    }
  }

  static String safeDescription(ExternalApiProviderProperties properties) {
    return properties.getClass().getSimpleName()
        + "[enabled="
        + properties.enabled()
        + ", apiKey=[REDACTED], baseUrl="
        + (properties.baseUrl() == null || properties.baseUrl().toString().isBlank()
            ? "[NOT_CONFIGURED]"
            : "[CONFIGURED]")
        + ", connectTimeout="
        + properties.connectTimeout()
        + ", readTimeout="
        + properties.readTimeout()
        + "]";
  }
}
