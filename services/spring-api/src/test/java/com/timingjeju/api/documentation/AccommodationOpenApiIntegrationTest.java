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
class AccommodationOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

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
                .value(
                    containsInAnyOrder(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
        .andExpect(
            jsonPath(collection + ".parameters[?(@.name=='Idempotency-Key')].schema.format")
                .value(containsInAnyOrder("uuid")))
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
        .andExpect(
            jsonPath(
                    collection
                        + ".responses['422'].content['application/problem+json'].example.code")
                .value("ACCOMMODATION_DATE_GAP_OR_OVERLAP"))
        .andExpect(
            jsonPath(
                    item
                        + ".delete.responses['422'].content['application/problem+json'].example.code")
                .value("ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE"));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
