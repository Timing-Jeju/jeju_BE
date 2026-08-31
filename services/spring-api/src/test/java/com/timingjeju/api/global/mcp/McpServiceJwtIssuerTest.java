package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class McpServiceJwtIssuerTest {

  @Test
  void 내부_service_JWT는_RS256과_필수_claim과_5분이하_수명을_사용한다() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var privateKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    McpServiceJwtIssuer issuer =
        new McpServiceJwtIssuer(
            "timing-jeju-spring",
            "timing-jeju-mcp",
            "backend-worker",
            "jeju:mcp:invoke",
            "mcp-key-2026-09",
            privateKey,
            Duration.ofMinutes(2),
            Clock.fixed(now, ZoneOffset.UTC),
            () -> UUID.fromString("10800000-0000-0000-0000-000000000052"));

    SignedJWT token = SignedJWT.parse(issuer.issue());

    assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(token.getHeader().getKeyID()).isEqualTo("mcp-key-2026-09");
    assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo("timing-jeju-spring");
    assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("timing-jeju-mcp");
    assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo("backend-worker");
    assertThat(token.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("jeju:mcp:invoke");
    assertThat(token.getJWTClaimsSet().getJWTID())
        .isEqualTo("10800000-0000-0000-0000-000000000052");
    assertThat(token.getJWTClaimsSet().getIssueTime()).isEqualTo(Date.from(now));
    assertThat(token.getJWTClaimsSet().getExpirationTime())
        .isEqualTo(Date.from(now.plus(Duration.ofMinutes(2))));
  }
}
