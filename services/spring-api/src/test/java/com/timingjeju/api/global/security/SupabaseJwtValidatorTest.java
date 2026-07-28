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
class SupabaseJwtValidatorTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";

  private final SupabaseJwtValidator validator = new SupabaseJwtValidator("authenticated");

  @Test
  void authenticated_role과_UUID_sub이면_성공한다() {
    assertThat(
            validator
                .validate(
                    jwt("authenticated", UUID.randomUUID().toString(), List.of("authenticated")))
                .hasErrors())
        .isFalse();
  }

  @Test
  void audience가_다르면_실패한다() {
    assertThat(
            validator
                .validate(jwt("authenticated", UUID.randomUUID().toString(), List.of("anon")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void anon과_service_role은_실패한다() {
    assertThat(
            validator
                .validate(jwt("anon", UUID.randomUUID().toString(), List.of("authenticated")))
                .hasErrors())
        .isTrue();
    assertThat(
            validator
                .validate(
                    jwt("service_role", UUID.randomUUID().toString(), List.of("authenticated")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void UUID가_아닌_sub는_실패한다() {
    assertThat(
            validator
                .validate(jwt("authenticated", "not-a-uuid", List.of("authenticated")))
                .hasErrors())
        .isTrue();
    assertThat(
            validator
                .validate(jwt("authenticated", "1-1-1-1-1", List.of("authenticated")))
                .hasErrors())
        .isTrue();
  }

  @Test
  void sub나_role이_없으면_실패한다() {
    Jwt missingClaims =
        new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "HS256"),
            Map.of("iss", ISSUER, "aud", List.of("authenticated")));

    assertThat(validator.validate(missingClaims).hasErrors()).isTrue();
  }

  private Jwt jwt(String role, String subject, List<String> audience) {
    Instant now = Instant.now();
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "HS256"),
        Map.of("iss", ISSUER, "aud", audience, "role", role, "sub", subject));
  }
}
