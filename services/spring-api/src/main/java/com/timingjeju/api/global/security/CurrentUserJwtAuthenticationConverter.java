package com.timingjeju.api.global.security;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUserJwtAuthenticationConverter
    implements Converter<Jwt, CurrentUserAuthentication> {

  @Override
  public CurrentUserAuthentication convert(Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    UUID sessionId = nullableUuid(jwt.getClaimAsString("session_id"));
    CurrentUser currentUser = new CurrentUser(userId, AuthenticatedRole.AUTHENTICATED, sessionId);
    return new CurrentUserAuthentication(currentUser);
  }

  private UUID nullableUuid(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }
}
