package com.timingjeju.api.domain.legal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.legal.ConsentUpdateResult;
import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalDocumentStore;
import com.timingjeju.api.application.legal.LegalProfileException;
import com.timingjeju.api.application.legal.UserConsentStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
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
      "timing-jeju.test.context=legal-profile-controller"
    })
@AutoConfigureMockMvc
class LegalProfileControllerIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER_ID = UUID.fromString("19000000-0000-0000-0000-000000000001");
  private static final UUID TERMS_ID = UUID.fromString("19200000-0000-0000-0000-000000000001");
  private static final Instant EFFECTIVE_AT = Instant.parse("2026-08-01T00:00:00Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private LegalDocumentStore documentStore;
  @MockitoBean private UserConsentStore consentStore;
  @MockitoBean private CurrentUserProvisioningService provisioning;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void responses() {
    when(documentStore.findEffectiveCandidates(eq("ko-KR"), any()))
        .thenReturn(List.of(document(TERMS_ID, "terms", "1.0.0", EFFECTIVE_AT)));
    when(consentStore.updateRequiredConsents(eq(USER_ID), eq("ko-KR"), any(), any()))
        .thenReturn(new ConsentUpdateResult(true, Instant.parse("2026-08-25T00:00:00Z")));
  }

  @Test
  void GET_legal_documents는_익명과_유효토큰에서_같은_현재_문서를_반환한다() throws Exception {
    for (String authorization : List.of("", "Bearer " + token(USER_ID))) {
      var request = get("/api/v1/legal-documents").queryParam("locale", "ko-KR");
      if (!authorization.isEmpty()) {
        request.header(HttpHeaders.AUTHORIZATION, authorization);
      }
      mvc.perform(request)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.locale").value("ko-KR"))
          .andExpect(jsonPath("$.items[0].documentId").value(TERMS_ID.toString()))
          .andExpect(jsonPath("$.items[0].version").value("1.0.0"));
    }
  }

  @Test
  void GET_legal_documents는_미지원_locale을_400으로_거부한다() throws Exception {
    mvc.perform(get("/api/v1/legal-documents").queryParam("locale", "en-US"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_LEGAL_REQUEST"));
  }

  @Test
  void PUT_consents는_canonical_sub와_exact_document를_store에_전달한다() throws Exception {
    mvc.perform(
            put("/api/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consents\":[{\"documentId\":\"" + TERMS_ID + "\",\"agreed\":true}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiredConsentsSatisfied").value(true))
        .andExpect(jsonPath("$.updatedAt").value("2026-08-25T00:00:00Z"));

    verify(consentStore)
        .updateRequiredConsents(
            eq(USER_ID),
            eq("ko-KR"),
            org.mockito.ArgumentMatchers.argThat(
                decisions ->
                    decisions.size() == 1
                        && TERMS_ID.equals(decisions.getFirst().documentId())
                        && decisions.getFirst().agreed()),
            any());
  }

  @Test
  void PUT_consents는_인증없음_unknown_duplicate_wrong_type_null_empty를_거부한다() throws Exception {
    String valid = "{\"consents\":[{\"documentId\":\"" + TERMS_ID + "\",\"agreed\":true}]}";
    mvc.perform(put("/api/v1/me/consents").contentType(MediaType.APPLICATION_JSON).content(valid))
        .andExpect(status().isUnauthorized());

    for (String body :
        List.of(
            "{}",
            "{\"consents\":null}",
            "{\"consents\":[]}",
            "{\"consents\":true}",
            "{\"unknown\":1}",
            "{\"consents\":[{\"documentId\":1,\"agreed\":true}]}",
            "{\"consents\":[{\"documentId\":\"19200000-0-0-0-1\",\"agreed\":true}]}",
            "{\"consents\":[{\"documentId\":\"" + TERMS_ID + "\",\"agreed\":\"true\"}]}",
            "{\"consents\":[{\"documentId\":\""
                + TERMS_ID
                + "\",\"agreed\":true,\"ip\":\"127.0.0.1\"}]}",
            "{\"consents\":[{\"documentId\":\""
                + TERMS_ID
                + "\",\"agreed\":true},{\"documentId\":\""
                + TERMS_ID
                + "\",\"agreed\":true}]}")) {
      mvc.perform(
              put("/api/v1/me/consents")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PROFILE_LEGAL_REQUEST"));
    }
  }

  @Test
  void PUT_consents의_required_reject는_cause없는_LEGAL_CONSENT_REQUIRED_422이다() throws Exception {
    when(consentStore.updateRequiredConsents(eq(USER_ID), eq("ko-KR"), any(), any()))
        .thenThrow(LegalProfileException.consentRequired());

    mvc.perform(
            put("/api/v1/me/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consents\":[{\"documentId\":\"" + TERMS_ID + "\",\"agreed\":false}]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("LEGAL_CONSENT_REQUIRED"))
        .andExpect(
            jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
        .andExpect(jsonPath("$.cause").doesNotExist())
        .andExpect(jsonPath("$.jwt").doesNotExist())
        .andExpect(jsonPath("$.ip").doesNotExist());
  }

  @Test
  void PUT_consents의_profile_identity_conflict는_cause없는_PROFILE_CONFLICT_409이다() throws Exception {
    for (ProfileProvisioningException failure :
        List.of(
            ProfileProvisioningException.emailConflict(),
            ProfileProvisioningException.providerSubjectConflict())) {
      reset(provisioning);
      when(provisioning.provision(any())).thenThrow(failure);

      performValidConsent()
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("PROFILE_CONFLICT"))
          .andExpect(jsonPath("$.cause").doesNotExist())
          .andExpect(jsonPath("$.message").doesNotExist());
    }
  }

  @Test
  void PUT_consents의_invalid_identity와_storage_failure는_cause없는_PROFILE_DATA_UNAVAILABLE_503이다()
      throws Exception {
    for (ProfileProvisioningException failure :
        List.of(
            ProfileProvisioningException.invalidAuthIdentity(),
            ProfileProvisioningException.storageUnavailable())) {
      reset(provisioning);
      when(provisioning.provision(any())).thenThrow(failure);

      performValidConsent()
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("PROFILE_DATA_UNAVAILABLE"))
          .andExpect(jsonPath("$.cause").doesNotExist())
          .andExpect(jsonPath("$.providerMessage").doesNotExist());
    }
  }

  @Test
  void GET_failure는_PII없는_PROFILE_DATA_UNAVAILABLE_503이다() throws Exception {
    when(documentStore.findEffectiveCandidates(eq("ko-KR"), any()))
        .thenThrow(LegalProfileException.dataUnavailable());

    mvc.perform(get("/api/v1/legal-documents"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PROFILE_DATA_UNAVAILABLE"))
        .andExpect(jsonPath("$.cause").doesNotExist());
  }

  @Test
  void OpenAPI는_optional_GET_required_PUT_닫힌DTO와_Problem을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/legal-documents'].get.summary").value("현재 법정 문서 조회"))
        .andExpect(jsonPath("$.paths['/api/v1/legal-documents'].get.security.length()").value(2))
        .andExpect(jsonPath("$.paths['/api/v1/legal-documents'].get.security[0]").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/legal-documents'].get.security[1].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/v1/legal-documents'].get.parameters[0].schema.enum[0]")
                .value("ko-KR"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/consents'].put.summary").value("현재 사용자 법정 문서 동의 저장"))
        .andExpect(jsonPath("$.paths['/api/v1/me/consents'].put.responses['400']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/consents'].put.responses['401']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/consents'].put.responses['409']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/consents'].put.responses['422']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/consents'].put.responses['503']").exists())
        .andExpect(
            jsonPath("$.components.schemas.UserConsentsRequest.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.UserConsentsRequest.properties.consents.minItems")
                .value(1))
        .andExpect(
            jsonPath("$.components.schemas.UserConsentsRequest.properties.consents.maxItems")
                .value(20))
        .andExpect(
            jsonPath("$.components.schemas.UserConsentItemRequest.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.UserConsentItemRequest.properties.agreed.type")
                .value("boolean"))
        .andExpect(
            jsonPath("$.components.schemas.LegalDocumentsResponse.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.LegalDocumentItemResponse.additionalProperties")
                .value(false));
  }

  private org.springframework.test.web.servlet.ResultActions performValidConsent()
      throws Exception {
    return mvc.perform(
        put("/api/v1/me/consents")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"consents\":[{\"documentId\":\"" + TERMS_ID + "\",\"agreed\":true}]}"));
  }

  private static LegalDocument document(UUID id, String type, String version, Instant effectiveAt) {
    return new LegalDocument(
        id,
        type,
        "ko-KR",
        version,
        type + " title",
        "https://timing-jeju.example/legal/" + type + "/" + version,
        true,
        effectiveAt);
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
