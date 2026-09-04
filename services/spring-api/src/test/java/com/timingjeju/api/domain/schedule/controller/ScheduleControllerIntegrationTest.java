package com.timingjeju.api.domain.schedule.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.schedule.ItemProgressSnapshot;
import com.timingjeju.api.application.schedule.ScheduleDaySnapshot;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleItemSnapshot;
import com.timingjeju.api.application.schedule.ScheduleLegSnapshot;
import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.schedule.ScheduleSnapshot;
import com.timingjeju.api.application.schedule.ScheduleVersionSnapshot;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.schedule.service.ScheduleQueryService;
import jakarta.servlet.ServletContext;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=schedule-controller"
    })
@AutoConfigureMockMvc
class ScheduleControllerIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID USER_ID = UUID.fromString("49000000-0000-0000-0000-000000000001");
  private static final UUID TRIP_ID = UUID.fromString("49000000-0000-0000-0000-000000000002");
  private static final UUID VERSION_ID = UUID.fromString("49000000-0000-0000-0000-000000000003");

  @Autowired private MockMvc mvc;
  @MockitoBean private ScheduleQueryService schedules;

  @MockitoBean(name = "scheduleMutationService")
  private ScheduleMutationService mutations;

  @MockitoBean private IdempotencyUseCase idempotency;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void 멱등성_operation을_동기_실행한다() {
    when(idempotency.execute(any(), any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<com.timingjeju.api.application.idempotency.IdempotencyOperation>getArgument(1)
                    .execute());
  }

  @Test
  void GET_schedule은_active_selector와_closed_KST_projection을_반환한다() throws Exception {
    when(schedules.read(any(), eq(TRIP_ID), isNull())).thenReturn(snapshot());

    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.tripId").value(TRIP_ID.toString()))
        .andExpect(jsonPath("$.scheduleVersion.scheduleVersionId").value(VERSION_ID.toString()))
        .andExpect(jsonPath("$.scheduleVersion.baseScheduleVersionId").value((Object) null))
        .andExpect(jsonPath("$.days[0].items[0].plannedStartAt").value("2026-09-01T09:00:00+09:00"))
        .andExpect(jsonPath("$.days[0].items[0].progress.actualArrivedAt").value((Object) null))
        .andExpect(jsonPath("$.days[0].legs[0].estimatedFareKrw").value(1250));
    verify(schedules).read(any(), eq(TRIP_ID), isNull());
  }

  @Test
  void GET_schedule은_explicit_version을_전달한다() throws Exception {
    when(schedules.read(any(), eq(TRIP_ID), eq(VERSION_ID))).thenReturn(snapshot());

    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .queryParam("versionId", VERSION_ID.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isOk());

    verify(schedules).read(any(), eq(TRIP_ID), eq(VERSION_ID));
  }

  @Test
  void GET_schedule은_noncanonical_unknown_repeated_blank_query와_body를_거부한다() throws Exception {
    for (var request :
        List.of(
            get("/api/v1/trips/{tripId}/schedule", "49000000-0000-0000-0000-00000000000A"),
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID).queryParam("unknown", "x"),
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .queryParam("versionId", VERSION_ID.toString(), VERSION_ID.toString()),
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID).queryParam("versionId", ""),
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))) {
      mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    verifyNoInteractions(schedules);
  }

  @Test
  void GET_schedule은_길이와_transfer_encoding이_없는_request_stream_body도_거부한다() throws Exception {
    var request =
        new UnknownLengthGetRequestBuilder()
            .uri("/api/v1/trips/{tripId}/schedule", TRIP_ID)
            .content("{}")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID));

    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verifyNoInteractions(schedules);
  }

  @Test
  void GET_schedule의_저장소_무결성_실패는_cause없는_공통_500으로_은닉한다() throws Exception {
    when(schedules.read(any(), eq(TRIP_ID), isNull()))
        .thenThrow(ScheduleException.internalServerError());

    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.cause").doesNotExist());
  }

  @Test
  void GET_schedule은_trip과_version_부재를_cause없는_404로_은닉한다() throws Exception {
    when(schedules.read(any(), eq(TRIP_ID), isNull())).thenThrow(ScheduleException.tripNotFound());
    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"))
        .andExpect(jsonPath("$.cause").doesNotExist());

    when(schedules.read(any(), eq(TRIP_ID), eq(VERSION_ID)))
        .thenThrow(ScheduleException.versionNotFound());
    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP_ID)
                .queryParam("versionId", VERSION_ID.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SCHEDULE_VERSION_NOT_FOUND"))
        .andExpect(jsonPath("$.cause").doesNotExist());
  }

  @Test
  void POST_schedule_items는_새_user_edit_version을_활성화하고_201을_반환한다() throws Exception {
    UUID newVersionId = UUID.fromString("49000000-0000-0000-0000-000000000008");
    UUID changedItemId = UUID.fromString("49000000-0000-0000-0000-000000000009");
    when(mutations.addItem(any(), eq(TRIP_ID), any(), any()))
        .thenReturn(
            new ScheduleMutationResult(
                TRIP_ID,
                VERSION_ID,
                newVersionId,
                2,
                2,
                List.of(changedItemId),
                Instant.parse("2026-09-01T01:00:00Z")));

    mvc.perform(
            post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "50000000-0000-0000-0000-000000000001")
                .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedActiveScheduleVersionId":"49000000-0000-0000-0000-000000000003",
                      "dayNo":1,
                      "sequenceNo":1,
                      "itemType":"place_visit",
                      "placeId":"49000000-0000-0000-0000-000000000007",
                      "plannedStartAt":"2026-09-01T09:00:00+09:00",
                      "stayMinutes":60
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tripId").value(TRIP_ID.toString()))
        .andExpect(jsonPath("$.previousScheduleVersionId").value(VERSION_ID.toString()))
        .andExpect(jsonPath("$.activeScheduleVersionId").value(newVersionId.toString()))
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.sourceType").value("user_edit"))
        .andExpect(jsonPath("$.feasibilityStale").value(true))
        .andExpect(jsonPath("$.changedItemIds[0]").value(changedItemId.toString()))
        .andExpect(jsonPath("$.etag").value("\"trip-" + TRIP_ID + "-r2\""));
  }

  @Test
  void POST_schedule_items는_중복_JSON_필수누락과_유효하지_않은_item을_구분해_거부한다() throws Exception {
    String prefix =
        "{\"expectedActiveScheduleVersionId\":\"" + VERSION_ID + "\",\"dayNo\":1,\"sequenceNo\":1,";
    for (var invalid :
        List.of(
            prefix
                + "\"itemType\":\"place_visit\",\"itemType\":\"place_visit\",\"placeId\":\""
                + UUID.fromString("49000000-0000-0000-0000-000000000007")
                + "\",\"plannedStartAt\":\"2026-09-01T09:00:00+09:00\",\"stayMinutes\":60}",
            prefix
                + "\"itemType\":\"place_visit\",\"placeId\":\"49000000-0000-0000-0000-000000000007\",\"stayMinutes\":60}")) {
      mvc.perform(
              post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
          .andExpect(
              jsonPath("$.type").value("https://api.timing-jeju.com/problems/invalid-request"))
          .andExpect(jsonPath("$.title").value("요청 값이 올바르지 않습니다"))
          .andExpect(jsonPath("$.detail").value("필수값과 형식, 일정 버전 식별자를 확인해 주세요."));
    }

    mvc.perform(
            post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    prefix
                        + "\"itemType\":\"place_visit\",\"plannedStartAt\":\"2026-09-01T09:00:00+09:00\",\"stayMinutes\":60}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SCHEDULE_ITEM_INVALID"));
    verifyNoInteractions(mutations);
  }

  @Test
  void POST_schedule_items는_Idempotency_Key와_strong_If_Match를_필수로_검증한다() throws Exception {
    String body =
        """
        {"expectedActiveScheduleVersionId":"49000000-0000-0000-0000-000000000003",
         "dayNo":1,"sequenceNo":1,"itemType":"place_visit",
         "placeId":"49000000-0000-0000-0000-000000000007",
         "plannedStartAt":"2026-09-01T09:00:00+09:00","stayMinutes":60}
        """;
    mvc.perform(
            post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
        .andExpect(
            jsonPath("$.type")
                .value("https://api.timing-jeju.com/problems/idempotency-key-required"))
        .andExpect(jsonPath("$.title").value("멱등성 키가 필요합니다"))
        .andExpect(jsonPath("$.detail").value("Idempotency-Key 헤더를 입력해 주세요."));

    mvc.perform(
            post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", "not-a-uuid")
                .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"))
        .andExpect(
            jsonPath("$.type")
                .value("https://api.timing-jeju.com/problems/idempotency-key-invalid"))
        .andExpect(jsonPath("$.title").value("멱등성 키가 유효하지 않습니다"))
        .andExpect(jsonPath("$.detail").value("UUID 형식의 Idempotency-Key를 입력해 주세요."));

    mvc.perform(
            post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"trip-1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.detail").value("필수값과 형식, 일정 버전 식별자를 확인해 주세요."));
    verifyNoInteractions(mutations);
  }

  @Test
  void POST_schedule_items의_참조와_version_오류는_schedule_canonical_problem을_반환한다() throws Exception {
    when(mutations.addItem(any(), eq(TRIP_ID), any(), any()))
        .thenThrow(
            ScheduleException.placeNotFound(),
            ScheduleException.versionNotFound(),
            ScheduleException.tripVersionConflict(),
            com.timingjeju.api.application.trip.TripException.terminalStateConflict());
    String body = validScheduleItemBody();
    List<List<String>> expected =
        List.of(
            List.of("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", "요청한 장소가 없거나 사용할 수 없습니다."),
            List.of(
                "SCHEDULE_VERSION_NOT_FOUND",
                "일정 버전을 찾을 수 없습니다",
                "요청한 일정 버전이 없거나 해당 여행에 속하지 않습니다."),
            List.of("TRIP_VERSION_CONFLICT", "여행 조건이 이미 변경되었습니다", "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."),
            List.of(
                "TRIP_TERMINAL_STATE_CONFLICT",
                "종료된 여행은 변경할 수 없습니다",
                "완료, 취소 또는 실패한 여행의 일정은 변경할 수 없습니다."));

    for (List<String> problem : expected) {
      int expectedStatus = problem.get(0).endsWith("CONFLICT") ? 409 : 404;
      mvc.perform(scheduleItemPost(body.getBytes(StandardCharsets.UTF_8), UUID.randomUUID()))
          .andExpect(status().is(expectedStatus))
          .andExpect(jsonPath("$.status").value(expectedStatus))
          .andExpect(jsonPath("$.code").value(problem.get(0)))
          .andExpect(
              jsonPath("$.type")
                  .value(
                      "https://api.timing-jeju.com/problems/"
                          + problem.get(0).toLowerCase().replace('_', '-')))
          .andExpect(jsonPath("$.title").value(problem.get(1)))
          .andExpect(jsonPath("$.detail").value(problem.get(2)));
    }
  }

  @Test
  void POST_schedule_items의_처리중_멱등성_충돌은_canonical_problem과_Retry_After를_반환한다() throws Exception {
    doThrow(IdempotencyException.processing()).when(idempotency).execute(any(), any());

    mvc.perform(
            scheduleItemPost(
                validScheduleItemBody().getBytes(StandardCharsets.UTF_8), UUID.randomUUID()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(
            jsonPath("$.type").value("https://api.timing-jeju.com/problems/idempotency-key-reused"))
        .andExpect(jsonPath("$.title").value("멱등성 키가 다른 요청에 사용되었습니다"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "다른 요청이면 새 Idempotency-Key로 다시 보내고, 동일 요청이 처리 중이면 Retry-After 헤더의 초만큼 기다린 뒤 다시 요청해 주세요."))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Retry-After", "1"));
  }

  @Test
  void POST_schedule_items는_정확히_1MiB를_허용하고_초과_body를_400으로_거부한다() throws Exception {
    UUID newVersionId = UUID.fromString("49000000-0000-0000-0000-000000000008");
    when(mutations.addItem(any(), eq(TRIP_ID), any(), any()))
        .thenReturn(
            new ScheduleMutationResult(
                TRIP_ID,
                VERSION_ID,
                newVersionId,
                2,
                2,
                List.of(UUID.fromString("49000000-0000-0000-0000-000000000009")),
                Instant.parse("2026-09-01T01:00:00Z")));
    String json =
        "{\"expectedActiveScheduleVersionId\":\""
            + VERSION_ID
            + "\",\"dayNo\":1,\"sequenceNo\":1,\"itemType\":\"place_visit\","
            + "\"placeId\":\"49000000-0000-0000-0000-000000000007\","
            + "\"plannedStartAt\":\"2026-09-01T09:00:00+09:00\",\"stayMinutes\":60}";
    byte[] base = json.getBytes(StandardCharsets.UTF_8);
    byte[] exact =
        (json + " ".repeat(IdempotencyRequest.MAX_BODY_BYTES - base.length))
            .getBytes(StandardCharsets.UTF_8);
    byte[] over =
        (json + " ".repeat(IdempotencyRequest.MAX_BODY_BYTES + 1 - base.length))
            .getBytes(StandardCharsets.UTF_8);

    mvc.perform(scheduleItemPost(exact, UUID.randomUUID())).andExpect(status().isCreated());
    mvc.perform(scheduleItemPost(over, UUID.randomUUID()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(mutations).addItem(any(), eq(TRIP_ID), any(), any());
  }

  private AbstractMockHttpServletRequestBuilder<?> scheduleItemPost(byte[] body, UUID key)
      throws Exception {
    return post("/api/v1/trips/{tripId}/schedule-items", TRIP_ID)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(USER_ID))
        .header("Idempotency-Key", key.toString())
        .header("If-Match", "\"trip-" + TRIP_ID + "-r1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String validScheduleItemBody() {
    return "{\"expectedActiveScheduleVersionId\":\""
        + VERSION_ID
        + "\",\"dayNo\":1,\"sequenceNo\":1,\"itemType\":\"place_visit\","
        + "\"placeId\":\"49000000-0000-0000-0000-000000000007\","
        + "\"plannedStartAt\":\"2026-09-01T09:00:00+09:00\",\"stayMinutes\":60}";
  }

  private static ScheduleSnapshot snapshot() {
    UUID day = UUID.fromString("49000000-0000-0000-0000-000000000004");
    UUID first = UUID.fromString("49000000-0000-0000-0000-000000000005");
    UUID second = UUID.fromString("49000000-0000-0000-0000-000000000006");
    Instant start = Instant.parse("2026-09-01T00:00:00Z");
    return new ScheduleSnapshot(
        TRIP_ID,
        new ScheduleVersionSnapshot(VERSION_ID, 1, "active", "initial", null, 81, false),
        List.of(
            new ScheduleDaySnapshot(
                day,
                1,
                LocalDate.parse("2026-09-01"),
                List.of(
                    new ScheduleItemSnapshot(
                        first,
                        1,
                        "place_visit",
                        UUID.fromString("49000000-0000-0000-0000-000000000007"),
                        "성산일출봉",
                        start,
                        start.plusSeconds(3600),
                        60,
                        10,
                        true,
                        null,
                        new ItemProgressSnapshot("planned", null, null, null, start)),
                    new ScheduleItemSnapshot(
                        second,
                        2,
                        "meal",
                        null,
                        "점심",
                        start.plusSeconds(5400),
                        start.plusSeconds(9000),
                        60,
                        0,
                        false,
                        null,
                        null)),
                List.of(
                    new ScheduleLegSnapshot(
                        UUID.fromString("49000000-0000-0000-0000-000000000008"),
                        1,
                        first,
                        second,
                        "public_transit",
                        start.plusSeconds(4200),
                        start.plusSeconds(5400),
                        5,
                        3,
                        10,
                        2,
                        20,
                        0,
                        5000,
                        1250,
                        20)))));
  }

  private static String token(UUID subject) throws Exception {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("authenticated")
                .subject(subject.toString())
                .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .claim("role", "authenticated")
                .build());
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static final class UnknownLengthGetRequestBuilder
      extends AbstractMockHttpServletRequestBuilder<UnknownLengthGetRequestBuilder> {
    private UnknownLengthGetRequestBuilder() {
      super(HttpMethod.GET);
    }

    @Override
    protected UnknownLengthGetRequestBuilder self() {
      return this;
    }

    @Override
    protected MockHttpServletRequest createServletRequest(ServletContext servletContext) {
      return new MockHttpServletRequest(servletContext) {
        @Override
        public int getContentLength() {
          return -1;
        }

        @Override
        public long getContentLengthLong() {
          return -1;
        }

        @Override
        public String getHeader(String name) {
          if (HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
            return null;
          }
          return super.getHeader(name);
        }
      };
    }
  }
}
