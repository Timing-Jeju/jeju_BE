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
      "app.security.jwt.secret=test-only-openapi-readiness-key-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=future-contract-openapi"
    })
@AutoConfigureMockMvc
class FrontendOpenApiFutureContractIntegrationTest {
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
            mapper,
            problems,
            placePreferences,
            preferences,
            path -> fixture.resource(path, "not-ready"));
    doAnswer(
            invocation -> {
              actual.customise(invocation.getArgument(0));
              return null;
            })
        .when(customizer)
        .customise(any());
  }

  @Test
  void 미구현_selector가_있어도_전체_OpenAPI는_200이고_현재_날씨와_숙소_일정이_남는다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')]")
                .isNotEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='placeId')]")
                .isEmpty())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/accommodations']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/schedule']").exists());
  }
}
