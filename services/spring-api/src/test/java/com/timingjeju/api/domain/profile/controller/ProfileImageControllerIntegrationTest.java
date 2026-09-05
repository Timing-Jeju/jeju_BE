package com.timingjeju.api.domain.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.ProfileImageApplyResult;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import com.timingjeju.api.application.profile.ProfileImageSource;
import com.timingjeju.api.application.profile.service.ProfileImageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=profile-image-controller"
    })
@AutoConfigureMockMvc
class ProfileImageControllerIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String KEY = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final ProfileImageSnapshot SNAPSHOT =
      new ProfileImageSnapshot(
          KEY,
          "https://project.example.invalid/storage/v1/object/public/profile-images/" + KEY,
          ProfileImageSource.STORAGE,
          1,
          Instant.parse("2026-08-25T11:00:00Z"));

  @Autowired private MockMvc mvc;
  @Autowired private tools.jackson.databind.ObjectMapper objectMapper;
  @MockitoBean private ProfileImageService service;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @Test
  void GET_profile_image는_atomic_body와_strong_ETag를_반환한다() throws Exception {
    when(service.read(any())).thenReturn(SNAPSHOT);

    mvc.perform(
            get("/api/v1/me/profile-image").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"profile-image-1\""))
        .andExpect(jsonPath("$.profileImageObjectKey").value(KEY))
        .andExpect(jsonPath("$.profileImageSource").value("storage"))
        .andExpect(jsonPath("$.profileImageVersion").value(1));
  }

  @Test
  void GET_profile_image의_profile_failure는_PROFILE_DATA_UNAVAILABLE_503이다() throws Exception {
    when(service.read(any())).thenThrow(CurrentUserProfileException.dataUnavailable());

    mvc.perform(
            get("/api/v1/me/profile-image").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PROFILE_DATA_UNAVAILABLE"));
  }

  @Test
  void PUT_profile_image는_required_headers와_exact_success_snapshot을_반환한다() throws Exception {
    when(service.applyIdempotent(any(), eq("018f47a1-43d2-7b6e-9fa2-11a1cc32c675"), any()))
        .thenReturn(new ProfileImageApplyResult(SNAPSHOT, false));

    mvc.perform(
            put("/api/v1/me/profile-image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileImageObjectKey\":\"" + KEY + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"profile-image-1\""))
        .andExpect(header().string("Idempotency-Replayed", "false"))
        .andExpect(jsonPath("$.profileImageUrl").value(SNAPSHOT.imageUrl()));
  }

  @Test
  void PUT_profile_image의_storage_owner_mismatch는_exact_404_problem으로_은닉한다() throws Exception {
    when(service.applyIdempotent(any(), eq("018f47a1-43d2-7b6e-9fa2-11a1cc32c675"), any()))
        .thenThrow(ProfileImageException.notFound());

    mvc.perform(
            put("/api/v1/me/profile-image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileImageObjectKey\":\"" + KEY + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_NOT_FOUND"));
  }

  @Test
  void PUT_profile_image의_runtime_problem은_contract_1_1_1_fixture_8필드와_exact하다() throws Exception {
    for (ProfileImageException failure :
        List.of(
            ProfileImageException.notFound(),
            ProfileImageException.versionConflict(),
            ProfileImageException.tooLarge(),
            ProfileImageException.mediaTypeUnsupported(),
            ProfileImageException.storageUnavailable())) {
      reset(service);
      when(service.applyIdempotent(any(), eq("018f47a1-43d2-7b6e-9fa2-11a1cc32c675"), any()))
          .thenThrow(failure);

      String body =
          mvc.perform(
                  put("/api/v1/me/profile-image")
                      .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                      .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                      .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"profileImageObjectKey\":\"" + KEY + "\"}"))
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);

      @SuppressWarnings("unchecked")
      Map<String, Object> actual = objectMapper.readValue(body, Map.class);
      Map<String, Object> expected = canonicalProblem(failure.code());
      assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
      assertThat(actual)
          .containsEntry("type", expected.get("type"))
          .containsEntry("title", expected.get("title"))
          .containsEntry("status", expected.get("status"))
          .containsEntry("detail", expected.get("detail"))
          .containsEntry("code", expected.get("code"))
          .containsEntry("fieldErrors", expected.get("fieldErrors"));
      assertThat(actual.get("traceId")).asString().matches("[0-9a-f]{32}");
      assertThat(actual.get("instance"))
          .isEqualTo("urn:timing-jeju:problem:" + actual.get("traceId"));
    }
  }

  @Test
  void PUT_profile_image는_unknown_or_missing_field와_headers를_400으로_닫는다() throws Exception {
    for (String body :
        new String[] {
          "{}", "{\"profileImageObjectKey\":null,\"url\":\"https://attacker.invalid\"}"
        }) {
      mvc.perform(
              put("/api/v1/me/profile-image")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                  .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_REQUEST"));
    }
  }

  @Test
  void PUT_profile_image의_JSON_null은_service전에_canonical_400으로_닫는다() throws Exception {
    mvc.perform(
            put("/api/v1/me/profile-image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "profile-image-confirm-78")
                .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void PUT_profile_image는_valid_JSON뒤의_second_JSON을_service전에_400으로_거부한다() throws Exception {
    mvc.perform(
            put("/api/v1/me/profile-image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileImageObjectKey\":\"" + KEY + "\"}{\"profileImageObjectKey\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void PUT_profile_image의_raw_body_MAX_plus_1은_service전_transport_400으로_거부한다() throws Exception {
    byte[] prefix =
        ("{\"profileImageObjectKey\":\"" + KEY + "\"}").getBytes(StandardCharsets.UTF_8);
    byte[] oversized = java.util.Arrays.copyOf(prefix, 1024 * 1024 + 1);
    java.util.Arrays.fill(oversized, prefix.length, oversized.length, (byte) ' ');

    mvc.perform(
            put("/api/v1/me/profile-image")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "018f47a1-43d2-7b6e-9fa2-11a1cc32c675")
                .header(HttpHeaders.IF_MATCH, "\"profile-image-0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void OpenAPI는_companion_GET_PUT_headers와_closed_body를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/me/profile-image'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/profile-image'].put").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].required")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].schema.type")
                .value("string"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].schema.minLength")
                .value(1))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].schema.maxLength")
                .value(128))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].schema.pattern")
                .value("^[\\x20-\\x7E]{1,128}$"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='Idempotency-Key')].schema.format")
                .doesNotExist())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='If-Match')].required")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.parameters[?(@.name=='If-Match')].schema.pattern")
                .value("^\\\"profile-image-(?:0|[1-9][0-9]*)\\\"$"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.responses['200'].headers.Idempotency-Replayed.schema.type")
                .value("boolean"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/profile-image'].put.responses['409'].content['application/problem+json'].schema")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.ProfileImageRequest.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.ProfileImageRequest.required[0]")
                .value("profileImageObjectKey"));
  }

  private static String token() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(OWNER.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    token.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return token.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> canonicalProblem(String code) throws Exception {
    Path fixture =
        Path.of("..", "..", "fixtures", "contracts", "profile-legal", "problem.json")
            .toAbsolutePath()
            .normalize();
    Map<String, Object> root = objectMapper.readValue(Files.readString(fixture), Map.class);
    return ((List<Map<String, Object>>) root.get("examples"))
        .stream()
            .map(example -> (Map<String, Object>) example.get("body"))
            .filter(problem -> code.equals(problem.get("code")))
            .findFirst()
            .orElseThrow();
  }
}
