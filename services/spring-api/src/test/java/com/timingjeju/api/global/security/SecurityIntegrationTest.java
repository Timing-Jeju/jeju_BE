package com.timingjeju.api.global.security;

import static com.timingjeju.api.global.logging.RequestTraceId.TRACE_ID_HEADER;
import static com.timingjeju.api.support.http.ProblemDetailsAssertions.problemDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=HTTP://LOCALHOST:80,http://localhost:3000,https://app.timing-jeju.test:443,http://127.0.0.1:80,http://[::1]:80,http://trailing.example.:80"
    })
@AutoConfigureMockMvc
@Import(SecurityIntegrationTest.TestEndpointConfig.class)
class SecurityIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String HMAC_KEY = "test-only-hs256-secret-with-at-least-32-bytes";
  @Autowired private MockMvc mockMvc;

  @Test
  void 유효한_사용자_token으로_보호_API에서_현재_사용자를_얻는다() throws Exception {
    UUID userId = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/test/current-user")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(userId))))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        .andExpect(jsonPath("$.userId").value(userId.toString()))
        .andExpect(jsonPath("$.role").value("AUTHENTICATED"));
  }

  @Test
  void token이_없거나_malformed이면_401_계약을_반환한다() throws Exception {
    assertUnauthorized(get("/api/v1/test/current-user"));
    assertUnauthorized(
        get("/api/v1/test/current-user").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"));
  }

  @Test
  void 서명_만료_nbf_issuer_audience_role_sub_검증_실패는_모두_401이다() throws Exception {
    UUID userId = UUID.randomUUID();
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        "another-secret-that-is-at-least-32-bytes",
                        ISSUER,
                        List.of("authenticated"),
                        "authenticated",
                        Instant.now().minusSeconds(600),
                        Instant.now().minusSeconds(300),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        ISSUER,
                        List.of("authenticated"),
                        "authenticated",
                        Instant.now().minusSeconds(600),
                        Instant.now().minusSeconds(31),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        ISSUER,
                        List.of("authenticated"),
                        "authenticated",
                        Instant.now(),
                        Instant.now().plusSeconds(600),
                        Instant.now().plusSeconds(31)))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        "https://other.supabase.co/auth/v1",
                        List.of("authenticated"),
                        "authenticated",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        ISSUER,
                        List.of("anon"),
                        "authenticated",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        ISSUER,
                        List.of("authenticated"),
                        "anon",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(
                    token(
                        userId,
                        HMAC_KEY,
                        ISSUER,
                        List.of("authenticated"),
                        "service_role",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(token("not-a-uuid"))));
  }

  @Test
  void 잘못된_claim_타입과_빈_session_id는_예외를_전파하지_않고_401이다() throws Exception {
    String subject = UUID.randomUUID().toString();
    List<Map<String, Object>> invalidClaims =
        List.of(
            Map.of("sub", subject, "role", "authenticated"),
            Map.of(
                "aud",
                Map.of("unexpected", "authenticated"),
                "sub",
                subject,
                "role",
                "authenticated"),
            Map.of("aud", 7, "sub", subject, "role", "authenticated"),
            Map.of("aud", List.of("authenticated"), "sub", subject, "role", 7),
            Map.of("aud", List.of("authenticated"), "sub", 7, "role", "authenticated"),
            Map.of(
                "aud",
                List.of("authenticated"),
                "sub",
                subject,
                "role",
                "authenticated",
                "session_id",
                7),
            Map.of(
                "aud",
                List.of("authenticated"),
                "sub",
                subject,
                "role",
                "authenticated",
                "session_id",
                ""));

    for (Map<String, Object> claims : invalidClaims) {
      assertUnauthorized(
          get("/api/v1/test/current-user")
              .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRawClaims(claims))));
    }
  }

  @Test
  void exp가_누락_null_비숫자_변환불가이면_filter_chain은_401을_반환한다() throws Exception {
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutExpiration())));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRawExpiration(null))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRawExpiration("not-a-number"))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION, bearer(tokenWithRawExpiration(Map.of("seconds", 1)))));
  }

  @Test
  void exp가_과거이거나_epoch_경계이면_filter_chain은_401을_반환한다() throws Exception {
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(
                HttpHeaders.AUTHORIZATION,
                bearer(tokenWithRawExpiration(Instant.now().minusSeconds(31).getEpochSecond()))));
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRawExpiration(0L))));
  }

  @Test
  void actuator와_활성화된_API_문서는_공개하고_API는_보호한다() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    assertUnauthorized(get("/api/v1/test/current-user"));
  }

  @Test
  void 인증된_사용자가_허용되지_않은_경로를_호출하면_403_계약을_반환한다() throws Exception {
    mockMvc
        .perform(
            get("/not-allowed").header(HttpHeaders.AUTHORIZATION, bearer(token(UUID.randomUUID()))))
        .andExpectAll(
            problemDetails(
                403,
                "https://api.timing-jeju.example/problems/auth-access-denied",
                "접근이 거부되었습니다.",
                "AUTH_ACCESS_DENIED",
                "접근 권한이 없습니다."));
  }

  @Test
  void 허용한_CORS_origin만_preflight를_통과한다() throws Exception {
    assertCorsPreflightAllowed("http://127.0.0.1");
    assertCorsPreflightAllowed("http://[::1]");
    assertCorsPreflightAllowed("http://trailing.example.");

    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .with(
                    request -> {
                      request.setServerName("api.timing-jeju.test");
                      return request;
                    })
                .header(HttpHeaders.ORIGIN, "http://localhost")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .header(HttpHeaders.ORIGIN, "https://app.timing-jeju.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.timing-jeju.test"));

    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpectAll(
            problemDetails(
                403,
                "https://api.timing-jeju.example/problems/auth-access-denied",
                "접근이 거부되었습니다.",
                "AUTH_ACCESS_DENIED",
                "접근 권한이 없습니다."))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "TRACE"))
        .andExpectAll(
            problemDetails(
                403,
                "https://api.timing-jeju.example/problems/auth-access-denied",
                "접근이 거부되었습니다.",
                "AUTH_ACCESS_DENIED",
                "접근 권한이 없습니다."));
    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Forbidden"))
        .andExpectAll(
            problemDetails(
                403,
                "https://api.timing-jeju.example/problems/auth-access-denied",
                "접근이 거부되었습니다.",
                "AUTH_ACCESS_DENIED",
                "접근 권한이 없습니다."));

    mockMvc
        .perform(
            get("/api/v1/auth/social/providers").header(HttpHeaders.ORIGIN, "https://evil.example"))
        .andExpectAll(
            problemDetails(
                403,
                "https://api.timing-jeju.example/problems/auth-access-denied",
                "접근이 거부되었습니다.",
                "AUTH_ACCESS_DENIED",
                "접근 권한이 없습니다."));
  }

  @Test
  void 허용된_CORS_성공_응답은_trace_id_header를_브라우저에_노출한다() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/auth/social/providers")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, TRACE_ID_HEADER))
        .andExpect(header().exists(TRACE_ID_HEADER));
  }

  @Test
  void 허용된_CORS_오류_응답도_origin과_trace_id_header를_보존한다() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/test/current-user").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
        .andExpectAll(
            problemDetails(
                401,
                "https://api.timing-jeju.example/problems/auth-token-invalid",
                "인증에 실패했습니다.",
                "AUTH_TOKEN_INVALID",
                "인증 토큰이 유효하지 않습니다."))
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, TRACE_ID_HEADER));
  }

  private void assertCorsPreflightAllowed(String origin) throws Exception {
    mockMvc
        .perform(
            options("/api/v1/test/current-user")
                .with(
                    request -> {
                      request.setServerName("api.timing-jeju.test");
                      return request;
                    })
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void bearer_scheme_대소문자_변형과_중복_Authorization_header를_거부한다() throws Exception {
    String token = token(UUID.randomUUID());
    mockMvc
        .perform(
            get("/api/v1/test/current-user").header(HttpHeaders.AUTHORIZATION, "bearer " + token))
        .andExpect(status().isOk());
    assertUnauthorized(
        get("/api/v1/test/current-user")
            .header(HttpHeaders.AUTHORIZATION, bearer(token), bearer(token)));
  }

  private void assertUnauthorized(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpectAll(
            problemDetails(
                401,
                "https://api.timing-jeju.example/problems/auth-token-invalid",
                "인증에 실패했습니다.",
                "AUTH_TOKEN_INVALID",
                "인증 토큰이 유효하지 않습니다."));
  }

  private String token(UUID userId) throws Exception {
    return token(userId.toString());
  }

  private String token(String subject) throws Exception {
    return token(
        subject,
        HMAC_KEY,
        ISSUER,
        List.of("authenticated"),
        "authenticated",
        Instant.now(),
        Instant.now().plusSeconds(300),
        null);
  }

  private String token(
      UUID userId,
      String secret,
      String issuer,
      List<String> audience,
      String role,
      Instant issuedAt,
      Instant expiresAt,
      Instant notBefore)
      throws Exception {
    return token(userId.toString(), secret, issuer, audience, role, issuedAt, expiresAt, notBefore);
  }

  private String token(
      String subject,
      String secret,
      String issuer,
      List<String> audience,
      String role,
      Instant issuedAt,
      Instant expiresAt,
      Instant notBefore)
      throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .claim("role", role)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt));
    if (notBefore != null) {
      claims.notBeforeTime(Date.from(notBefore));
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private String tokenWithRawClaims(Map<String, Object> rawClaims) throws Exception {
    Instant now = Instant.now();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("iat", Date.from(now));
    claims.put("exp", Date.from(now.plusSeconds(300)));
    claims.putAll(rawClaims);
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
    claims.forEach(builder::claim);
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
    jwt.sign(new MACSigner(HMAC_KEY.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private String tokenWithoutExpiration() throws Exception {
    return signedRawPayload(baseRawClaims());
  }

  private String tokenWithRawExpiration(Object expiration) throws Exception {
    Map<String, Object> claims = baseRawClaims();
    claims.put("exp", expiration);
    return signedRawPayload(claims);
  }

  private Map<String, Object> baseRawClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", List.of("authenticated"));
    claims.put("sub", UUID.randomUUID().toString());
    claims.put("role", "authenticated");
    claims.put("iat", Instant.now().getEpochSecond());
    return claims;
  }

  private String signedRawPayload(Map<String, Object> claims) throws Exception {
    JWSObject jwt =
        new JWSObject(
            new JWSHeader(JWSAlgorithm.HS256), new Payload(JSONObjectUtils.toJSONString(claims)));
    jwt.sign(new MACSigner(HMAC_KEY.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  static class TestEndpointConfig {

    @Bean
    TestCurrentUserController testCurrentUserController(CurrentUserAccessor currentUserAccessor) {
      return new TestCurrentUserController(currentUserAccessor);
    }
  }

  @RestController
  static class TestCurrentUserController {

    private final CurrentUserAccessor currentUserAccessor;

    TestCurrentUserController(CurrentUserAccessor currentUserAccessor) {
      this.currentUserAccessor = currentUserAccessor;
    }

    @GetMapping("/api/v1/test/current-user")
    CurrentUser currentUser() {
      return currentUserAccessor.getRequired();
    }
  }
}
