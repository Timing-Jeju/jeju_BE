package com.timingjeju.api.domain.transportevent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.TransportEventMutationPayload;
import com.timingjeju.api.application.transportevent.TransportEventMutationPayload.TransportEventPayload;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
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
      "timing-jeju.test.context=transport-event-controller"
    })
@AutoConfigureMockMvc
class TransportEventControllerIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER = UUID.fromString("47200000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("47200000-0000-0000-0000-000000000002");
  private static final UUID PLACE = UUID.fromString("47200000-0000-0000-0000-000000000003");
  private static final String ETAG = "\"trip-47200000-0000-0000-0000-000000000002-r1\"";

  @Autowired private MockMvc mvc;
  @MockitoBean private TransportEventService service;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    when(service.put(any(), any(), any(), any())).thenReturn(payload(false));
    when(service.delete(any(), any(), any(), any())).thenReturn(payload(true));
  }

  @Test
  void PUT은_7개_presence와_headers를_command로_변환하고_200_ETag를_반환한다() throws Exception {
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, ETAG))
        .andExpect(jsonPath("$.deleted").value(false))
        .andExpect(jsonPath("$.event.eventType").value("arrival"))
        .andExpect(jsonPath("$.etag").doesNotExist());

    ArgumentCaptor<PutTransportEventCommand> command =
        ArgumentCaptor.forClass(PutTransportEventCommand.class);
    verify(service).put(eq(USER), eq(TRIP), any(), command.capture());
    assertThat(command.getValue().terminalPlaceId()).isEqualTo(PLACE);
    assertThat(command.getValue().transportNumber()).isEqualTo("KE1001");
  }

  @Test
  void PUT_DELETE는_byte_array_argument없이_bounded_raw_stream을_직접읽는다() {
    for (String methodName : List.of("put", "delete")) {
      var method =
          java.util.Arrays.stream(TransportEventController.class.getDeclaredMethods())
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
  void PUT은_content_type_length_transfer_encoding과_max_plus_one을_service전에_400으로_거부한다()
      throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .content(validBody()),
            validPut().contentType(MediaType.TEXT_PLAIN),
            validPut().header(HttpHeaders.CONTENT_LENGTH, "-1"),
            validPut().header(HttpHeaders.CONTENT_LENGTH, "+1"),
            validPut().header(HttpHeaders.CONTENT_LENGTH, "00"),
            validPut().header(HttpHeaders.CONTENT_LENGTH, "0", "0"),
            validPut().header(HttpHeaders.CONTENT_LENGTH, "0,0"),
            validPut().header(HttpHeaders.TRANSFER_ENCODING, "chunked"),
            validPut().content("x".repeat((1024 * 1024) + 1)))) {
      reset(service);
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(service);
    }
  }

  @Test
  void PUT은_unknown_length도_max_plus_one만_probe하고_exact_max는_허용한다() throws Exception {
    mvc.perform(validPut().content(bodyAtSize(1024 * 1024))).andExpect(status().isOk());
    verify(service).put(eq(USER), eq(TRIP), any(), any());

    reset(service);
    mvc.perform(
            validPut()
                .content(bodyAtSize((1024 * 1024) + 1))
                .with(
                    raw -> {
                      raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                      return new ReportedContentLengthRequest(raw, -1);
                    }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void PUT은_declared_servlet_actual_length가_다르면_service전에_400이다() throws Exception {
    int actual = validBody().getBytes(StandardCharsets.UTF_8).length;
    for (int reported : List.of(-2, 0, 1, actual - 1)) {
      reset(service);
      mvc.perform(
              validPut()
                  .with(
                      raw -> {
                        raw.removeHeader(HttpHeaders.CONTENT_LENGTH);
                        raw.addHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(actual));
                        return new ReportedContentLengthRequest(raw, reported);
                      }))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(service);
    }
  }

  @Test
  void DELETE는_transfer_encoding_noncanonical_length와_hidden_body를_service전에_400으로_거부한다()
      throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(
            validDelete().header(HttpHeaders.TRANSFER_ENCODING, "chunked"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "-1"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "+0"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "00"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "0", "0"),
            validDelete().header(HttpHeaders.CONTENT_LENGTH, "0,0"),
            validDelete().content("x"))) {
      reset(service);
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(service);
    }
  }

  @Test
  void PUT은_누락_unknown_타입_noncanonical과_query를_service전에_400으로_거부한다() throws Exception {
    for (String body :
        List.of(
            "{}",
            validBody().replace(",\"note\":null", ""),
            validBody().replace("}", ",\"unknown\":true}"),
            validBody().replace("\"KE1001\"", "1001"),
            validBody().replace("09:00:00", "09:00"),
            validBody() + " true",
            validBody().replace("{", "{\"eventType\":\"arrival\","))) {
      reset(service);
      mvc.perform(
              put("/api/v1/trips/{tripId}/transport-event", TRIP)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header(HttpHeaders.IF_MATCH, ETAG)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(service);
    }
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .queryParam("unexpected", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void DELETE는_eventType을_정확히한번_요구하고_body를_금지한다() throws Exception {
    mvc.perform(
            delete("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .queryParam("eventType", "departure"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, ETAG))
        .andExpect(jsonPath("$.deleted").value(true))
        .andExpect(jsonPath("$.event").value(org.hamcrest.Matchers.nullValue()));
    verify(service).delete(eq(USER), eq(TRIP), eq("departure"), any());

    for (var request :
        List.of(
            delete("/api/v1/trips/{tripId}/transport-event", TRIP),
            delete("/api/v1/trips/{tripId}/transport-event", TRIP)
                .queryParam("eventType", "arrival", "departure"),
            delete("/api/v1/trips/{tripId}/transport-event", TRIP).queryParam("eventType", "train"),
            delete("/api/v1/trips/{tripId}/transport-event", TRIP)
                .queryParam("eventType", "arrival")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))) {
      mvc.perform(
              request
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                  .header(HttpHeaders.IF_MATCH, ETAG))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }

  @Test
  void PUT_DELETE는_duplicate_IfMatch를_service전에_거부한다() throws Exception {
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(
            delete("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .header(HttpHeaders.IF_MATCH, ETAG)
                .queryParam("eventType", "arrival"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void 인증_UUID_ETag를_강제하고_domain오류는_민감값없는_problem으로_변환한다() throws Exception {
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", "A7200000-0000-0000-0000-000000000002")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());

    reset(service);
    when(service.put(any(), any(), any(), any()))
        .thenThrow(TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION"));
    mvc.perform(
            put("/api/v1/trips/{tripId}/transport-event", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .header(HttpHeaders.IF_MATCH, ETAG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TRANSPORT_EVENT_CONSTRAINT_VIOLATION"))
        .andExpect(jsonPath("$.detail").value("날짜, +09:00 시간대와 터미널 입력을 확인해 주세요."))
        .andExpect(
            jsonPath("$.detail")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("KE1001"))));
  }

  private static TransportEventMutationPayload payload(boolean deleted) {
    return new TransportEventMutationPayload(
        TRIP,
        "none",
        false,
        null,
        "draft",
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        deleted ? "departure" : "arrival",
        deleted,
        deleted
            ? null
            : new TransportEventPayload(
                "arrival",
                "flight",
                PLACE,
                null,
                OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
                "KE1001",
                null),
        ETAG);
  }

  private static String validBody() {
    return """
        {"eventType":"arrival","transportType":"flight","terminalPlaceId":"47200000-0000-0000-0000-000000000003","customTerminalName":null,"scheduledAt":"2026-09-01T09:00:00+09:00","transportNumber":"KE1001","note":null}
        """;
  }

  private static byte[] bodyAtSize(int size) {
    byte[] body = validBody().getBytes(StandardCharsets.UTF_8);
    if (body.length > size) throw new IllegalArgumentException("size is too small");
    byte[] padded = java.util.Arrays.copyOf(body, size);
    java.util.Arrays.fill(padded, body.length, padded.length, (byte) ' ');
    return padded;
  }

  private static MockHttpServletRequestBuilder validPut() throws Exception {
    return put("/api/v1/trips/{tripId}/transport-event", TRIP)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, ETAG)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody());
  }

  private static MockHttpServletRequestBuilder validDelete() throws Exception {
    return delete("/api/v1/trips/{tripId}/transport-event", TRIP)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
        .header(HttpHeaders.IF_MATCH, ETAG)
        .queryParam("eventType", "arrival");
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

  private static final class ReportedContentLengthRequest extends MockHttpServletRequest {
    private final int reportedContentLength;

    private ReportedContentLengthRequest(MockHttpServletRequest source, int reportedContentLength) {
      super(source.getServletContext());
      this.reportedContentLength = reportedContentLength;
      setMethod(source.getMethod());
      setRequestURI(source.getRequestURI());
      setQueryString(source.getQueryString());
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
