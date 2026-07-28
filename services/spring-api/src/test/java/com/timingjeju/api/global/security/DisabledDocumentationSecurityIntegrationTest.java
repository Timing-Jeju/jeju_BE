package com.timingjeju.api.global.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "springdoc.api-docs.enabled=false",
      "springdoc.swagger-ui.enabled=false"
    })
@AutoConfigureMockMvc
class DisabledDocumentationSecurityIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String HMAC_KEY = "test-only-hs256-secret-with-at-least-32-bytes";

  @Autowired private MockMvc mockMvc;

  @Test
  void 비활성화된_OpenAPI와_Swagger_경로는_명시적으로_거부한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
  }

  @Test
  void Swagger_비활성_환경의_401과_403은_UTF8_JSON_계약을_유지한다() throws Exception {
    mockMvc
        .perform(get("/api/v1/protected"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
        .andExpect(jsonPath("$.message").value("인증 토큰이 유효하지 않습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));

    mockMvc
        .perform(get("/not-allowed").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
        .andExpect(status().isForbidden())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"))
        .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
  }

  @Test
  void 보안_오류_writer는_Boot_Jackson3_mapper_주입_계약을_사용한다() {
    org.assertj.core.api.Assertions.assertThat(
            SecurityErrorResponseWriter.class.getConstructors()[0].getParameterTypes()[0].getName())
        .isEqualTo("tools.jackson.databind.ObjectMapper");
  }

  private String validToken() throws Exception {
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
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(HMAC_KEY.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
