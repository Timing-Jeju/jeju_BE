package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("unit")
class CurrentUserJwtAuthenticationConverterTest {

  @Test
  void 검증된_JWT를_Spring_Jwt가_없는_현재_사용자로_변환한다() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();
    Jwt jwt =
        new Jwt(
            "token",
            now,
            now.plusSeconds(300),
            Map.of("alg", "HS256"),
            Map.of(
                "sub", userId.toString(),
                "aud", List.of("authenticated"),
                "role", "authenticated",
                "session_id", sessionId.toString()));

    CurrentUserAuthentication authentication =
        new CurrentUserJwtAuthenticationConverter().convert(jwt);

    assertThat(authentication.getPrincipal())
        .isEqualTo(new CurrentUser(userId, AuthenticatedRole.AUTHENTICATED, sessionId));
    assertThat(authentication.getCredentials()).isNull();
    assertThat(authentication.getPrincipal()).isNotInstanceOf(Jwt.class);
    assertThat(authentication.isAuthenticated()).isTrue();
  }

  @Test
  void session_id가_없어도_현재_사용자를_만든다() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    Jwt jwt =
        new Jwt(
            "token",
            now,
            now.plusSeconds(300),
            Map.of("alg", "HS256"),
            Map.of(
                "sub", userId.toString(),
                "aud", List.of("authenticated"),
                "role", "authenticated"));

    CurrentUserAuthentication authentication =
        new CurrentUserJwtAuthenticationConverter().convert(jwt);

    assertThat(authentication.getPrincipal().sessionId()).isNull();
  }
}
