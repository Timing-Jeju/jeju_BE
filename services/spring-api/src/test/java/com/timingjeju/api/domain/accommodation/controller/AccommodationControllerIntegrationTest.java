package com.timingjeju.api.domain.accommodation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
  private static final String ETAG = "\"trip-" + TRIP + "-r1\"";
  private static final String IDEMPOTENCY_KEY = "68abcdef-0000-0000-0000-000000000204";

  @Autowired private MockMvc mvc;
  @MockitoBean private AccommodationService service;
  @MockitoBean private CurrentUserAccessor currentUsers;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    when(currentUsers.getRequired())
        .thenReturn(new CurrentUser(USER, AuthenticatedRole.AUTHENTICATED, null));
    when(service.create(any(), any(), anyString(), anyLong(), any())).thenReturn(result(201, true));
    when(service.patch(any(), any(), any(), anyLong(), any())).thenReturn(result(200, false));
  }

  @Test
  void POST_PATCH는_byte_array_argument없이_bounded_raw_stream을_직접읽는다() {
    for (String methodName : List.of("create", "patch")) {
      var method =
          java.util.Arrays.stream(AccommodationController.class.getDeclaredMethods())
              .filter(candidate -> candidate.getName().equals(methodName))
              .findFirst()
              .orElseThrow();

      assertThat(method.getParameterTypes())
          .as(methodName)
          .contains(jakarta.servlet.http.HttpServletRequest.class)
          .doesNotContain(byte[].class);
    }
  }

  @Test
  void POST_PATCH는_max_body만_허용하고_duplicate_JSON과_max_plus_one을_auth전에_거부한다() throws Exception {
    mvc.perform(validPost().content(bodyAtSize(IdempotencyRequest.MAX_BODY_BYTES)))
        .andExpect(status().isCreated());
    mvc.perform(validPatch().content(bodyAtSize(IdempotencyRequest.MAX_BODY_BYTES)))
        .andExpect(status().isOk());

    reset(service, currentUsers);
    for (MockHttpServletRequestBuilder request :
        List.of(
            validPost().content(bodyAtSize(IdempotencyRequest.MAX_BODY_BYTES + 1)),
            validPatch().content(bodyAtSize(IdempotencyRequest.MAX_BODY_BYTES + 1)),
            validPost().content(validBody().replace("{", "{\"customName\":\"중복\",")),
            validPatch().content("{\"customName\":\"첫째\",\"customName\":\"둘째\"}"))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH는_정상_JSON뒤_trailing_token을_auth전에_거부한다() throws Exception {
    for (MockHttpServletRequestBuilder request : List.of(validPost(), validPatch())) {
      mvc.perform(request.content(validBody() + " true"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH는_declared_length와_transfer_encoding을_auth전에_fail_closed한다() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            validPost().header(HttpHeaders.CONTENT_LENGTH, "-1"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "+0"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "00"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "zero"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "0", "0"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "0,0"),
            validPost().header(HttpHeaders.CONTENT_LENGTH, "9223372036854775808"),
            validPost().header(HttpHeaders.TRANSFER_ENCODING, "chunked"),
            validPatch().header(HttpHeaders.CONTENT_LENGTH, "-1"),
            validPatch().header(HttpHeaders.CONTENT_LENGTH, "0", "0"),
            validPatch().header(HttpHeaders.TRANSFER_ENCODING, "gzip"))) {
      mvc.perform(request.content(validBody()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH는_unknown_length_stream도_max_plus_one까지만_probe하고_auth전에_거부한다() throws Exception {
    for (MockHttpServletRequestBuilder request : List.of(validPost(), validPatch())) {
      mvc.perform(
              request
                  .content(bodyAtSize(IdempotencyRequest.MAX_BODY_BYTES + 1))
                  .with(
                      raw -> {
                        raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                        return new ReportedContentLengthRequest(raw, -1);
                      }))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH는_raw_CL없이_servlet_length가_actual과_다르면_auth전에_400이다() throws Exception {
    reset(service, currentUsers);
    int actualLength = validBody().getBytes(StandardCharsets.UTF_8).length;
    for (int reported : List.of(0, 1, actualLength - 1)) {
      for (MockHttpServletRequestBuilder request : List.of(validPost(), validPatch())) {
        mvc.perform(
                request
                    .content(validBody())
                    .with(
                        raw -> {
                          raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                          return new ReportedContentLengthRequest(raw, reported);
                        }))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      }
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH는_raw_CL없는_unknown_length의_bounded_valid_JSON을_허용한다() throws Exception {
    for (MockHttpServletRequestBuilder request : List.of(validPost(), validPatch())) {
      mvc.perform(
              request
                  .content(validBody())
                  .with(
                      raw -> {
                        raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                        return new ReportedContentLengthRequest(raw, -1);
                      }))
          .andExpect(status().is2xxSuccessful());
    }
    verify(currentUsers, org.mockito.Mockito.times(2)).getRequired();
    verify(service).create(eq(USER), eq(TRIP), eq(IDEMPOTENCY_KEY), eq(1L), any());
    verify(service).patch(eq(USER), eq(TRIP), eq(ACCOMMODATION), eq(1L), any());
  }

  @Test
  void raw_CL이_있고_servlet_length가_unknown이면_POST_PATCH_DELETE모두_auth전에_400이다() throws Exception {
    int actualLength = validBody().getBytes(StandardCharsets.UTF_8).length;
    for (MockHttpServletRequestBuilder request :
        List.of(
            validPost()
                .content(validBody())
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(actualLength)),
            validPatch()
                .content(validBody())
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(actualLength)),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "0"))) {
      mvc.perform(request.with(raw -> new ReportedContentLengthRequest(raw, -1)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void POST_PATCH의_path_header_query_JSON_syntax오류는_auth_service전에_400이다() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            validPost("A8000000-0000-0000-0000-000000000202").content(validBody()),
            validPost().header(HttpHeaders.IF_MATCH, "W/\"trip-1\"").content(validBody()),
            validPost().header(HttpHeaders.IF_MATCH, ETAG).content(validBody()),
            validPost().header(HttpHeaders.IF_MATCH, ETAG + "," + ETAG).content(validBody()),
            validPost().header("Idempotency-Key", "space is invalid").content(validBody()),
            validPost().header("Idempotency-Key", IDEMPOTENCY_KEY).content(validBody()),
            validPost()
                .header("Idempotency-Key", IDEMPOTENCY_KEY + "," + IDEMPOTENCY_KEY)
                .content(validBody()),
            validPost()
                .header("Idempotency-Key", IDEMPOTENCY_KEY.toUpperCase())
                .content(validBody()),
            validPost().queryParam("unknown", "1").content(validBody()),
            validPost().content("{"),
            validPatch("A8000000-0000-0000-0000-000000000203").content(validBody()),
            validPatch().header(HttpHeaders.IF_MATCH, "trip-1").content(validBody()),
            validPatch().header(HttpHeaders.IF_MATCH, ETAG).content(validBody()),
            validPatch().header(HttpHeaders.IF_MATCH, ETAG + "," + ETAG).content(validBody()),
            validPatch().queryParam("unknown", "1", "2").content(validBody()),
            validPatch().content("{"))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void DELETE는_query_transfer_encoding_raw_content_length를_auth전에_400으로_거부한다() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            validDelete().queryParam("unknown", "1"),
            validDelete().queryParam("unknown", "1", "2"),
            validDelete().header(HttpHeaders.TRANSFER_ENCODING, "chunked"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "-1"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "+0"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "00"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "1"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "zero"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "9223372036854775808"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "0", "0"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "0,0"),
            validDelete().with(raw -> new ReportedContentLengthRequest(raw, -2)),
            validDelete().with(raw -> new ReportedContentLengthRequest(raw, 1)))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void DELETE는_CL0_unknown_length를_한_byte_probe하고_EOF만_204로_보존한다() throws Exception {
    for (int reported : List.of(0, -1)) {
      reset(service, currentUsers);
      mvc.perform(
              validDelete()
                  .content(new byte[] {'x'})
                  .with(
                      raw -> {
                        raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                        if (reported == 0) raw.addHeader(HttpHeaders.CONTENT_LENGTH, "0");
                        return new ReportedContentLengthRequest(raw, reported);
                      }))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(currentUsers, service);
    }

    reset(service, currentUsers);
    mvc.perform(validDelete().with(raw -> new FailingInputStreamRequest(raw)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(currentUsers, service);

    when(currentUsers.getRequired())
        .thenReturn(new CurrentUser(USER, AuthenticatedRole.AUTHENTICATED, null));
    mvc.perform(
            validDelete()
                .with(
                    raw -> {
                      raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                      return new ReportedContentLengthRequest(raw, -1);
                    }))
        .andExpect(status().isNoContent());
    mvc.perform(
            validDelete()
                .header(HttpHeaders.CONTENT_LENGTH, "0")
                .with(raw -> new ReportedContentLengthRequest(raw, 0)))
        .andExpect(status().isNoContent());
    verify(service, org.mockito.Mockito.times(2))
        .delete(eq(USER), eq(TRIP), eq(ACCOMMODATION), eq(1L));
  }

  @Test
  void POST는_6개_presence와_headers를_command로_변환하고_exact_replay응답을_전달한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
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
    verify(service).create(eq(USER), eq(TRIP), eq(IDEMPOTENCY_KEY), anyLong(), command.capture());
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
                  .header("Idempotency-Key", IDEMPOTENCY_KEY)
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
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
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
    verify(service).patch(eq(USER), eq(TRIP), eq(ACCOMMODATION), anyLong(), any());
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
    verify(service).delete(eq(USER), eq(TRIP), eq(ACCOMMODATION), anyLong());
  }

  @Test
  void lowercase_UUID와_인증을_강제하고_domain오류는_canonical_problem으로_변환한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", "A8000000-0000-0000-0000-000000000202")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

    reset(service);
    when(service.create(any(), any(), anyString(), anyLong(), any()))
        .thenThrow(AccommodationException.of("ACCOMMODATION_DATE_GAP_OR_OVERLAP"));
    mvc.perform(
            post("/api/v1/trips/{tripId}/accommodations", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
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

  private static byte[] bodyAtSize(int size) {
    byte[] body = validBody().getBytes(StandardCharsets.UTF_8);
    if (body.length > size) throw new IllegalArgumentException("size가 canonical body보다 작습니다.");
    byte[] result = new byte[size];
    System.arraycopy(body, 0, result, 0, body.length);
    java.util.Arrays.fill(result, body.length, size, (byte) ' ');
    return result;
  }

  private static MockHttpServletRequestBuilder validPost() throws Exception {
    return validPost(TRIP.toString());
  }

  private static MockHttpServletRequestBuilder validPost(String tripId) throws Exception {
    return post("/api/v1/trips/{tripId}/accommodations", tripId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header("Idempotency-Key", IDEMPOTENCY_KEY)
        .header(HttpHeaders.IF_MATCH, ETAG)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private static MockHttpServletRequestBuilder validPatch() throws Exception {
    return validPatch(ACCOMMODATION.toString());
  }

  private static MockHttpServletRequestBuilder validPatch(String accommodationId) throws Exception {
    return patch("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, accommodationId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, ETAG)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private static MockHttpServletRequestBuilder validDelete() throws Exception {
    return delete("/api/v1/trips/{tripId}/accommodations/{accommodationId}", TRIP, ACCOMMODATION)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, ETAG);
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

  private static class ReportedContentLengthRequest extends MockHttpServletRequest {
    private final int reportedContentLength;

    private ReportedContentLengthRequest(MockHttpServletRequest source, int reportedContentLength) {
      super(source.getServletContext());
      this.reportedContentLength = reportedContentLength;
      setMethod(source.getMethod());
      setRequestURI(source.getRequestURI());
      setContextPath(source.getContextPath());
      setServletPath(source.getServletPath());
      setPathInfo(source.getPathInfo());
      setQueryString(source.getQueryString());
      setScheme(source.getScheme());
      setServerName(source.getServerName());
      setServerPort(source.getServerPort());
      setContent(source.getContentAsByteArray());
      Collections.list(source.getHeaderNames())
          .forEach(
              name ->
                  Collections.list(source.getHeaders(name))
                      .forEach(value -> addHeader(name, value)));
    }

    @Override
    public int getContentLength() {
      return reportedContentLength;
    }

    @Override
    public long getContentLengthLong() {
      return reportedContentLength;
    }
  }

  private static final class FailingInputStreamRequest extends ReportedContentLengthRequest {
    private FailingInputStreamRequest(MockHttpServletRequest source) {
      super(source, -1);
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ServletInputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("test-only read failure");
        }

        @Override
        public boolean isFinished() {
          return false;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {}
      };
    }
  }
}
