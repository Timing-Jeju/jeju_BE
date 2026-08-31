package com.timingjeju.api.global.mcp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class McpServiceJwtIssuer {
  private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
  private final String issuer;
  private final String audience;
  private final String subject;
  private final String scope;
  private final String keyId;
  private final RSAPrivateKey privateKey;
  private final Duration lifetime;
  private final Clock clock;
  private final Supplier<UUID> jtiSupplier;

  public McpServiceJwtIssuer(
      String issuer,
      String audience,
      String subject,
      String scope,
      String keyId,
      RSAPrivateKey privateKey,
      Duration lifetime,
      Clock clock,
      Supplier<UUID> jtiSupplier) {
    this.issuer = requireText(issuer);
    this.audience = requireText(audience);
    this.subject = requireText(subject);
    this.scope = requireText(scope);
    this.keyId = requireText(keyId);
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey는 필수입니다.");
    this.lifetime = Objects.requireNonNull(lifetime, "lifetime은 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.jtiSupplier = Objects.requireNonNull(jtiSupplier, "jtiSupplier는 필수입니다.");
    if (privateKey.getModulus().bitLength() < 2048) {
      throw new IllegalArgumentException("RSA key는 2048 bit 이상이어야 합니다.");
    }
    if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
      throw new IllegalArgumentException("service JWT lifetime은 1초 이상 5분 이하여야 합니다.");
    }
  }

  public String issue() {
    Instant issuedAt = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(subject)
            .claim("scope", scope)
            .jwtID(jtiSupplier.get().toString())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(lifetime)))
            .build();
    SignedJWT token =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
    try {
      token.sign(new RSASSASigner(privateKey));
      return token.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException("MCP service JWT 서명에 실패했습니다.", exception);
    }
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("JWT 설정은 필수입니다.");
    return value;
  }
}
