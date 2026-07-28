package com.timingjeju.api.global.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class SupabaseJwtValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID_CLAIMS =
      new OAuth2Error("invalid_token", "필수 JWT claim이 유효하지 않습니다.", null);

  private final String expectedAudience;

  public SupabaseJwtValidator(String expectedAudience) {
    this.expectedAudience = expectedAudience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    Map<String, Object> claims = jwt.getClaims();
    if (!hasExpectedAudience(claims.get("aud"))) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    if (!(claims.get("role") instanceof String role) || !"authenticated".equals(role)) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    if (!(claims.get("sub") instanceof String subject) || !isUuid(subject)) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    if (claims.containsKey("session_id")
        && (!(claims.get("session_id") instanceof String sessionId) || !isUuid(sessionId))) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    return OAuth2TokenValidatorResult.success();
  }

  private boolean hasExpectedAudience(Object audience) {
    if (audience instanceof String value) {
      return expectedAudience.equals(value);
    }
    if (audience instanceof Collection<?> values) {
      return values.stream().allMatch(String.class::isInstance)
          && values.contains(expectedAudience);
    }
    return false;
  }

  private boolean isUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
