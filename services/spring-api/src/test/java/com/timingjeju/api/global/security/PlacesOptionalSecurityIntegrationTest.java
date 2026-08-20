package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.application.staypolicy.StayPolicySubject;
import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.model.PlaceSearchPosition;
import com.timingjeju.api.domain.places.model.PlaceSearchRow;
import com.timingjeju.api.domain.places.repository.PlaceSearchRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
@Import(PlacesOptionalSecurityIntegrationTest.Fakes.class)
class PlacesOptionalSecurityIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();

  @Autowired private MockMvc mvc;
  @Autowired private FakePlaceSearchRepository repository;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @Test
  void exact_GET_places는_익명_200이고_다른_method와_API는_인증이_필요하다() throws Exception {
    mvc.perform(get("/api/v1/places"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());

    mvc.perform(post("/api/v1/places")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/security-regression")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalid_bearer는_optional_endpoint에서도_INVALID_ACCESS_TOKEN_401이다() throws Exception {
    mvc.perform(get("/api/v1/places").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
  }

  @Test
  void valid_JWT의_canonical_sub만_personalization에_전달한다() throws Exception {
    UUID userId = UUID.randomUUID();

    mvc.perform(get("/api/v1/places").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId)))
        .andExpect(status().isOk());

    assertThat(repository.lastUser.get()).contains(userId);
  }

  @Test
  void 익명_savedOnly는_401이다() throws Exception {
    mvc.perform(get("/api/v1/places").queryParam("savedOnly", "true"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void query_type과_size_range_오류는_닫힌_INVALID_QUERY_PARAMETER다() throws Exception {
    mvc.perform(get("/api/v1/places").queryParam("size", "not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
    mvc.perform(get("/api/v1/places").queryParam("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  private static String token(UUID userId) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(userId.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    @Bean
    @Primary
    FakePlaceSearchRepository fakePlaceSearchRepository() {
      return new FakePlaceSearchRepository();
    }

    @Bean
    @Primary
    StayPolicyResolver fakeStayPolicyResolver() {
      return new StayPolicyResolver() {
        @Override
        public RecommendedStay resolve(UUID placeId, String category) {
          return RecommendedStay.unavailable();
        }

        @Override
        public Map<UUID, RecommendedStay> resolveAll(List<StayPolicySubject> subjects) {
          return Map.of();
        }
      };
    }

    @Bean
    SecurityRegressionController securityRegressionController() {
      return new SecurityRegressionController();
    }
  }

  static final class FakePlaceSearchRepository implements PlaceSearchRepository {
    private final AtomicReference<Optional<UUID>> lastUser =
        new AtomicReference<>(Optional.empty());

    @Override
    public List<PlaceSearchRow> search(
        PlacesListQuery query, PlaceSearchPosition after, Optional<UUID> currentUserId) {
      lastUser.set(currentUserId);
      return List.of();
    }
  }

  @RestController
  static class SecurityRegressionController {
    @GetMapping("/api/v1/security-regression")
    Map<String, Boolean> get() {
      return Map.of("ok", true);
    }
  }
}
