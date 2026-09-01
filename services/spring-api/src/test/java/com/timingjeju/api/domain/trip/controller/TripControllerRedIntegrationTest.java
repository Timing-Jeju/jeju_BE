package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripMutationResult;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.service.TripService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.web.servlet.MvcResult;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=trip-controller-red"
    })
@AutoConfigureMockMvc
class TripControllerRedIntegrationTest {

  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER_ID = UUID.fromString("44000000-0000-0000-0000-000000000001");

  @Autowired private MockMvc mvc;
  @MockitoBean private TripService tripService;
  @MockitoBean private IdempotencyUseCase idempotency;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    when(idempotency.execute(any(), any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<com.timingjeju.api.application.idempotency.IdempotencyOperation>getArgument(1)
                    .execute());
    when(tripService.create(any(), any())).thenReturn(aggregate());
    when(tripService.update(any(), any(), any(), any()))
        .thenReturn(new TripMutationResult(aggregate(8), "maintained", false));
  }

  @Test
  void POST_trips는_유효한_3일_여행과_날짜별_Day를_원자_생성한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000044")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title":"제주 동쪽 2박 3일",
                      "startDate":"2026-08-03",
                      "endDate":"2026-08-05"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    HttpHeaders.ETAG,
                    org.hamcrest.Matchers.matchesPattern("^\"[A-Za-z0-9._:-]{1,128}\"$")))
        .andExpect(jsonPath("$.days.length()").value(3))
        .andExpect(jsonPath("$.days[0].dayNo").value(1))
        .andExpect(jsonPath("$.days[0].date").value("2026-08-03"))
        .andExpect(jsonPath("$.days[2].dayNo").value(3))
        .andExpect(jsonPath("$.days[2].date").value("2026-08-05"));
  }

  @Test
  void POST_trips_replay는_저장된_Location_ETag_body를_재사용하고_replayed만_true다() throws Exception {
    AtomicReference<com.timingjeju.api.application.idempotency.IdempotencyResponse> stored =
        new AtomicReference<>();
    doAnswer(
            invocation -> {
              var existing = stored.get();
              if (existing != null) {
                return existing;
              }
              var created =
                  invocation
                      .<com.timingjeju.api.application.idempotency.IdempotencyOperation>getArgument(
                          1)
                      .execute();
              stored.set(created);
              return created;
            })
        .when(idempotency)
        .execute(any(), any());
    String body =
        """
        {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05"}
        """;

    String firstEtag =
        mvc.perform(
                post("/api/v1/trips")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                    .header("Idempotency-Key", "44000000-0000-0000-0000-000000000046")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.ETAG);

    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000046")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.ETAG, firstEtag))
        .andExpect(
            header()
                .string(HttpHeaders.LOCATION, "/api/v1/trips/44000000-0000-0000-0000-000000000044"))
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.tripId").value("44000000-0000-0000-0000-000000000044"));
  }

  @Test
  void GET_trips는_unknown과_repeated_query_parameter를_거부한다() throws Exception {
    mvc.perform(
            get("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .queryParam("unknown", "value"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"));
    for (String[] repeated :
        List.of(
            new String[] {"status", "draft", "planned"},
            new String[] {"sort", "updated_at_desc", "updated_at_desc"},
            new String[] {"cursor", "cursor-a", "cursor-b"},
            new String[] {"size", "10", "20"})) {
      mvc.perform(
              get("/api/v1/trips")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .queryParam(repeated[0], repeated[1], repeated[2]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"));
    }
  }

  @Test
  void GET_trip_path는_lowercase_canonical_UUID만_허용하고_그외에는_service를_호출하지_않는다() throws Exception {
    UUID canonical = UUID.fromString("44000000-0000-0000-0000-000000000044");
    when(tripService.read(any(), eq(canonical))).thenReturn(aggregate());

    for (String invalid :
        List.of(
            "1-1-1-1-1",
            "44000000-0000-0000-0000-00000000004",
            "44000000-0000-0000-0000-00000000004Z",
            "44000000-0000-0000-0000-0000000000AA")) {
      reset(tripService);
      mvc.perform(
              get("/api/v1/trips/{tripId}", invalid)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
      verifyNoInteractions(tripService);
    }

    when(tripService.read(any(), eq(canonical))).thenReturn(aggregate());
    mvc.perform(
            get("/api/v1/trips/{tripId}", canonical.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tripId").value(canonical.toString()));
    verify(tripService).read(any(), eq(canonical));
  }

  @Test
  void PATCH_trip은_유효한_If_Match와_presence_body를_받아_새_ETag와_schedule_effect를_반환한다()
      throws Exception {
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");

    mvc.perform(
            patch("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header(HttpHeaders.IF_MATCH, "\"trip-44000000-0000-0000-0000-000000000044-r7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"제주 가족 여행"}
                    """))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ETAG, "\"trip-44000000-0000-0000-0000-000000000044-r8\""))
        .andExpect(jsonPath("$.scheduleEffect").value("maintained"))
        .andExpect(jsonPath("$.regenerationRequired").value(false));

    verify(tripService).update(any(), eq(tripId), any(), any());
  }

  @Test
  void PATCH_trip의_If_Match_누락과_weak_tag는_각각_canonical_problem으로_거부한다() throws Exception {
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");
    String body = "{\"title\":\"제주 가족 여행\"}";

    mvc.perform(
            patch("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));

    mvc.perform(
            patch("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header(HttpHeaders.IF_MATCH, "W/\"trip-44000000-0000-0000-0000-000000000044-r7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
  }

  @Test
  void PATCH_trip은_empty_unknown_explicit_null_body를_INVALID_REQUEST로_거부한다() throws Exception {
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");
    for (String body :
        List.of(
            "{}",
            "{\"unknown\":true}",
            "{\"title\":null}",
            "{\"startDate\":null}",
            "{\"transportModes\":null}")) {
      mvc.perform(
              patch("/api/v1/trips/{tripId}", tripId)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .header(HttpHeaders.IF_MATCH, "\"trip-44000000-0000-0000-0000-000000000044-r7\"")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }

  @Test
  void DELETE_trip은_204_empty를_반환하고_query와_body를_거부한다() throws Exception {
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");

    mvc.perform(
            delete("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(tripService).delete(any(), eq(tripId));

    mvc.perform(
            delete("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .queryParam("force", "true"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mvc.perform(
            delete("/api/v1/trips/{tripId}", tripId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void GET_trip_list와_detail의_data_unavailable은_cause없는_TRIP_DATA_UNAVAILABLE_503이다()
      throws Exception {
    TripException listFailure = TripException.dataUnavailable();
    when(tripService.list(any(), isNull(), isNull(), isNull(), isNull())).thenThrow(listFailure);

    MvcResult listResult =
        mvc.perform(
                get("/api/v1/trips").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("TRIP_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.cause").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist())
            .andReturn();
    assertThat(listResult.getResponse().getContentAsString()).doesNotContain("cause");

    reset(tripService);
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");
    TripException detailFailure = TripException.dataUnavailable();
    when(tripService.read(any(), eq(tripId))).thenThrow(detailFailure);

    MvcResult detailResult =
        mvc.perform(
                get("/api/v1/trips/{tripId}", tripId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("TRIP_DATA_UNAVAILABLE"))
            .andExpect(jsonPath("$.cause").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist())
            .andReturn();
    assertThat(detailResult.getResponse().getContentAsString()).doesNotContain("cause");
  }

  @Test
  void POST_trips는_문자열_title의_잘못된_JSON_type을_INVALID_REQUEST로_거부한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000045")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "title": 44,
                      "startDate":"2026-08-03",
                      "endDate":"2026-08-05"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void POST_trips의_Idempotency_Key_누락은_canonical_Problem_Details로_거부한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
        .andExpect(jsonPath("$.cause").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());

    verifyNoInteractions(idempotency, tripService);
  }

  @Test
  void POST_trips의_uppercase_Idempotency_Key는_canonical_invalid_Problem으로_거부한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "018F6F2A-60A0-7F5B-8C61-8F548F34BC31")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"))
        .andExpect(jsonPath("$.cause").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist());

    verifyNoInteractions(idempotency, tripService);
  }

  @Test
  void POST_trips는_transportModes_생략만_default하고_explicit_null은_거부한다() throws Exception {
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000047")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05","transportModes":null}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void POST_trips는_body_exact_1MiB를_허용하고_max_plus_1은_INVALID_REQUEST다() throws Exception {
    byte[] exactMax = requestBody(IdempotencyRequest.MAX_BODY_BYTES);
    byte[] overMax = requestBody(IdempotencyRequest.MAX_BODY_BYTES + 1);

    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000048")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exactMax))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/trips")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "44000000-0000-0000-0000-000000000049")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overMax))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void POST_trips의_profile_identity_conflict는_cause없는_PROFILE_CONFLICT_409이다() throws Exception {
    for (ProfileProvisioningException failure :
        List.of(
            ProfileProvisioningException.emailConflict(),
            ProfileProvisioningException.providerSubjectConflict())) {
      assertProvisioningProblem(failure, 409, "PROFILE_CONFLICT");
    }
  }

  @Test
  void POST_trips의_invalid_identity와_storage_failure는_cause없는_TRIP_DATA_UNAVAILABLE_503이다()
      throws Exception {
    for (ProfileProvisioningException failure :
        List.of(
            ProfileProvisioningException.invalidAuthIdentity(),
            ProfileProvisioningException.storageUnavailable())) {
      assertProvisioningProblem(failure, 503, "TRIP_DATA_UNAVAILABLE");
    }
  }

  private void assertProvisioningProblem(
      ProfileProvisioningException failure, int expectedStatus, String expectedCode)
      throws Exception {
    reset(tripService);
    when(tripService.create(any(), any())).thenThrow(failure);

    MvcResult result =
        mvc.perform(
                post("/api/v1/trips")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"title":"제주","startDate":"2026-08-03","endDate":"2026-08-05"}
                        """))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode))
            .andExpect(jsonPath("$.cause").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.providerMessage").doesNotExist())
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(failure.getMessage())
        .doesNotContain(failure.code().name())
        .doesNotContain("demo@timing-jeju.local");
  }

  private static byte[] requestBody(int size) {
    byte[] json =
        "{\"title\":\"Jeju\",\"startDate\":\"2026-08-03\",\"endDate\":\"2026-08-05\"}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[size];
    Arrays.fill(body, (byte) ' ');
    System.arraycopy(json, 0, body, 0, json.length);
    return body;
  }

  private static TripAggregate aggregate() {
    return aggregate(1);
  }

  private static TripAggregate aggregate(long revision) {
    UUID tripId = UUID.fromString("44000000-0000-0000-0000-000000000044");
    Instant createdAt = Instant.parse("2026-08-03T00:05:00Z");
    return new TripAggregate(
        tripId,
        revision,
        "제주 동쪽 2박 3일",
        "draft",
        java.time.LocalDate.parse("2026-08-03"),
        java.time.LocalDate.parse("2026-08-05"),
        "Asia/Seoul",
        "normal",
        List.of(new TripTransportMode("public_transit", 1, true)),
        List.of(
            new TripDay(
                UUID.fromString("44000000-0000-0000-0001-000000000001"),
                1,
                java.time.LocalDate.parse("2026-08-03")),
            new TripDay(
                UUID.fromString("44000000-0000-0000-0001-000000000002"),
                2,
                java.time.LocalDate.parse("2026-08-04")),
            new TripDay(
                UUID.fromString("44000000-0000-0000-0001-000000000003"),
                3,
                java.time.LocalDate.parse("2026-08-05"))),
        null,
        null,
        null,
        createdAt,
        createdAt);
  }

  private static String token(UUID userId) throws Exception {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(userId.toString())
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
