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
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
class TripReplayOpenApiIntegrationTest {

  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void 기본_runtime_OpenAPI도_POST_legacy_union과_최신_GET을_분리한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.responses['201'].content['application/json'].schema.$ref")
                .value("#/components/schemas/TripCreateResponse"))
        .andExpect(jsonPath("$.components.schemas.TripCreateResponse.oneOf").value(hasSize(3)))
        .andExpect(
            jsonPath("$.components.schemas.TripDetailLegacyV1.properties.days.items.$ref")
                .value("#/components/schemas/TripDayLegacyV1"))
        .andExpect(
            jsonPath("$.components.schemas.TripDayLegacyV1.required")
                .value(containsInAnyOrder("dayId", "dayNo", "date")))
        .andExpect(
            jsonPath("$.components.schemas.TripDay.required")
                .value(
                    containsInAnyOrder(
                        "dayId", "dayNo", "date", "activityStartTime", "activityEndTime")));
  }

  @Test
  void 기본_Day_PUT도_projection_이전_receipt_union을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/day-activity-windows'].put.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/TripDayActivityWindowsResponse"))
        .andExpect(
            jsonPath("$.components.schemas.TripDayActivityWindowsResponse.oneOf").value(hasSize(2)))
        .andExpect(
            jsonPath("$.components.schemas.TripDetailLegacyV11.properties.transportEvents")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.TripDetailLegacyV11.properties.accommodations")
                .doesNotExist());
  }

  private static String randomKey() {
    byte[] key = new byte[64];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
