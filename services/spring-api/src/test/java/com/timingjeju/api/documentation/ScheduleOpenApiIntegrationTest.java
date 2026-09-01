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
class ScheduleOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void schedule_read는_canonical_query_closed_projection_problem과_example을_문서화한다() throws Exception {
    String path = "$.paths['/api/v1/trips/{tripId}/schedule'].get";
    String success = path + ".responses['200'].content['application/json']";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(path + ".operationId").value("tripScheduleRead"))
        .andExpect(jsonPath(path + ".tags[0]").value("일정"))
        .andExpect(jsonPath(path + ".parameters", hasSize(2)))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='tripId')].required").value(true))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='tripId')].schema.format").value("uuid"))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='versionId')].required").value(false))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='versionId')].schema.format").value("uuid"))
        .andExpect(jsonPath(path + ".requestBody").doesNotExist())
        .andExpect(
            jsonPath(path + ".responses.keys()")
                .value(containsInAnyOrder("200", "400", "401", "403", "404", "500", "503")))
        .andExpect(jsonPath(success + ".schema.additionalProperties").value(false))
        .andExpect(
            jsonPath(success + ".schema.required")
                .value(containsInAnyOrder("tripId", "scheduleVersion", "days")))
        .andExpect(
            jsonPath(success + ".schema.properties.scheduleVersion.required")
                .value(
                    containsInAnyOrder(
                        "scheduleVersionId",
                        "versionNo",
                        "status",
                        "sourceType",
                        "baseScheduleVersionId",
                        "score",
                        "feasibilityStale")))
        .andExpect(
            jsonPath(success + ".schema.properties.scheduleVersion.properties.score.type")
                .value(containsInAnyOrder("integer", "null")))
        .andExpect(
            jsonPath(
                    success
                        + ".schema.properties.days.items.properties.items.items.properties.progress.type")
                .value(containsInAnyOrder("object", "null")))
        .andExpect(
            jsonPath(
                    success
                        + ".schema.properties.days.items.properties.legs.items.properties.transportMode.enum")
                .value(containsInAnyOrder("walk", "public_transit", "rental_car", "taxi")))
        .andExpect(jsonPath(success + ".example.tripId").exists())
        .andExpect(
            jsonPath(success + ".example.days[0].items[0].plannedStartAt")
                .value("2026-09-01T09:00:00+09:00"))
        .andExpect(
            jsonPath(path + ".responses['404'].content['application/problem+json'].example.code")
                .value("SCHEDULE_VERSION_NOT_FOUND"))
        .andExpect(
            jsonPath(path + ".responses['503'].content['application/problem+json'].example.code")
                .value("TRIP_DATA_UNAVAILABLE"));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
