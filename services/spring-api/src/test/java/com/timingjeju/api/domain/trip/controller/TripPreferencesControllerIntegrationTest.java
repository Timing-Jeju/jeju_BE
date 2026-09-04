package com.timingjeju.api.domain.trip.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.service.TripService;
import com.timingjeju.api.global.logging.RequestTraceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000046");
  private static final String EXPECTED_ETAG = "\"trip-" + TRIP + "-r7\"";
  private static final String RESULT_ETAG = "\"trip-" + TRIP + "-r8\"";
  private static final Instant UPDATED = Instant.parse("2026-09-02T00:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private TripService service;
  @MockitoBean private CurrentUserAccessor currentUsers;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    CurrentUser user = new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);
    when(currentUsers.getRequired()).thenReturn(user);
    when(service.replacePreferences(eq(user), eq(TRIP), eq(7L), any()))
        .thenReturn(
            new TripPreferencesMutation(
                TRIP, command(), 8, UPDATED, "none", false, null, "draft", RESULT_ETAG));
  }

  @Test
  void 성공은_200_new_ETag_flat_body이며_Idempotency응답header가없다() throws Exception {
    mvc.perform(valid())
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, RESULT_ETAG))
        .andExpect(header().doesNotExist("Idempotency-Replayed"))
        .andExpect(jsonPath("$.tripId").value(TRIP.toString()))
        .andExpect(jsonPath("$.scheduleEffect").value("none"))
        .andExpect(jsonPath("$.preferences.transportModes[0].mode").value("public_transit"));

    verify(service).replacePreferences(any(), eq(TRIP), eq(7L), any());
  }

  @Test
  void malformed_path_query_IfMatch와_media_type은_auth와service전에400이다() throws Exception {
    List<MockHttpServletRequestBuilder> invalid =
        List.of(
            request("ABCDEF00-0000-0000-0000-000000000046", validBody())
                .header(HttpHeaders.IF_MATCH, EXPECTED_ETAG),
            valid().queryParam("unexpected", "1"),
            request(TRIP.toString(), validBody()),
            request(TRIP.toString(), validBody())
                .header(HttpHeaders.IF_MATCH, "W/" + EXPECTED_ETAG),
            request(TRIP.toString(), validBody())
                .header(HttpHeaders.IF_MATCH, "\"trip-" + TRIP + "-r0\""),
            request(TRIP.toString(), validBody()).header(HttpHeaders.IF_MATCH, "\"opaque\""),
            request(TRIP.toString(), validBody())
                .header(HttpHeaders.IF_MATCH, EXPECTED_ETAG, EXPECTED_ETAG),
            request(TRIP.toString(), validBody())
                .header(HttpHeaders.IF_MATCH, EXPECTED_ETAG + "," + RESULT_ETAG),
            valid().contentType(MediaType.TEXT_PLAIN));
    reset(currentUsers, service);

    for (MockHttpServletRequestBuilder candidate : invalid) {
      mvc.perform(candidate)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void malformed_duplicate_unknown_missing_null_trailing과_maxPlus1은_auth와service전에400이다()
      throws Exception {
    String body = validBody();
    List<byte[]> invalid =
        List.of(
            "".getBytes(StandardCharsets.UTF_8),
            body.substring(0, body.length() - 2).getBytes(StandardCharsets.UTF_8),
            (body + " true").getBytes(StandardCharsets.UTF_8),
            body.replaceFirst("\\{", "{\"preferredCategories\":[\"cafe\"],")
                .getBytes(StandardCharsets.UTF_8),
            body.replace("\"transportModes\":", "\"unknown\":true,\"transportModes\":")
                .getBytes(StandardCharsets.UTF_8),
            body.replace("\"preferredCategories\":[],", "").getBytes(StandardCharsets.UTF_8),
            body.replace("\"arrivalRegionCode\":\"jeju-si\"", "\"arrivalRegionCode\":null")
                .getBytes(StandardCharsets.UTF_8),
            new byte[TripPreferencesRequestBoundary.MAX_BODY_BYTES + 1]);
    reset(currentUsers, service);

    for (byte[] candidate : invalid) {
      mvc.perform(request(TRIP.toString(), candidate).header(HttpHeaders.IF_MATCH, EXPECTED_ETAG))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void nested_transport구조와_null_category_trim_blank_region은_auth와service전에400이다() throws Exception {
    reset(currentUsers, service);

    for (InvalidJson invalid : structuralInvalidBodies(validBody())) {
      mvc.perform(
              request(TRIP.toString(), invalid.body()).header(HttpHeaders.IF_MATCH, EXPECTED_ETAG))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void JSON_NUL은_auth와service전에400이다() throws Exception {
    String invalid =
        validBody()
            .replace(
                "\"arrivalRegionCode\":\"jeju-si\"",
                "\"arrivalRegionCode\":\"" + unicode("0000") + "jeju-si\"");
    reset(currentUsers, service);

    mvc.perform(request(TRIP.toString(), invalid).header(HttpHeaders.IF_MATCH, EXPECTED_ETAG))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void category와_region배열의_numeric_boolean_element는_auth와service전에400이다() throws Exception {
    reset(currentUsers, service);
    for (String invalid :
        List.of(
            validBody().replace("\"preferredCategories\":[]", "\"preferredCategories\":[7]"),
            validBody().replace("\"preferredCategories\":[]", "\"preferredCategories\":[true]"),
            validBody().replace("\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[7]"),
            validBody()
                .replace("\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[false]"))) {
      mvc.perform(request(TRIP.toString(), invalid).header(HttpHeaders.IF_MATCH, EXPECTED_ETAG))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void 모든_TransferEncoding은_auth와service전에400이다() throws Exception {
    reset(currentUsers, service);
    for (String value : List.of("chunked", "gzip", "identity")) {
      mvc.perform(valid().header(HttpHeaders.TRANSFER_ENCODING, value))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void raw_ContentLength가있고_servlet보고값이1작으면_auth와service전에400이다() throws Exception {
    byte[] body = validBody().getBytes(StandardCharsets.UTF_8);
    reset(currentUsers, service);

    mvc.perform(
            request(TRIP.toString(), body)
                .header(HttpHeaders.IF_MATCH, EXPECTED_ETAG)
                .header(HttpHeaders.CONTENT_LENGTH, body.length)
                .with(raw -> new ReportedContentLengthRequest(raw, body.length - 1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verifyNoInteractions(currentUsers, service);
  }

  @Test
  void raw_ContentLength가없고_servlet보고값minus1이면_bounded_read후성공한다() throws Exception {
    mvc.perform(
            valid()
                .with(
                    raw -> {
                      raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                      return new ReportedContentLengthRequest(raw, -1);
                    }))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, RESULT_ETAG));

    verify(service).replacePreferences(any(), eq(TRIP), eq(7L), any());
  }

  @Test
  void preferences_PUT의_runtime_problem은_canonical_fixture와_exact하다() throws Exception {
    JsonNode examples =
        objectMapper
            .readTree(
                Files.readString(
                    Path.of(
                        "..",
                        "..",
                        "fixtures",
                        "contracts",
                        "preferences-transport",
                        "problem.json")))
            .get("examples");

    for (String fixtureKey :
        List.of(
            "400_invalid_request",
            "404_place_not_found",
            "409_trip_version_conflict",
            "409_trip_terminal_state_conflict")) {
      JsonNode expected = examples.get(fixtureKey);
      reset(service);
      when(service.replacePreferences(any(), eq(TRIP), eq(7L), any()))
          .thenThrow(problem(expected.get("code").asText()));

      mvc.perform(
              valid()
                  .requestAttr(RequestTraceId.TRACE_ID_ATTRIBUTE, expected.get("traceId").asText()))
          .andExpect(status().is(expected.get("status").asInt()))
          .andExpect(content().json(expected.toString(), true));
    }
  }

  private MockHttpServletRequestBuilder valid() throws Exception {
    return request(TRIP.toString(), validBody()).header(HttpHeaders.IF_MATCH, EXPECTED_ETAG);
  }

  private MockHttpServletRequestBuilder request(String trip, String body) throws Exception {
    return request(trip, body.getBytes(StandardCharsets.UTF_8));
  }

  private MockHttpServletRequestBuilder request(String trip, byte[] body) throws Exception {
    return put("/api/v1/trips/{tripId}/preferences", trip)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static ReplaceTripPreferencesCommand command() {
    return new ReplaceTripPreferencesCommand(
        List.of(),
        "jeju-si",
        "seogwipo-si",
        List.of(),
        null,
        null,
        List.of(new TripTransportMode("public_transit", 1, true)));
  }

  private static String validBody() {
    return """
    {"preferredCategories":[],"arrivalRegionCode":"jeju-si","departureRegionCode":"seogwipo-si",
    "preferredRegionCodes":[],"startPlaceId":null,"endPlaceId":null,
    "transportModes":[{"mode":"public_transit","priority":1,"primary":true}]}
    """;
  }

  private static String unicode(String hex) {
    return "\\" + "u" + hex;
  }

  private static TripException problem(String code) {
    return switch (code) {
      case "INVALID_REQUEST" -> TripException.invalidRequest();
      case "PLACE_NOT_FOUND" -> TripException.placeNotFound();
      case "TRIP_VERSION_CONFLICT" -> TripException.versionConflict();
      case "TRIP_TERMINAL_STATE_CONFLICT" -> TripException.terminalStateConflict();
      default -> throw new IllegalArgumentException(code);
    };
  }

  private static List<InvalidJson> structuralInvalidBodies(String valid) {
    return List.of(
        new InvalidJson("mode missing", valid.replace("\"mode\":\"public_transit\",", "")),
        new InvalidJson("mode null", valid.replace("\"mode\":\"public_transit\"", "\"mode\":null")),
        new InvalidJson(
            "mode wrong type", valid.replace("\"mode\":\"public_transit\"", "\"mode\":7")),
        new InvalidJson("priority missing", valid.replace("\"priority\":1,", "")),
        new InvalidJson("priority null", valid.replace("\"priority\":1", "\"priority\":null")),
        new InvalidJson(
            "priority wrong type", valid.replace("\"priority\":1", "\"priority\":\"1\"")),
        new InvalidJson("primary missing", valid.replace(",\"primary\":true", "")),
        new InvalidJson("primary null", valid.replace("\"primary\":true", "\"primary\":null")),
        new InvalidJson("primary wrong type", valid.replace("\"primary\":true", "\"primary\":1")),
        new InvalidJson(
            "unknown nested property",
            valid.replace(
                "\"mode\":\"public_transit\"", "\"unknown\":true,\"mode\":\"public_transit\"")),
        new InvalidJson(
            "null preferred category",
            valid.replace("\"preferredCategories\":[]", "\"preferredCategories\":[null]")),
        new InvalidJson(
            "blank preferred region after trim",
            valid.replace(
                "\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[\"  \\t  \"]")));
  }

  private record InvalidJson(String label, String body) {}

  private static String token() throws Exception {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("authenticated")
                .subject(OWNER.toString())
                .claim("role", "authenticated")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] key = new byte[48];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static final class ReportedContentLengthRequest extends MockHttpServletRequest {
    private final int reportedContentLength;

    private ReportedContentLengthRequest(MockHttpServletRequest source, int reportedContentLength) {
      super(source.getServletContext());
      this.reportedContentLength = reportedContentLength;
      setMethod(source.getMethod());
      setRequestURI(source.getRequestURI());
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
}
