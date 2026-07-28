package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@Tag("integration")
class JwksCacheIntegrationTest {

  @Test
  void unknown_kid는_JWKS를_재조회하고_cache된_key는_endpoint_장애_중에도_재사용한다() throws Exception {
    RSAKey oldKey = new RSAKeyGenerator(2048).keyID("rotation-key-old").generate();
    RSAKey newKey = new RSAKeyGenerator(2048).keyID("rotation-key-new").generate();
    RSAKey unseenKey = new RSAKeyGenerator(2048).keyID("rotation-key-unseen").generate();
    AtomicReference<JWKSet> currentJwkSet = new AtomicReference<>(new JWKSet(oldKey.toPublicJWK()));
    AtomicInteger requestCount = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/auth/v1/.well-known/jwks.json",
        exchange -> {
          requestCount.incrementAndGet();
          byte[] response = currentJwkSet.get().toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("Cache-Control", "public, max-age=300");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();

    try {
      String issuer = "https://project.supabase.co/auth/v1";
      URI jwksUri =
          URI.create(
              "http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/auth/v1/.well-known/jwks.json");
      SupabaseJwtProperties properties =
          new SupabaseJwtProperties(
              JwtDecoderMode.JWKS,
              URI.create(issuer),
              "authenticated",
              jwksUri,
              "",
              Duration.ofSeconds(30));

      JwtDecoder decoder =
          new SupabaseJwtDecoderFactory(properties, SecurityRuntimeEnvironment.LOCAL).create();
      assertThat(requestCount).hasValue(0);

      decoder.decode(token(oldKey, issuer, UUID.randomUUID()));
      assertThat(requestCount).hasValue(1);

      currentJwkSet.set(new JWKSet(List.of(oldKey.toPublicJWK(), newKey.toPublicJWK())));
      decoder.decode(token(newKey, issuer, UUID.randomUUID()));
      assertThat(requestCount).hasValue(2);

      server.stop(0);
      decoder.decode(token(oldKey, issuer, UUID.randomUUID()));
      assertThat(requestCount).hasValue(2);
      assertThatThrownBy(() -> decoder.decode(token(unseenKey, issuer, UUID.randomUUID())))
          .isInstanceOf(JwtException.class);
    } finally {
      server.stop(0);
    }
  }

  private String token(RSAKey signingKey, String issuer, UUID subject) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(List.of("authenticated"))
            .subject(subject.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }
}
