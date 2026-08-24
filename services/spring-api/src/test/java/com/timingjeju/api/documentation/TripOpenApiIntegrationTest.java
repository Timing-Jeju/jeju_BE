package com.timingjeju.api.documentation;

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
class TripOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void trip_create_list_detail은_bearer_DTO_cursor와_problem을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}'].get").exists())
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/v1/trips'].get.parameters[?(@.name=='size')].schema.maximum")
                .value(50))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/CreateTripRequest"))
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['201']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/trips'].post.responses['201'].headers.Location").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['201'].headers.ETag").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.responses['201'].headers['Idempotency-Replayed']")
                .exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['422']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}'].get.responses['404']").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}'].get.parameters[?(@.name=='tripId')].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
