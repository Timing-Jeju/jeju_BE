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
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.service.TripPlacePreferencesService;
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
      "timing-jeju.test.context=trip-place-preferences-controller"
    })
@AutoConfigureMockMvc
class TripPlacePreferencesControllerIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER = UUID.fromString("48000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("48000000-0000-0000-0000-000000000002");
  private static final UUID PLACE_A = UUID.fromString("48000000-0000-0000-0000-000000000010");
  private static final UUID PLACE_B = UUID.fromString("48000000-0000-0000-0000-000000000011");
  private static final Instant UPDATED_AT = Instant.parse("2026-09-01T03:04:05.123456Z");
  private static final String IF_MATCH = "\"trip-current-v1\"";

  @Autowired private MockMvc mvc;
  @MockitoBean private TripPlacePreferencesService service;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    reset(service);
    when(service.replace(any(), eq(TRIP), eq(IF_MATCH), any())).thenReturn(success());
  }

  @Test
  void PUT_place_preferences는_전체교체하고_closed_response와_새_ETag를_반환한다() throws Exception {
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
        .andExpect(jsonPath("$.updatedAt").value("2026-09-01T03:04:05.123456Z"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0]").value(org.hamcrest.Matchers.aMapWithSize(4)))
        .andExpect(jsonPath("$.items[0].placeId").value(PLACE_A.toString()))
        .andExpect(jsonPath("$.items[0].type").value("must_visit"))
        .andExpect(jsonPath("$.items[0].targetDayNo").value(2))
        .andExpect(jsonPath("$.items[0].priority").value(90))
        .andExpect(jsonPath("$.items[1].targetDayNo").isEmpty());
  }

  @Test
  void PUT_place_preferences는_인증되지_않은_요청을_서비스_호출_전에_거부한다() throws Exception {
    mvc.perform(
            put("/api/v1/trips/{tripId}/place-preferences", TRIP)
                .header(HttpHeaders.IF_MATCH, IF_MATCH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(service);
  }

  @Test
  void PUT_place_preferences는_누락_null_unknown_타입_UUID와_약한_IfMatch를_400으로_거부한다() throws Exception {
    for (String invalidBody :
        List.of(
            "{}",
            "{\"items\":null}",
            validBody().replace("\"targetDayNo\":2,", ""),
            validBody().replace("\"priority\":90", "\"priority\":90,\"unknown\":true"),
            validBody().replace("\"priority\":90", "\"priority\":\"90\""),
            validBody().replace(PLACE_A.toString(), "A8000000-0000-0000-0000-000000000010"))) {
      mvc.perform(
              put("/api/v1/trips/{tripId}/place-preferences", TRIP)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header(HttpHeaders.IF_MATCH, IF_MATCH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidBody))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    mvc.perform(
            put("/api/v1/trips/{tripId}/place-preferences", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, "W/\"trip-current-v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(validRequest().queryParam("unknown", "true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void PUT_place_preferences는_domain_제약_owner은닉_version_terminal을_정해진_problem으로_반환한다()
      throws Exception {
    for (String code :
        List.of(
            "PLACE_PREFERENCE_CONSTRAINT_VIOLATION",
            "TRIP_NOT_FOUND",
            "PLACE_NOT_FOUND",
            "TRIP_VERSION_CONFLICT",
            "TRIP_TERMINAL_STATE_CONFLICT")) {
      reset(service);
      when(service.replace(any(), eq(TRIP), eq(IF_MATCH), any())).thenThrow(exception(code));
      mvc.perform(validRequest())
          .andExpect(status().is(expectedStatus(code)))
          .andExpect(jsonPath("$.code").value(code))
          .andExpect(jsonPath("$.detail").isNotEmpty())
          .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest()
      throws Exception {
    return put("/api/v1/trips/{tripId}/place-preferences", TRIP)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, IF_MATCH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody());
  }

  private static String validBody() {
    return """
        {
          "items":[
            {"placeId":"%s","type":"must_visit","targetDayNo":2,"priority":90},
            {"placeId":"%s","type":"avoid","targetDayNo":null,"priority":10}
          ]
        }
        """
        .formatted(PLACE_A, PLACE_B);
  }

  private static TripPlacePreferencesMutation success() {
    return new TripPlacePreferencesMutation(
        TRIP,
        "none",
        false,
        null,
        "draft",
        UPDATED_AT,
        List.of(
            new TripPlacePreference(PLACE_A, "must_visit", 2, 90),
            new TripPlacePreference(PLACE_B, "avoid", null, 10)));
  }

  private static TripException exception(String code) {
    return switch (code) {
      case "PLACE_PREFERENCE_CONSTRAINT_VIOLATION" ->
          TripException.placePreferenceConstraintViolation();
      case "TRIP_NOT_FOUND" -> TripException.notFound();
      case "PLACE_NOT_FOUND" -> TripException.placeNotFound();
      case "TRIP_VERSION_CONFLICT" -> TripException.versionConflict();
      case "TRIP_TERMINAL_STATE_CONFLICT" -> TripException.terminalStateConflict();
      default -> throw new IllegalArgumentException(code);
    };
  }

  private static int expectedStatus(String code) {
    return switch (code) {
      case "PLACE_PREFERENCE_CONSTRAINT_VIOLATION" -> 422;
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
