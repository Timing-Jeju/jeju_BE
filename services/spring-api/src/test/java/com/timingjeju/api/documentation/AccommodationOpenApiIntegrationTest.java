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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class AccommodationOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void 숙소_CRUD는_closed_schema_headers_status_examples를_canonical_contract로_투영한다() throws Exception {
    String collection = "$.paths['/api/v1/trips/{tripId}/accommodations'].post";
    String item = "$.paths['/api/v1/trips/{tripId}/accommodations/{accommodationId}']";
    String createSchema = collection + ".requestBody.content['application/json'].schema";
    String responseSchema = collection + ".responses['201'].content['application/json'].schema";

    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(collection + ".operationId").value("tripAccommodationsCreate"))
        .andExpect(jsonPath(item + ".patch.operationId").value("tripAccommodationsUpdate"))
        .andExpect(jsonPath(item + ".delete.operationId").value("tripAccommodationsDelete"))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='Idempotency-Key')]").value(hasSize(1)))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='Idempotency-Key')].schema.pattern")
                .value(containsInAnyOrder("^[\\x20-\\x7E]{1,128}$")))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='Idempotency-Key')].schema.minLength")
                .value(containsInAnyOrder(1)))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='Idempotency-Key')].schema.maxLength")
                .value(containsInAnyOrder(128)))
        .andExpect(jsonPath(collection + ".parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='If-Match')].schema.pattern")
                .value(
                    containsInAnyOrder(
                        "^\\\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\\\"$")))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='If-Match')].example")
                .value(containsInAnyOrder("\"trip-68000000-0000-4000-8000-000000000068-r1\"")))
        .andExpect(jsonPath(item + ".patch.parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(item + ".delete.parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(jsonPath(createSchema + ".additionalProperties").value(false))
        .andExpect(
            jsonPath(createSchema + ".required")
                .value(
                    containsInAnyOrder(
                        "placeId",
                        "customName",
                        "checkInDate",
                        "checkOutDate",
                        "checkInTime",
                        "checkOutTime")))
        .andExpect(jsonPath(createSchema + ".oneOf[0].required").value(hasSize(2)))
        .andExpect(jsonPath(createSchema + ".oneOf[1].required").value(hasSize(2)))
        .andExpect(jsonPath(responseSchema + ".additionalProperties").value(false))
        .andExpect(
            jsonPath(responseSchema + ".properties.accommodation.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath(responseSchema + ".properties.accommodation.properties.checkInTime.pattern")
                .value("^(?:[01]\\d|2[0-3]):[0-5]\\d$"))
        .andExpect(
            jsonPath(responseSchema + ".properties.scheduleEffect.enum")
                .value(containsInAnyOrder("none", "invalidated")))
        .andExpect(jsonPath(collection + ".responses['201'].headers.Location").exists())
        .andExpect(jsonPath(collection + ".responses['201'].headers.ETag").exists())
        .andExpect(
            jsonPath(collection + ".responses['201'].headers['Idempotency-Replayed']").exists())
        .andExpect(jsonPath(item + ".patch.responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(item + ".delete.responses['204'].content").doesNotExist())
        .andExpect(jsonPath(collection + ".responses['503']").doesNotExist())
        .andExpect(jsonPath(item + ".patch.responses['503']").doesNotExist())
        .andExpect(jsonPath(item + ".delete.responses['503']").doesNotExist())
        .andExpect(
            jsonPath(collection + ".responses['404']['x-error-codes']")
                .value(containsInAnyOrder("TRIP_NOT_FOUND", "PLACE_NOT_FOUND")))
        .andExpect(
            jsonPath(collection + ".responses['409']['x-error-codes']")
                .value(
                    containsInAnyOrder(
                        "IDEMPOTENCY_KEY_REUSED",
                        "TRIP_VERSION_CONFLICT",
                        "ACCOMMODATION_CONCURRENT_CONFLICT")))
        .andExpect(
            jsonPath(item + ".patch.responses['404']['x-error-codes']")
                .value(
                    containsInAnyOrder(
                        "TRIP_NOT_FOUND", "ACCOMMODATION_NOT_FOUND", "PLACE_NOT_FOUND")))
        .andExpect(
            jsonPath(item + ".delete.responses['404']['x-error-codes']")
                .value(containsInAnyOrder("TRIP_NOT_FOUND", "ACCOMMODATION_NOT_FOUND")))
        .andExpect(
            jsonPath(collection + ".responses['422'].content['application/problem+json'].example")
                .doesNotExist());
  }

  @Test
  void 숙소_mutation은_canonical_matrix의_모든_code를_named_problem_example로_정확히_제공한다() throws Exception {
    JsonNode api =
        objectMapper.readTree(
            mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsByteArray());
    JsonNode contract =
        objectMapper.readTree(
            Files.readString(
                Path.of(
                    "..",
                    "..",
                    "docs",
                    "contracts",
                    "domains",
                    "accommodations",
                    "contract.json")));
    JsonNode fixture =
        objectMapper.readTree(
            Files.readString(
                Path.of("..", "..", "fixtures", "contracts", "accommodations", "problem.json")));
    Map<String, JsonNode> expectedByCode = new HashMap<>();
    fixture.get("examples").forEach(value -> expectedByCode.put(value.get("code").asText(), value));

    for (JsonNode endpoint : contract.get("endpoints")) {
      if (!endpoint.get("path").asText().contains("/accommodations")) {
        continue;
      }
      String method = endpoint.get("method").asText().toLowerCase();
      JsonNode responses =
          api.at("/paths/" + pointer(endpoint.get("path").asText()) + "/" + method + "/responses");
      Set<String> expectedStatuses =
          Set.of(
              method.equals("post") ? "201" : method.equals("patch") ? "200" : "204",
              "400",
              "401",
              "403",
              "404",
              "409",
              "422",
              "500");
      assertThat(fieldNames(responses)).isEqualTo(expectedStatuses);

      endpoint
          .get("errorMatrix")
          .properties()
          .forEach(
              entry -> {
                String status = entry.getKey();
                List<String> codes = new ArrayList<>();
                entry.getValue().forEach(code -> codes.add(code.asText()));
                JsonNode response = responses.get(status);
                assertThat(textValues(response.get("x-error-codes")))
                    .containsExactlyElementsOf(codes);
                JsonNode media = response.at("/content/application~1problem+json");
                assertThat(media.has("example")).isFalse();
                assertThat(fieldNames(media.get("examples")))
                    .containsExactlyInAnyOrderElementsOf(codes);
                for (String code : codes) {
                  JsonNode actual = media.get("examples").get(code).get("value");
                  JsonNode expected = expectedByCode.get(code);
                  assertThat(fieldNames(actual))
                      .containsExactlyInAnyOrder(
                          "type",
                          "title",
                          "status",
                          "detail",
                          "instance",
                          "code",
                          "traceId",
                          "fieldErrors");
                  for (String field : List.of("type", "title", "status", "detail", "code")) {
                    assertThat(actual.get(field)).isEqualTo(expected.get(field));
                  }
                }
              });
    }
  }

  private static String pointer(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static Set<String> fieldNames(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  private static List<String> textValues(JsonNode node) {
    List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
