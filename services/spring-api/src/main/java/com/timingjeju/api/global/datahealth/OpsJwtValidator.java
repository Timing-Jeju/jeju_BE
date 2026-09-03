package com.timingjeju.api.global.datahealth;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class OpsJwtValidator implements OAuth2TokenValidator<Jwt> {
  private static final OAuth2Error INVALID_CLAIMS =
      new OAuth2Error("invalid_token", "운영 진단 JWT claim이 유효하지 않습니다.", null);

  private final String expectedAudience;

  public OpsJwtValidator(String expectedAudience) {
    Objects.requireNonNull(expectedAudience, "audience는 필수입니다.");
    if (expectedAudience.isBlank() || !expectedAudience.equals(expectedAudience.trim())) {
      throw new IllegalArgumentException("audience가 올바르지 않습니다.");
    }
    this.expectedAudience = expectedAudience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    Map<String, Object> claims = jwt.getClaims();
    if (!(claims.get("exp") instanceof Instant)
        || !hasExpectedAudience(claims.get("aud"))
        || !(claims.get("role") instanceof String role)
        || !"operator".equals(role)) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    return OAuth2TokenValidatorResult.success();
  }

  private boolean hasExpectedAudience(Object audience) {
    if (audience instanceof String value) {
      return expectedAudience.equals(value);
    }
    if (audience instanceof Collection<?> values) {
      return values.size() == 1
          && values.stream().allMatch(String.class::isInstance)
          && values.contains(expectedAudience);
    }
    return false;
  }
}
