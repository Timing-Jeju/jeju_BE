package com.timingjeju.api.global.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local",
      "app.security.jwt.issuer=https://project.supabase.co/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.secret=",
      "app.security.cors.allowed-origins=http://localhost:3000"
    })
@AutoConfigureMockMvc
@Import(JwksRotationSecurityIntegrationTest.TestEndpointConfig.class)
class JwksRotationSecurityIntegrationTest {

  private static final String ISSUER = "https://project.supabase.co/auth/v1";
  private static final RSAKey OLD_KEY = key("rotation-filter-old");
  private static final RSAKey NEW_KEY = key("rotation-filter-new");
  private static final RSAKey UNSEEN_KEY = key("rotation-filter-unseen");
  private static final AtomicReference<JWKSet> CURRENT_JWKS =
      new AtomicReference<>(new JWKSet(OLD_KEY.toPublicJWK()));
  private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
  private static final AtomicBoolean ENDPOINT_UNAVAILABLE = new AtomicBoolean();
  private static final HttpServer JWKS_SERVER = startJwksServer();

  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void jwksUrl(DynamicPropertyRegistry registry) {
    registry.add(
        "app.security.jwt.jwks-url",
        () ->
            "http://127.0.0.1:"
                + JWKS_SERVER.getAddress().getPort()
                + "/auth/v1/.well-known/jwks.json");
  }

  @AfterAll
  static void stopServer() {
    JWKS_SERVER.stop(0);
  }

  @Test
  void key_rotation과_JWKS_장애에서_cache와_unknown_kid를_fail_closed로_처리한다() throws Exception {
    mockMvc
        .perform(get("/api/v1/test/jwks").header(HttpHeaders.AUTHORIZATION, bearer(token(OLD_KEY))))
        .andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(REQUEST_COUNT).hasValue(1);

    CURRENT_JWKS.set(new JWKSet(List.of(OLD_KEY.toPublicJWK(), NEW_KEY.toPublicJWK())));
    mockMvc
        .perform(get("/api/v1/test/jwks").header(HttpHeaders.AUTHORIZATION, bearer(token(NEW_KEY))))
        .andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(REQUEST_COUNT).hasValue(2);

    ENDPOINT_UNAVAILABLE.set(true);
    mockMvc
        .perform(get("/api/v1/test/jwks").header(HttpHeaders.AUTHORIZATION, bearer(token(OLD_KEY))))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/test/jwks").header(HttpHeaders.AUTHORIZATION, bearer(token(UNSEEN_KEY))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
    org.assertj.core.api.Assertions.assertThat(REQUEST_COUNT).hasValue(3);
  }

  private static HttpServer startJwksServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/auth/v1/.well-known/jwks.json",
          exchange -> {
            REQUEST_COUNT.incrementAndGet();
            if (ENDPOINT_UNAVAILABLE.get()) {
              exchange.sendResponseHeaders(503, -1);
              exchange.close();
              return;
            }
            byte[] response = CURRENT_JWKS.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=300");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static RSAKey key(String keyId) {
    try {
      return new RSAKeyGenerator(2048).keyID(keyId).generate();
    } catch (JOSEException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private String token(RSAKey signingKey) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(UUID.randomUUID().toString())
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

  private String bearer(String token) {
    return "Bearer " + token;
  }

  static class TestEndpointConfig {

    @Bean
    JwksTestController jwksTestController() {
      return new JwksTestController();
    }
  }

  @RestController
  static class JwksTestController {

    @GetMapping("/api/v1/test/jwks")
    String jwks() {
      return "ok";
    }
  }
}
