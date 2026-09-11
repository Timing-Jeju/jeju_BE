package com.timingjeju.api.global.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.domain.trip.exception.TripPlacePreferencesProblemDefinitions;
import com.timingjeju.api.domain.trip.exception.TripPreferencesProblemDefinitions;
import com.timingjeju.api.global.error.ProblemCodeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=future-contract-openapi"
    })
@AutoConfigureMockMvc
class FrontendOpenApiFutureContractIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @org.springframework.test.context.DynamicPropertySource
  static void jwtKey(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  private static String randomKey() {
    byte[] bytes = new byte[48];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getEncoder().encodeToString(bytes);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JsonMapper mapper;
  @Autowired private ProblemCodeRegistry problems;
  @Autowired private TripPlacePreferencesProblemDefinitions placePreferences;
  @Autowired private TripPreferencesProblemDefinitions preferences;
  @MockitoBean private FrontendOpenApiCustomizer customizer;

  @BeforeEach
  void 미래_계약_resource만_주입하고_실제_customizer를_실행한다() {
    var fixture = new FrontendOpenApiReadinessTest();
    var actual =
        new FrontendOpenApiCustomizer(
            mapper, problems, placePreferences, preferences, path -> futureResource(fixture, path));
    doAnswer(
            invocation -> {
              actual.customise(invocation.getArgument(0));
              return null;
            })
        .when(customizer)
        .customise(any());
  }

  @SuppressWarnings("unchecked")
  private java.util.Map<String, Object> futureResource(
      FrontendOpenApiReadinessTest fixture, String path) {
    var resource = fixture.resource(path, "not-ready");
    if (path.equals("/rest/catalog.json")) {
      for (var row :
          (java.util.List<java.util.Map<String, Object>>) resource.get("domainContracts")) {
        if (java.util.Set.of("accommodations", "schedules").contains(row.get("domain"))) {
          ((java.util.Map<String, Object>) row.get("readiness"))
              .put(
                  "implementation",
                  java.util.Map.of(
                      "status",
                      "ready",
                      "evidence",
                      java.util.Map.of("testFixture", "independent-domain-projection")));
        }
      }
    }
    return resource;
  }

  @Test
  void 미완료_문서_연결이_있어도_전체_OpenAPI는_200이고_현재_날씨와_숙소_일정이_남는다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')]")
                .isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='placeId')]")
                .isNotEmpty())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/accommodations']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/schedule']").exists());
  }
}
