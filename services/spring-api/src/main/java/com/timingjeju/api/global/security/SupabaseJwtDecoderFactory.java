package com.timingjeju.api.global.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class SupabaseJwtDecoderFactory {

  private final SupabaseJwtProperties properties;
  private final SecurityRuntimePolicy runtimePolicy;
  private final List<JwtDecoderStrategy> strategies;

  public SupabaseJwtDecoderFactory(
      SupabaseJwtProperties properties, SecurityRuntimePolicy runtimePolicy) {
    this(
        properties,
        runtimePolicy,
        List.of(new JwksJwtDecoderStrategy(), new LocalHs256JwtDecoderStrategy()));
  }

  public SupabaseJwtDecoderFactory(
      SupabaseJwtProperties properties,
      SecurityRuntimePolicy runtimePolicy,
      List<JwtDecoderStrategy> strategies) {
    this.properties = properties;
    this.runtimePolicy = runtimePolicy;
    this.strategies = List.copyOf(strategies);
  }

  public JwtDecoder create() {
    validateCommonProperties();
    NimbusJwtDecoder decoder =
        strategies.stream()
            .filter(strategy -> strategy.mode() == properties.mode())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("지원하지 않는 JWT decoder mode입니다."))
            .create(properties, runtimePolicy.environment());
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(properties.clockSkew()),
            new JwtIssuerValidator(properties.issuer().toString()),
            new SupabaseJwtValidator(properties.audience())));
    return decoder;
  }

  private void validateCommonProperties() {
    if (properties.mode() != runtimePolicy.allowedDecoderMode()) {
      throw new IllegalStateException(
          "현재 보안 profile에서는 " + properties.mode() + " JWT decoder mode를 사용할 수 없습니다.");
    }
    JwtEndpointPolicy.validate(
        properties.issuer(), "SUPABASE_JWT_ISSUER", runtimePolicy.environment());
    if (properties.clockSkew().isNegative()
        || properties.clockSkew().compareTo(java.time.Duration.ofSeconds(60)) > 0) {
      throw new IllegalStateException("JWT clock skew는 0초 이상 60초 이하여야 합니다.");
    }
  }
}
