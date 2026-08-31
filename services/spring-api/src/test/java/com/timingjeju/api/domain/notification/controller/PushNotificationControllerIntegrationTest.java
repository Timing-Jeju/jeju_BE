package com.timingjeju.api.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.notification.NotificationPreference;
import com.timingjeju.api.application.notification.PushDevice;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import com.timingjeju.api.application.notification.service.NotificationPreferenceService;
import com.timingjeju.api.application.notification.service.PushDeviceService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
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
      "timing-jeju.test.context=push-notification-controller"
    })
@AutoConfigureMockMvc
class PushNotificationControllerIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER_ID = UUID.fromString("11300000-0000-0000-0000-000000000001");
  private static final UUID DEVICE_ID = UUID.fromString("11300000-0000-4000-8000-000000000101");
  private static final Instant UPDATED_AT = Instant.parse("2026-08-26T01:02:03Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private PushDeviceService deviceService;
  @MockitoBean private NotificationPreferenceService preferenceService;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void responses() {
    when(deviceService.register(any(), eq(DEVICE_ID), any()))
        .thenReturn(
            new PushDevice(
                DEVICE_ID, PushPlatform.IOS, PushPermissionStatus.GRANTED, true, UPDATED_AT));
    when(preferenceService.read(any())).thenReturn(new NotificationPreference(false, 10, null));
    when(preferenceService.update(any(), any()))
        .thenReturn(new NotificationPreference(true, 0, UPDATED_AT));
  }

  @Test
  void PUT_device는_canonical_sub로_upsert하고_token_crypto필드를_응답하지_않는다() throws Exception {
    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDeviceBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
        .andExpect(jsonPath("$.platform").value("IOS"))
        .andExpect(jsonPath("$.permissionStatus").value("GRANTED"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
        .andExpect(jsonPath("$.registrationToken").doesNotExist())
        .andExpect(jsonPath("$.tokenCiphertext").doesNotExist())
        .andExpect(jsonPath("$.tokenFingerprint").doesNotExist());

    verify(deviceService)
        .register(
            org.mockito.ArgumentMatchers.argThat(user -> USER_ID.equals(user.userId())),
            eq(DEVICE_ID),
            org.mockito.ArgumentMatchers.argThat(
                request -> "__REDACTED_REGISTRATION_TOKEN__".equals(request.registrationToken())));
  }

  @Test
  void PUT_device는_인증_canonicalUUID_closed입력과_token_timeZone경계를_검증한다() throws Exception {
    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDeviceBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer malformed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDeviceBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

    for (String path :
        new String[] {"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "not-a-uuid", DEVICE_ID + " "}) {
      mvc.perform(
              put("/api/v1/me/push-devices/{deviceId}", path)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validDeviceBody()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }

    for (String body :
        new String[] {
          "{}",
          validDeviceBody().replace("\"IOS\"", "\"WEB\""),
          validDeviceBody().replace("__REDACTED_REGISTRATION_TOKEN__", ""),
          validDeviceBody().replace("Asia/Seoul", "Invalid/Zone"),
          validDeviceBody()
              .replace(
                  "\"timeZone\":\"Asia/Seoul\"", "\"timeZone\":\"Asia/Seoul\",\"unknown\":true")
        }) {
      mvc.perform(
              put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }
  }

  @Test
  void PUT_device는_ASCII_4096byte만_service에_전달하고_초과와_Unicode를_400으로_거부한다() throws Exception {
    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(deviceBodyWithToken("A".repeat(4096))))
        .andExpect(status().isOk());

    for (String invalid : new String[] {"A".repeat(4097), "가".repeat(2000)}) {
      mvc.perform(
              put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(deviceBodyWithToken(invalid)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }
    verify(deviceService, org.mockito.Mockito.times(1)).register(any(), eq(DEVICE_ID), any());
  }

  @Test
  void PUT_device_locale은_canonical_BCP47확장과_35자를_허용하고_case_36자_invalid를_400으로_거부한다()
      throws Exception {
    for (String locale :
        new String[] {"en-US-u-ca-gregory", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-ddd"}) {
      mvc.perform(
              put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validDeviceBody().replace("ko-KR", locale)))
          .andExpect(status().isOk());
    }
    for (String locale :
        new String[] {"en-us", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-dddd", "invalid_locale"}) {
      mvc.perform(
              put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(validDeviceBody().replace("ko-KR", locale)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }
    verify(deviceService, org.mockito.Mockito.times(2)).register(any(), eq(DEVICE_ID), any());
  }

  @Test
  void DELETE_device는_자기기기만_204로_멱등해제한다() throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              delete("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
          .andExpect(status().isNoContent());
    }
    verify(deviceService, org.mockito.Mockito.times(2))
        .invalidate(
            org.mockito.ArgumentMatchers.argThat(user -> USER_ID.equals(user.userId())),
            eq(DEVICE_ID));
  }

  @Test
  void GET_preferences는_최초_false와10을_반환한다() throws Exception {
    mvc.perform(
            get("/api/v1/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextDestinationDepartureEnabled").value(false))
        .andExpect(jsonPath("$.safetyBufferMinutes").value(10))
        .andExpect(jsonPath("$.updatedAt").doesNotExist());
  }

  @Test
  void PATCH_preferences는_0과120_integer만_허용하고_boolean_decimal_overflow를_거부한다() throws Exception {
    for (int value : new int[] {0, 120}) {
      mvc.perform(
              patch("/api/v1/me/notification-preferences")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"safetyBufferMinutes\":" + value + "}"))
          .andExpect(status().isOk());
    }
    for (String body :
        new String[] {
          "{}",
          "{\"safetyBufferMinutes\":-1}",
          "{\"safetyBufferMinutes\":121}",
          "{\"safetyBufferMinutes\":true}",
          "{\"safetyBufferMinutes\":1.5}",
          "{\"safetyBufferMinutes\":2147483648}",
          "{\"nextDestinationDepartureEnabled\":null}",
          "{\"unknown\":true}"
        }) {
      mvc.perform(
              patch("/api/v1/me/notification-preferences")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }
  }

  @Test
  void 네_endpoint는_data_unavailable을_503_stable_problem으로_반환한다() throws Exception {
    when(deviceService.register(any(), eq(DEVICE_ID), any()))
        .thenThrow(PushNotificationException.dataUnavailable());
    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDeviceBody()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PUSH_NOTIFICATION_DATA_UNAVAILABLE"));

    doThrow(PushNotificationException.dataUnavailable())
        .when(deviceService)
        .invalidate(any(), eq(DEVICE_ID));
    mvc.perform(
            delete("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PUSH_NOTIFICATION_DATA_UNAVAILABLE"));

    when(preferenceService.read(any())).thenThrow(PushNotificationException.dataUnavailable());
    mvc.perform(
            get("/api/v1/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PUSH_NOTIFICATION_DATA_UNAVAILABLE"));

    when(preferenceService.update(any(), any()))
        .thenThrow(PushNotificationException.dataUnavailable());
    mvc.perform(
            patch("/api/v1/me/notification-preferences")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nextDestinationDepartureEnabled\":true}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PUSH_NOTIFICATION_DATA_UNAVAILABLE"));
  }

  @Test
  void programmer_failure는_503으로_숨기지_않고_global_500경계로_전달한다() throws Exception {
    when(deviceService.register(any(), eq(DEVICE_ID), any()))
        .thenThrow(new IllegalStateException("programmer failure"));

    mvc.perform(
            put("/api/v1/me/push-devices/{deviceId}", DEVICE_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validDeviceBody()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.cause").doesNotExist())
        .andExpect(jsonPath("$.providerMessage").doesNotExist());
  }

  @Test
  void OpenAPI는_세경로와_닫힌_schema_token비노출을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.operationId")
                .value("pushDevicesUpdate"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.tags[0]").value("푸시 알림"))
        .andExpect(jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.summary").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].delete.summary").exists())
        .andExpect(jsonPath("$.paths['/api/v1/me/notification-preferences'].get.summary").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/me/notification-preferences'].patch.summary").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.parameters[0].in")
                .value("path"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.parameters[0].required")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.parameters[0].schema.format")
                .value("uuid"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.parameters[0].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.parameters[0].example")
                .value(DEVICE_ID.toString()))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.requestBody.content['application/json'].example.registrationToken")
                .value("__REDACTED_REGISTRATION_TOKEN__"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.responses['200'].content['application/json'].example.deviceId")
                .value(DEVICE_ID.toString()))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].delete.parameters[0].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/PushDeviceRegistrationRequest"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/PushDeviceResponse"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/notification-preferences'].get.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/NotificationPreferenceResponse"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/notification-preferences'].patch.requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/NotificationPreferencePatchRequest"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/notification-preferences'].patch.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/NotificationPreferenceResponse"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].put.responses.keys()")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "200", "400", "401", "403", "500", "503")))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/push-devices/{deviceId}'].delete.responses.keys()")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "204", "400", "401", "403", "500", "503")))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/notification-preferences'].get.responses.keys()")
                .value(org.hamcrest.Matchers.containsInAnyOrder("200", "401", "403", "500", "503")))
        .andExpect(
            jsonPath("$.paths['/api/v1/me/notification-preferences'].patch.responses.keys()")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "200", "400", "401", "403", "500", "503")))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/notification-preferences'].get.responses['503'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/push-devices/{deviceId}'].put.responses['400'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(
            jsonPath("$.components.schemas.PushDeviceRegistrationRequest.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath(
                    "$.components.schemas.PushDeviceRegistrationRequest.properties.registrationToken.pattern")
                .value("^[!-~]{1,4096}$"))
        .andExpect(
            jsonPath(
                    "$.components.schemas.PushDeviceRegistrationRequest.properties.registrationToken.maxLength")
                .value(4096))
        .andExpect(
            jsonPath(
                    "$.components.schemas.PushDeviceRegistrationRequest.properties.locale.maxLength")
                .value(35))
        .andExpect(
            jsonPath("$.components.schemas.PushDeviceRegistrationRequest.properties.locale.example")
                .value("en-US-u-ca-gregory"))
        .andExpect(
            jsonPath("$.components.schemas.PushDeviceResponse.properties.registrationToken")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.PushDeviceResponse.properties.tokenCiphertext")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.PushDeviceResponse.properties.tokenFingerprint")
                .doesNotExist());
  }

  @Test
  void OpenAPI는_네_operation의_status_media_schema_stableCode_example과_204무본문을_고정한다()
      throws Exception {
    JsonNode root =
        new ObjectMapper()
            .readTree(
                mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray());
    Map<String, Map<String, String>> operations = new LinkedHashMap<>();
    operations.put(
        "put /api/v1/me/push-devices/{deviceId}",
        Map.of(
            "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
            "401", "AUTHENTICATION_REQUIRED",
            "403", "AUTH_ACCESS_DENIED",
            "500", "INTERNAL_SERVER_ERROR",
            "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE"));
    operations.put(
        "delete /api/v1/me/push-devices/{deviceId}",
        Map.of(
            "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
            "401", "AUTHENTICATION_REQUIRED",
            "403", "AUTH_ACCESS_DENIED",
            "500", "INTERNAL_SERVER_ERROR",
            "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE"));
    operations.put(
        "get /api/v1/me/notification-preferences",
        Map.of(
            "401", "AUTHENTICATION_REQUIRED",
            "403", "AUTH_ACCESS_DENIED",
            "500", "INTERNAL_SERVER_ERROR",
            "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE"));
    operations.put(
        "patch /api/v1/me/notification-preferences",
        Map.of(
            "400", "INVALID_PUSH_NOTIFICATION_REQUEST",
            "401", "AUTHENTICATION_REQUIRED",
            "403", "AUTH_ACCESS_DENIED",
            "500", "INTERNAL_SERVER_ERROR",
            "503", "PUSH_NOTIFICATION_DATA_UNAVAILABLE"));

    for (var operationEntry : operations.entrySet()) {
      String[] identity = operationEntry.getKey().split(" ", 2);
      JsonNode responses = root.path("paths").path(identity[1]).path(identity[0]).path("responses");
      for (var problem : operationEntry.getValue().entrySet()) {
        JsonNode response = responses.path(problem.getKey());
        assertThat(response.path("description").asText()).contains(problem.getValue());
        assertThat(
                response
                    .path("content")
                    .path("application/problem+json")
                    .path("schema")
                    .path("$ref")
                    .asText())
            .isEqualTo("#/components/schemas/ApiProblemDetails");
        assertThat(
                response
                    .path("content")
                    .path("application/problem+json")
                    .path("example")
                    .path("code")
                    .asText())
            .isEqualTo(problem.getValue());
        assertThat(
                response
                    .path("content")
                    .path("application/problem+json")
                    .path("example")
                    .fieldNames())
            .toIterable()
            .containsExactlyInAnyOrder(
                "type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors");
      }
    }
    JsonNode noContent =
        root.path("paths")
            .path("/api/v1/me/push-devices/{deviceId}")
            .path("delete")
            .path("responses")
            .path("204");
    assertThat(noContent.path("description").asText()).contains("204");
    assertThat(noContent.has("content")).isFalse();
  }

  private static String validDeviceBody() {
    return """
        {"platform":"IOS","registrationToken":"__REDACTED_REGISTRATION_TOKEN__",
         "permissionStatus":"GRANTED","appVersion":"1.2.3","locale":"ko-KR",
         "timeZone":"Asia/Seoul"}
        """;
  }

  private static String deviceBodyWithToken(String token) {
    return validDeviceBody().replace("__REDACTED_REGISTRATION_TOKEN__", token);
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
