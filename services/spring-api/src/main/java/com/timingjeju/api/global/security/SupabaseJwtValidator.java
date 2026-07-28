package com.timingjeju.api.global.security;

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
    if (!jwt.getAudience().contains(expectedAudience)) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    if (!"authenticated".equals(jwt.getClaimAsString("role"))) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    if (!isUuid(jwt.getSubject()) || !isOptionalUuid(jwt.getClaimAsString("session_id"))) {
      return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
    return OAuth2TokenValidatorResult.success();
  }

  private boolean isOptionalUuid(String value) {
    return value == null || value.isBlank() || isUuid(value);
  }

  private boolean isUuid(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
