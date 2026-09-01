package com.timingjeju.api.domain.trip.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferences;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.service.TripPreferencesService;
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
      "timing-jeju.test.context=trip-preferences-controller"
    })
@AutoConfigureMockMvc
class TripPreferencesControllerIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER = UUID.fromString("46000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000002");
  private static final Instant UPDATED_AT = Instant.parse("2026-09-01T01:02:03Z");
  private static final String IF_MATCH = "\"trip-current-v1\"";

  @Autowired private MockMvc mvc;
  @MockitoBean private TripPreferencesService preferences;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    reset(preferences);
    when(preferences.replace(any(), eq(TRIP), eq(IF_MATCH), any())).thenReturn(success());
  }

  @Test
  void PUT_preferences는_7개_필드를_전체교체하고_closed_response와_새_ETag를_반환한다() throws Exception {
    mvc.perform(validRequest())
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.ETAG,
                    org.hamcrest.Matchers.matchesPattern("^\"[A-Za-z0-9._:-]{1,128}\"$")))
        .andExpect(jsonPath("$").value(org.hamcrest.Matchers.aMapWithSize(7)))
        .andExpect(jsonPath("$.tripId").value(TRIP.toString()))
        .andExpect(jsonPath("$.scheduleEffect").value("none"))
        .andExpect(jsonPath("$.regenerationRequired").value(false))
        .andExpect(jsonPath("$.activeScheduleVersionId").isEmpty())
        .andExpect(jsonPath("$.tripStatus").value("draft"))
        .andExpect(jsonPath("$.updatedAt").value("2026-09-01T01:02:03Z"))
        .andExpect(jsonPath("$.preferences").value(org.hamcrest.Matchers.aMapWithSize(7)))
        .andExpect(jsonPath("$.preferences.transportModes.length()").value(3))
        .andExpect(jsonPath("$.preferences.transportModes[0].mode").value("public_transit"))
        .andExpect(jsonPath("$.preferences.transportModes[0].primary").value(true));
  }

  @Test
  void PUT_preferences는_인증되지_않은_요청을_서비스_호출_전에_거부한다() throws Exception {
    mvc.perform(
            put("/api/v1/trips/{tripId}/preferences", TRIP)
                .header(HttpHeaders.IF_MATCH, IF_MATCH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(preferences);
  }

  @Test
  void PUT_preferences는_누락_null_unknown_타입과_약한_IfMatch를_400으로_거부한다() throws Exception {
    for (String invalidBody :
        List.of(
            "{}",
            validBody()
                .replace(
                    "\"preferredCategories\":[\"tourist_attraction\",\"cafe\"]",
                    "\"preferredCategories\":null"),
            validBody().replace("\"arrivalRegionCode\":\"jeju-si\"", "\"arrivalRegionCode\":1"),
            validBody().replace("\"endPlaceId\":null", "\"endPlaceId\":null,\"unknown\":true"),
            validBody().replace("\"primary\":true", "\"primary\":null"))) {
      mvc.perform(
              put("/api/v1/trips/{tripId}/preferences", TRIP)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header(HttpHeaders.IF_MATCH, IF_MATCH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidBody))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    mvc.perform(
            put("/api/v1/trips/{tripId}/preferences", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, "W/\"trip-current-v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(preferences);
  }

  @Test
  void PUT_preferences는_domain_constraint와_owner은닉과_version_terminal충돌을_problem으로_반환한다()
      throws Exception {
    for (String code :
        List.of(
            "PREFERENCE_CONSTRAINT_VIOLATION",
            "TRIP_NOT_FOUND",
            "PLACE_NOT_FOUND",
            "TRIP_VERSION_CONFLICT",
            "TRIP_TERMINAL_STATE_CONFLICT")) {
      reset(preferences);
      when(preferences.replace(any(), eq(TRIP), eq(IF_MATCH), any())).thenThrow(exception(code));
      mvc.perform(validRequest())
          .andExpect(status().is(expectedStatus(code)))
          .andExpect(jsonPath("$.code").value(code));
    }
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest()
      throws Exception {
    return put("/api/v1/trips/{tripId}/preferences", TRIP)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, IF_MATCH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody());
  }

  private static String validBody() {
    return """
        {
          "preferredCategories":["tourist_attraction","cafe"],
          "arrivalRegionCode":"jeju-si",
          "departureRegionCode":"seogwipo-si",
          "preferredRegionCodes":["seongsan","aewol"],
          "startPlaceId":null,
          "endPlaceId":null,
          "transportModes":[
            {"mode":"public_transit","priority":1,"primary":true},
            {"mode":"rental_car","priority":2,"primary":false},
            {"mode":"taxi","priority":3,"primary":false}
          ]
        }
        """;
  }

  private static TripPreferencesMutation success() {
    TripPreferences details =
        new TripPreferences(
            List.of("tourist_attraction", "cafe"),
            "jeju-si",
            "seogwipo-si",
            List.of("seongsan", "aewol"),
            null,
            null,
            List.of(
                new TripTransportMode("public_transit", 1, true),
                new TripTransportMode("rental_car", 2, false),
                new TripTransportMode("taxi", 3, false)));
    return new TripPreferencesMutation(TRIP, "none", false, null, "draft", UPDATED_AT, details);
  }

  private static TripException exception(String code) {
    return switch (code) {
      case "PREFERENCE_CONSTRAINT_VIOLATION" -> TripException.preferenceConstraintViolation();
      case "TRIP_NOT_FOUND" -> TripException.notFound();
      case "PLACE_NOT_FOUND" -> TripException.placeNotFound();
      case "TRIP_VERSION_CONFLICT" -> TripException.versionConflict();
      case "TRIP_TERMINAL_STATE_CONFLICT" -> TripException.terminalStateConflict();
      default -> throw new IllegalArgumentException(code);
    };
  }

  private static int expectedStatus(String code) {
    return switch (code) {
      case "PREFERENCE_CONSTRAINT_VIOLATION" -> 422;
      case "TRIP_NOT_FOUND", "PLACE_NOT_FOUND" -> 404;
      case "TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT" -> 409;
      default -> throw new IllegalArgumentException(code);
    };
  }

  private static String token() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(USER.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
