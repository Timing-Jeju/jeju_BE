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
  void 목록_ETag는_opaque_strong_형식을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceResponse.properties.etag.pattern")
                .value("^\"[A-Za-z0-9._:-]{1,128}\"$"));
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
        .andExpect(jsonPath(item + ".delete.responses['409']").exists())
        .andExpect(
            jsonPath(item + ".delete.parameters[?(@.name == 'If-Match')].required")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(jsonPath(item + ".delete.responses['204'].content").doesNotExist())
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

  @Test
  void 목록과_PATCH는_required_ETag를_제공하고_POST만_legacy_union을_허용한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/saved-places'].get.responses['200'].content['application/json'].example.items[0].etag")
                .exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/saved-places/{placeId}'].patch.responses['200'].content['application/json'].example.etag")
                .exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/saved-places'].post.responses['201'].content['application/json'].example.etag")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceResponse.required")
                .value(org.hamcrest.Matchers.hasItem("etag")))
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceResponse.properties.etag.type")
                .value("string"))
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceResponse.properties.memo.type")
                .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceCreateResponse.oneOf")
                .value(org.hamcrest.Matchers.hasSize(2)))
        .andExpect(
            jsonPath("$.components.schemas.SavedPlaceLegacyV1.properties.etag").doesNotExist())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/saved-places'].post.responses['201'].content['application/json'].schema.$ref")
                .value("#/components/schemas/SavedPlaceCreateResponse"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/saved-places/{placeId}'].patch.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/SavedPlaceResponse"));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
