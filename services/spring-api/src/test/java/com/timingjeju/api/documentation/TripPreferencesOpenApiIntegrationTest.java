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
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
class TripPreferencesOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();
  private static final String PUT = "$.paths['/api/v1/trips/{tripId}/preferences'].put";
  private static final String TRIP_ETAG_PATTERN =
      "^\\\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\\\"$";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void preferences_PUT은_exact_headers_closed7field_schema와_flat_response를문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(PUT).exists())
        .andExpect(jsonPath(PUT + ".operationId").value("tripPreferencesUpdate"))
        .andExpect(jsonPath(PUT + ".parameters[?(@.name=='If-Match')]").value(hasSize(1)))
        .andExpect(jsonPath(PUT + ".parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(PUT + ".parameters[?(@.name=='If-Match')].schema.pattern")
                .value(containsInAnyOrder(TRIP_ETAG_PATTERN)))
        .andExpect(jsonPath(PUT + ".parameters[?(@.name=='Idempotency-Key')]").value(hasSize(0)))
        .andExpect(jsonPath(PUT + ".requestBody.required").value(true))
        .andExpect(
            jsonPath(PUT + ".requestBody.content['application/json'].schema.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath(PUT + ".requestBody.content['application/json'].schema.required")
                .value(
                    containsInAnyOrder(
                        "preferredCategories",
                        "arrivalRegionCode",
                        "departureRegionCode",
                        "preferredRegionCodes",
                        "startPlaceId",
                        "endPlaceId",
                        "transportModes")))
        .andExpect(
            jsonPath(PUT + ".responses['200'].headers.ETag['$ref']")
                .value("#/components/headers/ETag"))
        .andExpect(jsonPath("$.components.headers.ETag.required").value(true))
        .andExpect(
            jsonPath("$.components.headers.ETag.schema.pattern")
                .value("^\\\"[A-Za-z0-9._:-]{1,128}\\\"$"))
        .andExpect(
            jsonPath(
                    PUT
                        + ".responses['200'].content['application/json'].schema.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath(PUT + ".responses['200'].content['application/json'].schema.allOf")
                .doesNotExist())
        .andExpect(
            jsonPath(PUT + ".responses['200'].content['application/json'].schema.required")
                .value(
                    containsInAnyOrder(
                        "tripId",
                        "preferences",
                        "scheduleEffect",
                        "regenerationRequired",
                        "activeScheduleVersionId",
                        "tripStatus",
                        "updatedAt")))
        .andExpect(
            jsonPath(PUT + ".responses['200'].content['application/json'].schema.properties.*")
                .value(hasSize(7)));
  }

  @Test
  void preferences_PUT_examples는_canonical_fixture의_7field_wire를그대로쓴다() throws Exception {
    String request = PUT + ".requestBody.content['application/json'].example";
    String success = PUT + ".responses['200'].content['application/json'].example";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(request + ".*").value(hasSize(7)))
        .andExpect(jsonPath(request + ".preferredCategories[0]").value("tourist_attraction"))
        .andExpect(jsonPath(request + ".arrivalRegionCode").value("jeju-si"))
        .andExpect(jsonPath(request + ".preferredRegionCodes[0]").value("seongsan"))
        .andExpect(jsonPath(request + ".transportModes[1].mode").value("taxi"))
        .andExpect(
            jsonPath(request + ".startPlaceId").value("20000000-0000-4000-8000-000000000086"))
        .andExpect(jsonPath(success + ".*").value(hasSize(7)))
        .andExpect(jsonPath(success + ".preferences.*").value(hasSize(7)))
        .andExpect(jsonPath(success + ".tripId").value("50000000-0000-4000-8000-000000000086"))
        .andExpect(
            jsonPath(success + ".preferences.startPlaceId")
                .value("20000000-0000-4000-8000-000000000086"))
        .andExpect(jsonPath(success + ".scheduleEffect").value("invalidated"))
        .andExpect(jsonPath(success + ".revision").doesNotExist())
        .andExpect(jsonPath(request + ".arrivalTransportModes").doesNotExist())
        .andExpect(jsonPath(request + ".departureTransportModes").doesNotExist());
  }

  @Test
  void preferences_PUT은_exact_status와_problem_codes를문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(PUT + ".responses.*").value(hasSize(9)))
        .andExpect(jsonPath(PUT + ".responses['200']").exists())
        .andExpect(jsonPath(PUT + ".responses['400']").exists())
        .andExpect(jsonPath(PUT + ".responses['401']").exists())
        .andExpect(jsonPath(PUT + ".responses['403']").exists())
        .andExpect(jsonPath(PUT + ".responses['404']").exists())
        .andExpect(jsonPath(PUT + ".responses['409']").exists())
        .andExpect(jsonPath(PUT + ".responses['422']").exists())
        .andExpect(jsonPath(PUT + ".responses['500']").exists())
        .andExpect(jsonPath(PUT + ".responses['503']").exists())
        .andExpect(jsonPath(problemCode("400", "INVALID_REQUEST")).value("INVALID_REQUEST"))
        .andExpect(
            jsonPath(problemCode("401", "AUTHENTICATION_REQUIRED"))
                .value("AUTHENTICATION_REQUIRED"))
        .andExpect(
            jsonPath(PUT + ".responses['403'].content['application/problem+json'].example.code")
                .value("AUTH_ACCESS_DENIED"))
        .andExpect(jsonPath(problemCode("404", "TRIP_NOT_FOUND")).value("TRIP_NOT_FOUND"))
        .andExpect(
            jsonPath(problemCode("409", "TRIP_VERSION_CONFLICT")).value("TRIP_VERSION_CONFLICT"))
        .andExpect(
            jsonPath(problemCode("422", "PREFERENCE_CONSTRAINT_VIOLATION"))
                .value("PREFERENCE_CONSTRAINT_VIOLATION"))
        .andExpect(
            jsonPath(
                    PUT
                        + ".responses['422'].content['application/problem+json'].examples['PREFERENCE_CONSTRAINT_VIOLATION'].value.detail")
                .value("중복 값과 교통수단 primary·priority를 확인해 주세요."))
        .andExpect(
            jsonPath(
                    "$.components.responses.InternalServerProblem.content['application/problem+json'].example.code")
                .value("INTERNAL_SERVER_ERROR"))
        .andExpect(
            jsonPath(PUT + ".responses['503'].content['application/problem+json'].example.code")
                .value("TRIP_DATA_UNAVAILABLE"));
  }

  @Test
  void preferences_PUT은_contract_errorMatrix의_status_code집합을_named_examples로_양방향_exact_공개한다()
      throws Exception {
    JsonNode operation =
        objectMapper
            .readTree(
                mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("paths")
            .get("/api/v1/trips/{tripId}/preferences")
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
          && "/api/v1/trips/{tripId}/preferences".equals(candidate.get("path").asText())) {
        endpoint = candidate;
      }
    }
    assertThat(endpoint).isNotNull();

    for (String statusCode : List.of("400", "401", "404", "409", "422")) {
      JsonNode expectedCodes = endpoint.get("errorMatrix").get(statusCode);
      JsonNode examples =
          operation
              .get("responses")
              .get(statusCode)
              .get("content")
              .get("application/problem+json")
              .get("examples");
      JsonNode problemMedia =
          operation.get("responses").get(statusCode).get("content").get("application/problem+json");
      assertThat(problemMedia.has("example")).isFalse();
      assertThat(examples).isNotNull();
      assertThat(examples.size()).isEqualTo(expectedCodes.size());
      for (JsonNode expectedCode : expectedCodes) {
        String code = expectedCode.asText();
        assertThat(examples.get(code).get("value").get("code").asText()).isEqualTo(code);
      }
    }
  }

  private static String problemCode(String status, String code) {
    return PUT
        + ".responses['"
        + status
        + "'].content['application/problem+json'].examples['"
        + code
        + "'].value.code";
  }

  private static String randomKey() {
    byte[] key = new byte[48];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
