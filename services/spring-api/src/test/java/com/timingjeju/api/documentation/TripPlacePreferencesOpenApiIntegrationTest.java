package com.timingjeju.api.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  @Autowired private ObjectMapper objectMapper;

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
        .andExpect(
            jsonPath(operation + ".responses.keys()")
                .value(
                    containsInAnyOrder(
                        "200", "400", "401", "403", "404", "409", "422", "500", "503")))
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
        .andExpect(jsonPath(operation + ".responses['403']").exists())
        .andExpect(jsonPath(operation + ".responses['404']").exists())
        .andExpect(jsonPath(operation + ".responses['409']").exists())
        .andExpect(jsonPath(operation + ".responses['422']").exists())
        .andExpect(jsonPath(operation + ".responses['500']").exists())
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

  @Test
  void place_preferences는_contract_errorMatrix의_동일_status_code를_named_examples로_exact_공개한다()
      throws Exception {
    String operation = "$.paths['/api/v1/trips/{tripId}/place-preferences'].put";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['400'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("INVALID_REQUEST")))
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['401'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN")))
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['404'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("TRIP_NOT_FOUND", "PLACE_NOT_FOUND")))
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['409'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT")))
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['422'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("PLACE_PREFERENCE_CONSTRAINT_VIOLATION")))
        .andExpect(
            jsonPath(
                    operation
                        + ".responses['503'].content['application/problem+json'].examples.*.value.code")
                .value(containsInAnyOrder("TRIP_DATA_UNAVAILABLE")));
  }

  @Test
  void place_preferences_OpenAPI_problem_code는_canonical_errorMatrix와_양방향_exact다()
      throws Exception {
    String openApiJson =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode operation =
        objectMapper
            .readTree(openApiJson)
            .get("paths")
            .get("/api/v1/trips/{tripId}/place-preferences")
            .get("put");
    JsonNode contract =
        objectMapper.readTree(
            Files.readString(
                Path.of(
                    "..",
                    "..",
                    "docs",
                    "contracts",
                    "domains",
                    "preferences-transport",
                    "contract.json")));
    JsonNode endpoint = null;
    for (JsonNode candidate : contract.get("endpoints")) {
      if ("PUT".equals(candidate.get("method").asText())
          && "/api/v1/trips/{tripId}/place-preferences".equals(candidate.get("path").asText())) {
        endpoint = candidate;
      }
    }
    assertThat(endpoint).isNotNull();

    assertThat(operation.get("responses").propertyNames())
        .containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "422", "500", "503");

    for (String statusCode : List.of("400", "401", "404", "409", "422")) {
      JsonNode expectedCodes = endpoint.get("errorMatrix").get(statusCode);
      JsonNode examples =
          operation
              .get("responses")
              .get(statusCode)
              .get("content")
              .get("application/problem+json")
              .get("examples");
      assertThat(examples.size()).isEqualTo(expectedCodes.size());
      for (JsonNode expectedCode : expectedCodes) {
        String code = expectedCode.asText();
        assertThat(examples.get(code).get("value").get("code").asText()).isEqualTo(code);
      }
    }
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
