package com.timingjeju.api.domain.profile.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
      "timing-jeju.test.context=current-user-profile-controller"
    })
@AutoConfigureMockMvc
class CurrentUserProfileControllerIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER_ID = UUID.fromString("18000000-0000-0000-0000-000000000001");

  @Autowired private MockMvc mvc;
  @MockitoBean private CurrentUserProvisioningService provisioningService;
  @MockitoBean private CurrentUserProfileStore profileStore;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void profileResponse() {
    when(profileStore.read(USER_ID)).thenReturn(Optional.of(profile()));
    when(profileStore.update(eq(USER_ID), any(), any())).thenReturn(profile());
  }

  @Test
  void GET_me_프로필이_없으면_canonical_sub로_provisioning하고_200을_반환한다() throws Exception {
    mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk());

    verify(provisioningService).provision(argThat(user -> USER_ID.equals(user.userId())));
    verify(profileStore).read(USER_ID);
  }

  @Test
  void GET_me는_email_provider_image를_read_only_response로_반환한다() throws Exception {
    mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
        .andExpect(jsonPath("$.email").value("user@example.invalid"))
        .andExpect(jsonPath("$.nickname").value("제주 여행자"))
        .andExpect(jsonPath("$.profileImageUrl").value("https://images.example.invalid/avatar"))
        .andExpect(jsonPath("$.locale").value("ko-KR"))
        .andExpect(jsonPath("$.providers[0]").value("custom:naver"))
        .andExpect(jsonPath("$.onboardingCompleted").value(true));
  }

  @Test
  void GET과_PATCH_me는_인증이_없으면_401이다() throws Exception {
    mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mvc.perform(
            patch("/api/v1/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"여행자\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void GET_me의_storage_failure는_PII없는_PROFILE_DATA_UNAVAILABLE_503이다() throws Exception {
    when(profileStore.read(USER_ID)).thenThrow(CurrentUserProfileException.dataUnavailable());

    mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PROFILE_DATA_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.providerMessage").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());
  }

  @Test
  void PATCH_me는_nickname을_trim하고_locale만_수정한다() throws Exception {
    mvc.perform(
            patch("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"  제주 여행자  \",\"locale\":\"ko-KR\"}"))
        .andExpect(status().isOk());

    verify(provisioningService).provision(argThat(user -> USER_ID.equals(user.userId())));
    verify(profileStore)
        .update(
            eq(USER_ID),
            argThat(
                command ->
                    command.nicknamePresent()
                        && "제주 여행자".equals(command.nickname())
                        && command.localePresent()
                        && "ko-KR".equals(command.locale())),
            any());
  }

  @Test
  void PATCH_me는_unknown_email_provider_image_input을_400으로_거부한다() throws Exception {
    for (String body :
        new String[] {
          "{\"email\":\"attacker@example.invalid\"}",
          "{\"providers\":[\"google\"]}",
          "{\"profileImageUrl\":\"https://attacker.invalid/a\"}",
          "{\"profileImageObjectKey\":\"profiles/a.webp\"}",
          "{\"userId\":\"28000000-0000-0000-0000-000000000002\"}"
        }) {
      mvc.perform(
              patch("/api/v1/me")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PROFILE_LEGAL_REQUEST"));
    }
    verify(provisioningService, never()).provision(any());
    verify(profileStore, never()).update(any(), any(), any());
  }

  @Test
  void PATCH_me는_empty와_explicit_null을_400으로_거부한다() throws Exception {
    for (String body :
        new String[] {
          "{}",
          "{\"nickname\":null}",
          "{\"locale\":null}",
          "{\"nickname\":123}",
          "{\"locale\":true}",
          "{\"nickname\":[\"여행자\"]}",
          "{\"locale\":{\"language\":\"ko-KR\"}}"
        }) {
      mvc.perform(
              patch("/api/v1/me")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PROFILE_LEGAL_REQUEST"));
    }
    verify(provisioningService, never()).provision(any());
    verify(profileStore, never()).read(any());
    verify(profileStore, never()).update(any(), any(), any());
  }

  @Test
  void PATCH_me는_nickname_경계_control문자와_noncanonical_locale을_검증한다() throws Exception {
    String fifty = "가".repeat(50);
    mvc.perform(
            patch("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + fifty + "\"}"))
        .andExpect(status().isOk());

    for (String body :
        new String[] {
          "{\"nickname\":\"" + "가".repeat(51) + "\"}",
          "{\"nickname\":\"여행\\u0000자\"}",
          "{\"locale\":\"ko-kr\"}",
          "{\"locale\":\" ko-KR \"}"
        }) {
      mvc.perform(
              patch("/api/v1/me")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PROFILE_LEGAL_REQUEST"));
    }
  }

  @Test
  void OpenAPI는_GET_PATCH_me와_닫힌_PATCH_schema를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/me'].get.summary").value("현재 사용자 프로필 조회"))
        .andExpect(jsonPath("$.paths['/api/v1/me'].patch.summary").value("현재 사용자 프로필 수정"))
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
        .andExpect(jsonPath("$.paths['/api/v1/me'].patch.responses['400']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me'].get.responses['503']").exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.minProperties").value(1))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties")
                .value(org.hamcrest.Matchers.aMapWithSize(2)))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.nickname")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.locale")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.nickname.type")
                .value("string"))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfilePatchRequest.properties.nickname.nullable")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.locale.type")
                .value("string"))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfilePatchRequest.properties.locale.nullable")
                .doesNotExist())
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfilePatchRequest.properties.locale.enum[0]")
                .value("ko-KR"))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.email")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfilePatchRequest.properties.providers")
                .doesNotExist())
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfilePatchRequest.properties.profileImageUrl")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.email").exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.providers")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.profileImageUrl")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties")
                .value(org.hamcrest.Matchers.aMapWithSize(8)))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfileResponse.properties.profileImageObjectKey")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.email.readOnly")
                .value(true))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.email.type")
                .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath("$.components.schemas.CurrentUserProfileResponse.properties.nickname.type")
                .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfileResponse.properties.providers.readOnly")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfileResponse.properties.profileImageUrl.readOnly")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CurrentUserProfileResponse.properties.profileImageUrl.type")
                .value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")));
  }

  private static CurrentUserProfile profile() {
    return new CurrentUserProfile(
        USER_ID,
        "user@example.invalid",
        "제주 여행자",
        "https://images.example.invalid/avatar",
        "ko-KR",
        java.util.List.of("custom:naver"),
        true,
        Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }

  private static String token(UUID userId) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(userId.toString())
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
}
