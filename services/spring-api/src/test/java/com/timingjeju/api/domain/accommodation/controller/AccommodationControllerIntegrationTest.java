package com.timingjeju.api.domain.accommodation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpResult;
import com.timingjeju.api.application.accommodation.AccommodationHttpSnapshot;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
      "timing-jeju.test.context=accommodation-controller"
    })
@AutoConfigureMockMvc
class AccommodationControllerIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER = UUID.fromString("68000000-0000-0000-0000-000000000201");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000202");
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000203");
  private static final String ETAG = "\"trip-1\"";

  @Autowired private MockMvc mvc;
  @MockitoBean private AccommodationService service;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    when(service.create(any(), any(), anyString(), any(), any())).thenReturn(result(201, true));
    when(service.patch(any(), any(), any(), any(), any())).thenReturn(result(200, false));
  }

  @Test
  void POST는_6개_presence와_headers를_command로_변환하고_exact_replay응답을_전달한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "accommodation-key:1")
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.ETAG, ETAG))
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    "/api/v1/trips/" + TRIP + "/accommodations/" + ACCOMMODATION))
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(content().json("{\"accommodationId\":\"" + ACCOMMODATION + "\"}"));

    ArgumentCaptor<CreateAccommodationCommand> command =
        ArgumentCaptor.forClass(CreateAccommodationCommand.class);
    verify(service).create(eq(USER), eq(TRIP), eq("accommodation-key:1"), any(), command.capture());
    assertThat(command.getValue().customName()).isEqualTo("제주 숙소");
    assertThat(command.getValue().placeId()).isNull();
  }

  @Test
  void POST는_누락_unknown_noncanonical형식과_약한_ETag를_400으로_거부한다() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"placeId\":null,\"customName\":\"숙소\",\"checkInDate\":\"2026-09-01\",\"checkOutDate\":\"2026-09-02\",\"checkInTime\":\"15:00\"}",
            validBody().replace("}", ",\"unknown\":true}"),
            validBody().replace("15:00", "15:00:00"),
            validBody().replace("2026-09-01", "2026-9-1"))) {
      reset(service);
      mvc.perform(
              post("/api/v1/trips/{tripId}/accommodations", TRIP)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header("Idempotency-Key", "valid-key")
                  .header(HttpHeaders.IF_MATCH, ETAG)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(service);
    }

    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "valid-key")
                .header(HttpHeaders.IF_MATCH, "W/" + ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void PATCH는_presence_identity_null을_보존하고_Idempotency헤더를_반환하지않는다() throws Exception {
    mvc.perform(
            patch("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"placeId":"68000000-0000-0000-0000-000000000204","customName":null}
                    """))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotency-Replayed"))
        .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    verify(service).patch(eq(USER), eq(TRIP), eq(ACCOMMODATION), any(), any());
  }

  @Test
  void PATCH_empty와_DELETE_body_query는_service전에_400으로_거부되고_정상_DELETE는_204다() throws Exception {
    mvc.perform(
            patch("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mvc.perform(
            delete("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            delete("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .queryParam("force", "true"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            delete("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(service).delete(eq(USER), eq(TRIP), eq(ACCOMMODATION), any());
  }

  @Test
  void lowercase_UUID와_인증을_강제하고_domain오류는_canonical_problem으로_변환한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", "A8000000-0000-0000-0000-000000000202")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "valid-key")
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header("Idempotency-Key", "valid-key")
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

    reset(service);
    when(service.create(any(), any(), anyString(), any(), any()))
        .thenThrow(AccommodationException.of("ACCOMMODATION_DATE_GAP_OR_OVERLAP"));
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", "valid-key")
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ACCOMMODATION_DATE_GAP_OR_OVERLAP"))
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  private static AccommodationHttpResult result(int status, boolean replayed) {
    String location =
        status == 201 ? "/api/v1/trips/" + TRIP + "/accommodations/" + ACCOMMODATION : null;
    return new AccommodationHttpResult(
        new AccommodationHttpSnapshot(
            status,
            "application/json",
            location,
            ETAG,
            ("{\"accommodationId\":\"" + ACCOMMODATION + "\"}").getBytes(StandardCharsets.UTF_8)),
        replayed);
  }

  private static String validBody() {
    return """
        {"placeId":null,"customName":"제주 숙소","checkInDate":"2026-09-01","checkOutDate":"2026-09-02","checkInTime":"15:00","checkOutTime":"11:00"}
        """;
  }

  private static String token() throws Exception {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(USER.toString())
                .issuer(ISSUER)
                .audience("authenticated")
                .claim("role", "authenticated")
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .build());
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
