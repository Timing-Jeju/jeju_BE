package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("unit")
class OpsJwtValidatorTest {

  @Test
  void ops_audience와_operator_role인_service_JWT만_허용한다() {
    OpsJwtValidator validator = new OpsJwtValidator("timing-jeju-ops");

    assertThat(validator.validate(jwt(List.of("timing-jeju-ops"), "operator")).hasErrors())
        .isFalse();
    assertThat(validator.validate(jwt(List.of("authenticated"), "operator")).hasErrors()).isTrue();
    assertThat(
            validator
                .validate(jwt(List.of("timing-jeju-ops", "authenticated"), "operator"))
                .hasErrors())
        .isTrue();
    assertThat(validator.validate(jwt(List.of("timing-jeju-ops"), "authenticated")).hasErrors())
        .isTrue();
  }

  @Test
  void 누락되거나_잘못된_claim_타입은_fail_closed다() {
    OpsJwtValidator validator = new OpsJwtValidator("timing-jeju-ops");
    Jwt missingRole =
        new Jwt(
            "token",
            Instant.parse("2026-09-02T00:00:00Z"),
            Instant.parse("2026-09-02T00:05:00Z"),
            Map.of("alg", "RS256"),
            Map.of("aud", List.of("timing-jeju-ops")));

    assertThat(validator.validate(missingRole).hasErrors()).isTrue();
  }

  private static Jwt jwt(List<String> audience, String role) {
    Instant expiresAt = Instant.parse("2026-09-02T00:05:00Z");
    return new Jwt(
        "token",
        Instant.parse("2026-09-02T00:00:00Z"),
        expiresAt,
        Map.of("alg", "RS256"),
        Map.of("aud", audience, "role", role, "exp", expiresAt));
  }
}
