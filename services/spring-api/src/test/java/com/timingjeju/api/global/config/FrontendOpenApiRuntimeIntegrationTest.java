package com.timingjeju.api.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=runtime-contract-openapi"
    })
@AutoConfigureMockMvc
class FrontendOpenApiRuntimeIntegrationTest {
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

  @Test
  void 실제_catalog의_OpenAPI는_현재_입력과_nullable_응답을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.components.schemas.TransportEventRequest.properties.customTerminalName.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.CreateAccommodationRequest.properties.placeId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.AccommodationMutationPayload.properties.activeScheduleVersionId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                    "$.components.schemas.ScheduleMutationResponse.properties.feasibilityStale.const")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/schedule-order'].put.requestBody.content['application/json'].schema['$ref']")
                .value("#/components/schemas/ReorderScheduleRequest"))
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
