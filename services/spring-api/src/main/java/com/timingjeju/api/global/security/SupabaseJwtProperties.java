package com.timingjeju.api.global.security;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.jwt")
public record SupabaseJwtProperties(
    JwtDecoderMode mode,
    URI issuer,
    String audience,
    URI jwksUrl,
    String secret,
    Duration clockSkew) {

  public SupabaseJwtProperties {
    mode = mode == null ? JwtDecoderMode.JWKS : mode;
    audience = audience == null || audience.isBlank() ? "authenticated" : audience;
    secret = secret == null ? "" : secret;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
  }
}
