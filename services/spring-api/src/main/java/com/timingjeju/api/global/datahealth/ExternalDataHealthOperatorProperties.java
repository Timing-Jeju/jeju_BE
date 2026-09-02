package com.timingjeju.api.global.datahealth;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.data-health.operator")
public record ExternalDataHealthOperatorProperties(
    boolean enabled, URI issuer, String audience, URI jwksUrl, Duration clockSkew) {

  public ExternalDataHealthOperatorProperties {
    audience = audience == null || audience.isBlank() ? "timing-jeju-ops" : audience;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
  }
}
