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
import com.timingjeju.api.domain.places.exception.PlaceDetailUnavailableException;
import com.timingjeju.api.domain.places.exception.PlaceSearchUnavailableException;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import com.timingjeju.api.domain.places.model.PlaceSearchPosition;
import com.timingjeju.api.domain.places.model.PlaceSearchRow;
import com.timingjeju.api.domain.places.repository.PlaceDetailRepository;
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
import org.junit.jupiter.api.BeforeEach;
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
  @Autowired private FakePlaceDetailRepository detailRepository;

  @BeforeEach
  void resetRepository() {
    repository.failure.set(null);
    detailRepository.failure.set(null);
    detailRepository.lastUser.set(Optional.empty());
  }

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
    mvc.perform(get("/api/v1/places/20000000-0000-0000-0000-000000000002/nested-regression"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/security-regression")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalid_bearer는_optional_endpoint에서도_INVALID_ACCESS_TOKEN_401이다() throws Exception {
    mvc.perform(get("/api/v1/places").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

    mvc.perform(
            get("/api/v1/places/20000000-0000-0000-0000-000000000002")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
  }

  @Test
  void exact_GET_place_detail은_optional_auth이고_익명_saved_shape를_고정한다() throws Exception {
    mvc.perform(get("/api/v1/places/20000000-0000-0000-0000-000000000002"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved.value").value(false))
        .andExpect(jsonPath("$.saved.memo").value((Object) null))
        .andExpect(jsonPath("$.saved.tags").isEmpty())
        .andExpect(jsonPath("$.images").isEmpty())
        .andExpect(jsonPath("$.nearbyStops").isEmpty());
  }

  @Test
  void detail의_noncanonical_UUID와_missing_place는_닫힌_Problem_Details다() throws Exception {
    mvc.perform(get("/api/v1/places/20000000-0000-0000-0000-00000000000A"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"));

    mvc.perform(get("/api/v1/places/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
  }

  @Test
  void detail의_typed_DB_failure는_raw_message없는_503이다() throws Exception {
    detailRepository.failure.set(new PlaceDetailUnavailableException());

    mvc.perform(get("/api/v1/places/20000000-0000-0000-0000-000000000002"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PLACE_DATA_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.message").doesNotExist())
        .andExpect(jsonPath("$.sql").doesNotExist());
  }

  @Test
  void valid_JWT의_canonical_sub만_personalization에_전달한다() throws Exception {
    UUID userId = UUID.randomUUID();

    mvc.perform(get("/api/v1/places").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId)))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/places/20000000-0000-0000-0000-000000000002")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId)))
        .andExpect(status().isOk());

    assertThat(repository.lastUser.get()).contains(userId);
    assertThat(detailRepository.lastUser.get()).contains(userId);
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

  @Test
  void query는_trim후_길이1과_100을_MVC에서_허용한다() throws Exception {
    mvc.perform(get("/api/v1/places").queryParam("query", "  가  ")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/places").queryParam("query", "  " + "가".repeat(100) + "  "))
        .andExpect(status().isOk());
  }

  @Test
  void repository_typed_failure는_raw_message없이_traceId가_있는_503이다() throws Exception {
    repository.failure.set(new PlaceSearchUnavailableException());

    mvc.perform(get("/api/v1/places"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PLACE_DATA_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.detail").value("잠시 후 다시 시도해 주세요."))
        .andExpect(jsonPath("$.sql").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());
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
    FakePlaceDetailRepository fakePlaceDetailRepository() {
      return new FakePlaceDetailRepository();
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
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    @Override
    public List<PlaceSearchRow> search(
        PlacesListQuery query, PlaceSearchPosition after, Optional<UUID> currentUserId) {
      if (failure.get() != null) {
        throw failure.get();
      }
      lastUser.set(currentUserId);
      return List.of();
    }
  }

  static final class FakePlaceDetailRepository implements PlaceDetailRepository {
    private static final UUID MISSING = new UUID(0, 0);
    private final AtomicReference<Optional<UUID>> lastUser =
        new AtomicReference<>(Optional.empty());
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    @Override
    public Optional<PlaceDetailSnapshot> find(UUID placeId, Optional<UUID> currentUserId) {
      if (failure.get() != null) {
        throw failure.get();
      }
      lastUser.set(currentUserId);
      if (MISSING.equals(placeId)) {
        return Optional.empty();
      }
      return Optional.of(
          new PlaceDetailSnapshot(
              placeId,
              "126435",
              "성산일출봉",
              "VE",
              "seongsan",
              null,
              null,
              33.458,
              126.941,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              List.of(),
              false,
              null,
              List.of()));
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
