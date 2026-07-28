package com.timingjeju.api.global.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class SupabaseJwtDecoderFactory {

  private final SupabaseJwtProperties properties;
  private final SecurityRuntimeEnvironment runtimeEnvironment;
  private final List<JwtDecoderStrategy> strategies;

  public SupabaseJwtDecoderFactory(
      SupabaseJwtProperties properties, SecurityRuntimeEnvironment runtimeEnvironment) {
    this(
        properties,
        runtimeEnvironment,
        List.of(new JwksJwtDecoderStrategy(), new LocalHs256JwtDecoderStrategy()));
  }

  public SupabaseJwtDecoderFactory(
      SupabaseJwtProperties properties,
      SecurityRuntimeEnvironment runtimeEnvironment,
      List<JwtDecoderStrategy> strategies) {
    this.properties = properties;
    this.runtimeEnvironment = runtimeEnvironment;
    this.strategies = List.copyOf(strategies);
  }

  public JwtDecoder create() {
    validateCommonProperties();
    NimbusJwtDecoder decoder =
        strategies.stream()
            .filter(strategy -> strategy.mode() == properties.mode())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("지원하지 않는 JWT decoder mode입니다."))
            .create(properties, runtimeEnvironment);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(properties.clockSkew()),
            new JwtIssuerValidator(properties.issuer().toString()),
            new SupabaseJwtValidator(properties.audience())));
    return decoder;
  }

  private void validateCommonProperties() {
    JwtEndpointPolicy.validate(properties.issuer(), "SUPABASE_JWT_ISSUER", runtimeEnvironment);
    if (properties.clockSkew().isNegative()
        || properties.clockSkew().compareTo(java.time.Duration.ofSeconds(60)) > 0) {
      throw new IllegalStateException("JWT clock skew는 0초 이상 60초 이하여야 합니다.");
    }
  }
}
