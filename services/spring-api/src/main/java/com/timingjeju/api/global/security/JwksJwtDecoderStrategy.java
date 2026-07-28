package com.timingjeju.api.global.security;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class JwksJwtDecoderStrategy implements JwtDecoderStrategy {

  @Override
  public JwtDecoderMode mode() {
    return JwtDecoderMode.JWKS;
  }

  @Override
  public NimbusJwtDecoder create(
      SupabaseJwtProperties properties, boolean localCompatibilityProfile) {
    if (!properties.secret().isBlank()) {
      throw new IllegalStateException("JWKS 검증 환경에는 SUPABASE_JWT_SECRET을 주입할 수 없습니다.");
    }
    if (!isHttpUri(properties.jwksUrl())) {
      throw new IllegalStateException("JWKS 검증에는 SUPABASE_JWKS_URL이 필요합니다.");
    }
    return NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl().toString())
        .jwsAlgorithms(
            algorithms -> {
              algorithms.add(SignatureAlgorithm.ES256);
              algorithms.add(SignatureAlgorithm.RS256);
            })
        .build();
  }

  private boolean isHttpUri(java.net.URI uri) {
    return uri != null
        && uri.isAbsolute()
        && uri.getHost() != null
        && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
  }
}
