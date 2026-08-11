package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;

public record ExternalApiClientSettings(
    ExternalApiProvider provider,
    ExternalApiCredential credential,
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout) {

  static ExternalApiClientSettings from(ExternalApiProviderProperties properties) {
    return new ExternalApiClientSettings(
        properties.provider(),
        ExternalApiCredential.from(properties.provider(), properties.apiKey()),
        properties.baseUrl(),
        properties.connectTimeout(),
        properties.readTimeout());
  }

  @Override
  public String toString() {
    return "ExternalApiClientSettings[provider="
        + provider
        + ", credential=[REDACTED], baseUrl="
        + baseUrl
        + ", connectTimeout="
        + connectTimeout
        + ", readTimeout="
        + readTimeout
        + "]";
  }
}
