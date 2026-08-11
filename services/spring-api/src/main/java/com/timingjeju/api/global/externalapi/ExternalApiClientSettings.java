package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;

public record ExternalApiClientSettings(
    ExternalApiProvider provider,
    String apiKey,
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  static ExternalApiClientSettings from(ExternalApiProviderProperties properties) {
    return new ExternalApiClientSettings(
        properties.provider(),
        properties.apiKey(),
        properties.baseUrl(),
        properties.connectTimeout(),
        properties.readTimeout());
  }

  @Override
  public String toString() {
    return "ExternalApiClientSettings[provider="
        + provider
        + ", apiKey=[REDACTED], baseUrl="
        + baseUrl
        + ", connectTimeout="
        + connectTimeout
        + ", readTimeout="
        + readTimeout
        + "]";
  }
}
