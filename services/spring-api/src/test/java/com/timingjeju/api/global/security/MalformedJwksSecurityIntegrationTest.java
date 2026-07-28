package com.timingjeju.api.global.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
class MalformedJwksSecurityIntegrationTest {

  private static final String ISSUER = "https://project.supabase.co/auth/v1";
  private static final String MALFORMED_JWKS = "not-json";
  private static final RSAKey SIGNING_KEY = key();
  private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
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
  void HTTP_200_invalid_JWKS_payload는_토큰오류로_숨기지_않고_안전한_500을_반환한다() throws Exception {
    String token = token();

    mockMvc
        .perform(get("/api/v1/provider-fault").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("AUTH_INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("인증 처리 중 내부 오류가 발생했습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")))
        .andExpect(content().string(not(containsString(MALFORMED_JWKS))))
        .andExpect(content().string(not(containsString(SIGNING_KEY.getKeyID()))))
        .andExpect(content().string(not(containsString(token))));

    org.assertj.core.api.Assertions.assertThat(REQUEST_COUNT).hasValue(1);
  }

  private static HttpServer startJwksServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/auth/v1/.well-known/jwks.json",
          exchange -> {
            REQUEST_COUNT.incrementAndGet();
            byte[] response = MALFORMED_JWKS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

  private static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("malformed-provider-key").generate();
    } catch (JOSEException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private String token() throws Exception {
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
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
            claims);
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return jwt.serialize();
  }
}
