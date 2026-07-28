package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
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
  void exp가_없거나_변환된_Instant_타입이_아니면_실패한다() {
    String subject = UUID.randomUUID().toString();
    assertInvalidClaimsWithoutDefaultExpiration(
        Map.of("aud", List.of("authenticated"), "role", "authenticated", "sub", subject));
    assertInvalidClaimsWithoutDefaultExpiration(
        Map.of(
            "aud", List.of("authenticated"), "role", "authenticated", "sub", subject, "exp", 123));
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

  @Test
  void role_sub_session_id의_null과_비문자열은_실패한다() {
    String subject = UUID.randomUUID().toString();
    assertInvalidClaims(Map.of("aud", List.of("authenticated"), "role", 7, "sub", subject));
    assertInvalidClaims(Map.of("aud", List.of("authenticated"), "role", "authenticated", "sub", 7));
    assertInvalidClaims(
        Map.of(
            "aud",
            List.of("authenticated"),
            "role",
            "authenticated",
            "sub",
            subject,
            "session_id",
            7));

    for (String claim : List.of("role", "sub", "session_id")) {
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("aud", List.of("authenticated"));
      claims.put("role", "authenticated");
      claims.put("sub", subject);
      claims.put(claim, null);
      assertInvalidClaims(claims);
    }
  }

  @Test
  void 빈값과_canonical_형식이_아닌_UUID는_실패한다() {
    String subject = UUID.randomUUID().toString();
    assertInvalidClaims(
        Map.of(
            "aud",
            List.of("authenticated"),
            "role",
            "authenticated",
            "sub",
            subject,
            "session_id",
            ""));
    assertInvalidClaims(
        Map.of(
            "aud",
            List.of("authenticated"),
            "role",
            "authenticated",
            "sub",
            subject.toUpperCase()));
  }

  private void assertInvalidClaims(Map<String, Object> claims) {
    Instant now = Instant.now();
    Map<String, Object> claimsWithExpiration = new LinkedHashMap<>(claims);
    claimsWithExpiration.putIfAbsent("exp", now.plusSeconds(300));
    Jwt jwt =
        new Jwt("token", now, now.plusSeconds(300), Map.of("alg", "HS256"), claimsWithExpiration);
    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }

  private void assertInvalidClaimsWithoutDefaultExpiration(Map<String, Object> claims) {
    Instant now = Instant.now();
    Jwt jwt = new Jwt("token", now, now.plusSeconds(300), Map.of("alg", "HS256"), claims);
    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }

  private Jwt jwt(String role, String subject, List<String> audience) {
    Instant now = Instant.now();
    return new Jwt(
        "token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "HS256"),
        Map.of(
            "iss",
            ISSUER,
            "aud",
            audience,
            "role",
            role,
            "sub",
            subject,
            "exp",
            now.plusSeconds(300)));
  }
}
