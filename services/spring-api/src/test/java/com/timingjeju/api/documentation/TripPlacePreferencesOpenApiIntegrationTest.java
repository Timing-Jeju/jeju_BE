package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
      "timing-jeju.test.context=trip-place-preferences-openapi"
    })
@AutoConfigureMockMvc
class TripPlacePreferencesOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void place_preferences는_closed_schema_ETag와_오류_응답을_문서화한다() throws Exception {
    String operation = "$.paths['/api/v1/trips/{tripId}/place-preferences'].put";
    mvc.perform(get("/v3/api-docs"))
        .andDo(
            result -> {
              if (result.getResolvedException() != null) {
                throw new AssertionError(result.getResolvedException());
              }
            })
        .andExpect(status().isOk())
        .andExpect(jsonPath(operation + ".operationId").value("tripPlacePreferencesUpdate"))
        .andExpect(jsonPath(operation + ".parameters").value(hasSize(2)))
        .andExpect(jsonPath(operation + ".parameters[?(@.name=='tripId')].required").value(true))
        .andExpect(jsonPath(operation + ".parameters[?(@.name=='If-Match')].in").value("header"))
        .andExpect(jsonPath(operation + ".parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(
                    operation
                        + ".requestBody.content['application/json'].schema.additionalProperties")
                .value(false))
        .andExpect(jsonPath(operation + ".responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(operation + ".responses['400']").exists())
        .andExpect(jsonPath(operation + ".responses['401']").exists())
        .andExpect(jsonPath(operation + ".responses['404']").exists())
        .andExpect(jsonPath(operation + ".responses['409']").exists())
        .andExpect(jsonPath(operation + ".responses['422']").exists())
        .andExpect(jsonPath(operation + ".responses['503']").exists())
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['200'].content['application/json'].schema.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesRequest.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesRequest.required")
                .value(containsInAnyOrder("items")))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesRequest.properties.items.minItems")
                .value(0))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesRequest.properties.items.maxItems")
                .value(100))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.required")
                .value(containsInAnyOrder("placeId", "type", "targetDayNo", "priority")))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.properties.type.enum")
                .value(containsInAnyOrder("must_visit", "avoid")))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.properties.targetDayNo.minimum")
                .value(1))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.properties.targetDayNo.maximum")
                .value(30))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.properties.priority.minimum")
                .value(0))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferenceItem.properties.priority.maximum")
                .value(100))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesResponse.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.PlacePreferencesResponse.required")
                .value(
                    containsInAnyOrder(
                        "tripId",
                        "scheduleEffect",
                        "regenerationRequired",
                        "activeScheduleVersionId",
                        "tripStatus",
                        "updatedAt",
                        "items")));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
