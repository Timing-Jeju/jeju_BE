package com.timingjeju.api.global.datahealth;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

final class OpsJwtDecoderFactory {
  private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);

  private OpsJwtDecoderFactory() {}

  static JwtDecoder create(ExternalDataHealthOperatorProperties properties) {
    validate(properties);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl().toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(properties.clockSkew()),
            new JwtIssuerValidator(properties.issuer().toString()),
            new OpsJwtValidator(properties.audience())));
    return decoder;
  }

  static void validate(ExternalDataHealthOperatorProperties properties) {
    requireHttps(properties.issuer(), "OPS_JWT_ISSUER");
    requireHttps(properties.jwksUrl(), "OPS_JWT_JWKS_URL");
    if (!"timing-jeju-ops".equals(properties.audience())) {
      throw new IllegalStateException("운영 진단 JWT audience는 timing-jeju-ops여야 합니다.");
    }
    if (properties.clockSkew().isNegative()
        || properties.clockSkew().compareTo(MAX_CLOCK_SKEW) > 0) {
      throw new IllegalStateException("운영 진단 JWT clock skew는 0초 이상 60초 이하여야 합니다.");
    }
  }

  private static void requireHttps(URI uri, String name) {
    if (uri == null
        || !uri.isAbsolute()
        || uri.getHost() == null
        || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(name + "는 userinfo/query/fragment 없는 HTTPS URL이어야 합니다.");
    }
  }
}
