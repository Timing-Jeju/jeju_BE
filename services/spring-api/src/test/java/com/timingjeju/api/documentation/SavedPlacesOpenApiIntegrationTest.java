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
class SavedPlacesOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void saved_places는_success_status_headers와_endpoint_error_matrix를_문서화한다() throws Exception {
    String collection = "$.paths['/api/v1/me/saved-places']";
    String item = "$.paths['/api/v1/me/saved-places/{placeId}']";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(collection + ".post.responses['201'].headers.Location").exists())
        .andExpect(jsonPath(collection + ".post.responses['201'].headers.ETag").exists())
        .andExpect(
            jsonPath(collection + ".post.responses['201'].headers.Idempotency-Replayed").exists())
        .andExpect(jsonPath(collection + ".post.responses['200'].headers.Location").exists())
        .andExpect(jsonPath(collection + ".post.responses['200'].headers.ETag").exists())
        .andExpect(
            jsonPath(collection + ".post.responses['200'].headers.Idempotency-Replayed").exists())
        .andExpect(jsonPath(item + ".patch.responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(collection + ".get.responses['400']").exists())
        .andExpect(jsonPath(collection + ".post.responses['400']").exists())
        .andExpect(jsonPath(collection + ".post.responses['404']").exists())
        .andExpect(jsonPath(collection + ".post.responses['409']").exists())
        .andExpect(jsonPath(collection + ".post.responses['422']").exists())
        .andExpect(jsonPath(item + ".patch.responses['400']").exists())
        .andExpect(jsonPath(item + ".patch.responses['404']").exists())
        .andExpect(jsonPath(item + ".patch.responses['409']").exists())
        .andExpect(jsonPath(item + ".patch.responses['422']").exists())
        .andExpect(jsonPath(item + ".delete.responses['400']").exists())
        .andExpect(jsonPath(item + ".delete.responses['404']").exists())
        .andExpect(
            jsonPath("$.components.schemas.CreateSavedPlaceRequest.properties.placeId.type")
                .value("string"))
        .andExpect(
            jsonPath("$.components.schemas.CreateSavedPlaceRequest.properties.placeId.format")
                .value("uuid"))
        .andExpect(
            jsonPath("$.components.schemas.CreateSavedPlaceRequest.required")
                .value(org.hamcrest.Matchers.hasItem("placeId")));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
