package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.domain.trip.dto.response.TripAggregateResponse;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripDetailProjectionIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  protected int expectedPostgresMajor() {
    return 16;
  }

  @Test
  void 선택한_PostgreSQL_주버전에서_실제로_검증한다() {
    int version =
        Integer.parseInt(
            jdbc.queryForObject("select current_setting('server_version_num')", String.class));
    assertThat(version / 10000).isEqualTo(expectedPostgresMajor());
  }

  private static final UUID OWNER = UUID.fromString("24600000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("24600000-0000-0000-0000-000000000002");
  private static final UUID TRIP = UUID.fromString("24600000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcTripStore store;
  @Autowired private AccommodationService accommodations;
  @Autowired private TransportEventService events;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private com.timingjeju.api.global.idempotency.JdbcIdempotencyRecordRepository receipts;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired private com.timingjeju.api.domain.trip.controller.TripController controller;

  @Autowired
  private com.timingjeju.api.domain.trip.controller.TripProblemExceptionHandler problemHandler;

  @BeforeEach
  void 준비한다() {
    정리한다();
    for (UUID owner : List.of(OWNER, OTHER)) {
      jdbc.update(
          "insert into auth.users (id,email) values (?,?)", owner, owner + "@issue246.test");
      jdbc.update(
          "insert into public.user_profiles (id,email) values (?,?)",
          owner,
          owner + "@issue246.test");
    }
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version)
        values (?,?,'issue246-trip','복원 여행','draft','2026-09-01','2026-09-05',
                'Asia/Seoul','normal','fixture','issue246-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_transport_modes (trip_plan_id,transport_mode,priority,is_primary) values (?,'public_transit',1,true)",
        TRIP);
    for (int index = 0; index < 5; index++) {
      jdbc.update(
          "insert into public.trip_days (trip_plan_id,day_no,trip_date) values (?,?,?)",
          TRIP,
          index + 1,
          LocalDate.parse("2026-09-01").plusDays(index));
    }
  }

  @AfterEach
  void 정리한다() {
    jdbc.update("delete from public.api_idempotency_records where owner_sub = ?", OWNER);
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.user_profiles where id in (?,?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?,?)", OWNER, OTHER);
  }

  @Test
  void 조회_도중_child가_저장되어도_revision과_숙소와_교통은_같은_스냅샷이다() throws Exception {
    var rootRead = new java.util.concurrent.CountDownLatch(1);
    var continueRead = new java.util.concurrent.CountDownLatch(1);
    var delayed =
        new NamedParameterJdbcTemplate(jdbc) {
          @Override
          public <T> List<T> query(
              String sql, java.util.Map<String, ?> params, RowMapper<T> mapper) {
            var result = super.query(sql, params, mapper);
            if (sql.contains("from public.trip_plans p")) {
              rootRead.countDown();
              try {
                if (!continueRead.await(15, java.util.concurrent.TimeUnit.SECONDS))
                  throw new AssertionError("child writer 완료 대기 초과");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
              }
            }
            return result;
          }
        };
    var proxy =
        new org.springframework.aop.framework.ProxyFactory(new JdbcTripStore(jdbc, delayed));
    proxy.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactions,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    var reader = (com.timingjeju.api.application.trip.TripStore) proxy.getProxy();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var reading = executor.submit(() -> reader.findOwned(OWNER, TRIP, NOW).orElseThrow());
      try {
        assertThat(rootRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        accommodations.create(OWNER, TRIP, "issue246-snapshot", 1, accommodation("동시 저장 숙소", 1, 5));
        events.put(
            OWNER, TRIP, expected(2), event("arrival", "flight", "2026-09-01T09:30:00+09:00"));
      } finally {
        continueRead.countDown();
      }
      var snapshot = reading.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(snapshot.revision()).isEqualTo(1);
      assertThat(snapshot.accommodations()).isEmpty();
      assertThat(snapshot.transportEvents().arrival()).isNull();
      assertThat(snapshot.transportEvents().departure()).isNull();
    }
    var current = store.findOwned(OWNER, TRIP, NOW).orElseThrow();
    assertThat(current.revision()).isEqualTo(3);
    assertThat(current.accommodations()).hasSize(1);
    assertThat(current.transportEvents().arrival()).isNotNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"POST", "PUT"})
  void projection_이전_완료_receipt는_원본_replay하고_현재_GET은_필수_child를_반환한다(String method)
      throws Exception {
    boolean create = method.equals("POST");
    String path = create ? "/api/v1/trips" : "/api/v1/trips/" + TRIP + "/day-activity-windows";
    if (!create) {
      jdbc.update(
          "update public.trip_days set start_time='09:00', end_time='18:00' where trip_plan_id=?",
          TRIP);
      jdbc.update("update public.trip_plans set revision=2 where id=?", TRIP);
    }
    var legacy = (tools.jackson.databind.node.ObjectNode) detail();
    legacy.remove("transportEvents");
    legacy.remove("accommodations");
    byte[] original = mapper.writeValueAsBytes(legacy);
    byte[] body =
        create
            ? "{\"title\":\"복원 여행\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-05\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : mapper.writeValueAsBytes(
                java.util.Map.of(
                    "days",
                    store.findOwned(OWNER, TRIP, NOW).orElseThrow().days().stream()
                        .map(
                            day ->
                                java.util.Map.of(
                                    "dayId",
                                    day.dayId().toString(),
                                    "startTime",
                                    "09:00",
                                    "endTime",
                                    "18:00"))
                        .toList()));
    String key = "24600000-0000-0000-0000-000000000099";
    var request =
        com.timingjeju.api.application.idempotency.IdempotencyRequest.create(
            OWNER, method, path, key, body);
    Instant completed = Instant.now().minusSeconds(3600);
    var acquired =
        receipts.acquire(request.scope(), request.requestHash(), completed.minusSeconds(1));
    long revision = create ? 1 : 2;
    var headers =
        new java.util.ArrayList<com.timingjeju.api.application.idempotency.IdempotencyHeader>();
    headers.add(
        new com.timingjeju.api.application.idempotency.IdempotencyHeader(
            "Content-Type", "application/json"));
    headers.add(
        new com.timingjeju.api.application.idempotency.IdempotencyHeader("ETag", tag(revision)));
    if (create)
      headers.add(
          new com.timingjeju.api.application.idempotency.IdempotencyHeader(
              "Location", "/api/v1/trips/" + TRIP));
    var snapshot =
        new com.timingjeju.api.application.idempotency.IdempotencyResponse(
            create ? 201 : 200, headers, original);
    receipts.complete(
        request.scope(),
        request.requestHash(),
        acquired.attemptToken().orElseThrow(),
        snapshot,
        completed);
    var builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), path)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body);
    if (!create) builder.header("If-Match", tag(1));
    var replay = performHttp(OWNER, builder).getResponse();
    assertThat(replay.getStatus()).isEqualTo(snapshot.status());
    assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
    assertThat(replay.getHeader("ETag")).isEqualTo(tag(revision));
    if (create) assertThat(replay.getHeader("Location")).isEqualTo("/api/v1/trips/" + TRIP);
    assertThat(replay.getContentAsByteArray()).isEqualTo(original);
    var current = store.findOwned(OWNER, TRIP, NOW).orElseThrow();
    assertThat(current.revision()).isEqualTo(revision);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_plans where user_id=?", Long.class, OWNER))
        .isEqualTo(1L);
    var latest = mapper.readTree(readHttp(OWNER).getResponse().getContentAsByteArray());
    assertThat(latest.has("transportEvents")).isTrue();
    assertThat(latest.has("accommodations")).isTrue();
  }

  @Test
  void 미입력_child도_required_null_pair와_빈_숙소배열을_반환한다() {
    JsonNode body = detail();
    assertThat(body.has("transportEvents")).isTrue();
    assertThat(body.path("transportEvents").size()).isEqualTo(2);
    assertThat(body.path("transportEvents").path("arrival").isNull()).isTrue();
    assertThat(body.path("transportEvents").path("departure").isNull()).isTrue();
    assertThat(body.path("accommodations").isArray()).isTrue();
    assertThat(body.path("accommodations").size()).isZero();
  }

  @Test
  void 입출도_write의_정규화_payload와_ETag를_새_조회에서_그대로_복원한다() {
    var arrival =
        events.put(
            OWNER, TRIP, expected(1), event("arrival", "flight", "2026-09-01T09:30:00+09:00"));
    assertThat(detail().path("transportEvents").path("arrival"))
        .isEqualTo(mapper.valueToTree(arrival.event()));
    assertThat(detail().path("transportEvents").path("departure").isNull()).isTrue();
    var departure =
        events.put(
            OWNER, TRIP, expected(2), event("departure", "ferry", "2026-09-05T18:00:00+09:00"));
    var aggregate = store.findOwned(OWNER, TRIP, NOW).orElseThrow();
    JsonNode body = mapper.valueToTree(TripAggregateResponse.from(aggregate));
    assertThat(body.path("transportEvents").path("arrival"))
        .isEqualTo(mapper.valueToTree(arrival.event()));
    assertThat(body.path("transportEvents").path("departure"))
        .isEqualTo(mapper.valueToTree(departure.event()));
    assertThat(TripEntityTag.strong(TRIP, aggregate.revision())).isEqualTo(departure.etag());
  }

  @Test
  void 숙소_write_payload와_sequence_정렬을_별도_조회에서_복원한다() {
    var middle =
        accommodations.create(OWNER, TRIP, "issue246-middle", 1, accommodation("가운데 숙소", 2, 3));
    var after =
        accommodations.create(OWNER, TRIP, "issue246-after", 2, accommodation("마지막 숙소", 3, 5));
    var before =
        accommodations.create(OWNER, TRIP, "issue246-before", 3, accommodation("첫 숙소", 1, 2));
    JsonNode body = detail();
    assertThat(body.path("accommodations").size()).isEqualTo(3);
    assertThat(body.path("accommodations").get(0))
        .isEqualTo(mapper.readTree(before.snapshot().body()).path("accommodation"));
    assertThat(body.path("accommodations").get(1).path("accommodationId"))
        .isEqualTo(mapper.readTree(middle.snapshot().body()).path("accommodationId"));
    assertThat(body.path("accommodations").get(2).path("accommodationId"))
        .isEqualTo(mapper.readTree(after.snapshot().body()).path("accommodationId"));
    for (int index = 0; index < 3; index++) {
      assertThat(body.path("accommodations").get(index).path("sequenceNo").asInt())
          .isEqualTo(index + 1);
    }
    assertThat(tag(store.findOwned(OWNER, TRIP, NOW).orElseThrow().revision()))
        .isEqualTo(before.snapshot().etag());
  }

  @Test
  void 여행_root_PATCH_응답도_기존_child를_보존하고_새_조회와_일치한다() throws Exception {
    accommodations.create(OWNER, TRIP, "issue246-root-patch", 1, accommodation("유지 숙소", 1, 5));
    var updated =
        performHttp(
                OWNER,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/trips/{tripId}", TRIP)
                    .header("If-Match", tag(2))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"수정 여행\"}"))
            .getResponse();
    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(updated.getHeader("ETag")).isEqualTo(tag(3));
    JsonNode response = mapper.readTree(updated.getContentAsByteArray());
    assertThat(response.path("accommodations").size()).isEqualTo(1);
    var restored = readHttp(OWNER).getResponse();
    assertThat(restored.getStatus()).isEqualTo(200);
    assertThat(restored.getHeader("ETag")).isEqualTo(tag(3));
    assertThat(response.path("accommodations"))
        .isEqualTo(mapper.readTree(restored.getContentAsByteArray()).path("accommodations"));
    assertThat(response.path("title").asText()).isEqualTo("수정 여행");
  }

  @Test
  void 저장_commit_후_새_HTTP_GET이_child와_최신_ETag를_복원한다() throws Exception {
    accommodations.create(OWNER, TRIP, "issue246-http", 1, accommodation("HTTP 복원 숙소", 1, 5));
    var saved =
        events.put(
            OWNER, TRIP, expected(2), event("arrival", "flight", "2026-09-01T09:30:00+09:00"));
    var response = readHttp(OWNER).getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("ETag")).isEqualTo(saved.etag());
    JsonNode body = mapper.readTree(response.getContentAsByteArray());
    assertThat(body.path("accommodations").get(0).path("name").asText()).isEqualTo("HTTP 복원 숙소");
    assertThat(body.path("transportEvents").path("arrival"))
        .isEqualTo(mapper.valueToTree(saved.event()));
  }

  @Test
  void child가_있는_trip도_비소유_HTTP_조회에서는_404로_은닉한다() throws Exception {
    accommodations.create(OWNER, TRIP, "issue246-hidden", 1, accommodation("노출 금지 숙소", 1, 5));
    var response = readHttp(OTHER).getResponse();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText())
        .isEqualTo("TRIP_NOT_FOUND");
    assertThat(response.getContentAsString())
        .doesNotContain("노출 금지 숙소", "accommodations", "transportEvents");
  }

  @Test
  void child_행수가_늘어도_추가_SQL은_항상_두_개다() {
    CountingJdbcTemplate counted = new CountingJdbcTemplate(dataSource);
    JdbcTripStore reader = new JdbcTripStore(counted, new NamedParameterJdbcTemplate(counted));
    assertThat(reader.findOwned(OWNER, TRIP, NOW)).isPresent();
    assertThat(counted.childQueries).isEqualTo(2);
    accommodations.create(OWNER, TRIP, "issue246-count-a", 1, accommodation("첫 숙소", 1, 2));
    accommodations.create(OWNER, TRIP, "issue246-count-b", 2, accommodation("다음 숙소", 2, 5));
    counted.childQueries = 0;
    assertThat(reader.findOwned(OWNER, TRIP, NOW)).isPresent();
    assertThat(counted.childQueries).isEqualTo(2);
  }

  @Test
  void 비소유와_없는_trip은_child_SQL_없이_같은_빈_조회다() {
    CountingJdbcTemplate counted = new CountingJdbcTemplate(dataSource);
    JdbcTripStore reader = new JdbcTripStore(counted, new NamedParameterJdbcTemplate(counted));
    assertThat(reader.findOwned(OTHER, TRIP, NOW)).isEmpty();
    assertThat(reader.findOwned(OWNER, OTHER, NOW)).isEmpty();
    assertThat(counted.childQueries).isZero();
  }

  @Test
  void child_SQL_실패는_부분응답이나_원인노출_없이_전체_실패한다() {
    for (int failedQuery : List.of(1, 2)) {
      CountingJdbcTemplate counted = new CountingJdbcTemplate(dataSource);
      counted.failOnQuery = failedQuery;
      JdbcTripStore reader = new JdbcTripStore(counted, new NamedParameterJdbcTemplate(counted));
      assertThatThrownBy(() -> reader.findOwned(OWNER, TRIP, NOW))
          .isInstanceOf(TripException.class)
          .hasNoCause()
          .extracting(failure -> ((TripException) failure).code())
          .isEqualTo("TRIP_DATA_UNAVAILABLE");
      assertThat(counted.childQueries).isEqualTo(failedQuery);
    }
  }

  private JsonNode detail() {
    return mapper.valueToTree(
        TripAggregateResponse.from(store.findOwned(OWNER, TRIP, NOW).orElseThrow()));
  }

  private org.springframework.test.web.servlet.MvcResult readHttp(UUID owner) throws Exception {
    return performHttp(
        owner,
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/trips/{tripId}", TRIP));
  }

  private org.springframework.test.web.servlet.MvcResult performHttp(
      UUID owner,
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new com.timingjeju.api.global.security.CurrentUserAuthentication(
            new com.timingjeju.api.application.security.CurrentUser(
                owner,
                com.timingjeju.api.application.security.AuthenticatedRole.AUTHENTICATED,
                null)));
    org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    try {
      return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
          .setControllerAdvice(problemHandler)
          .build()
          .perform(request)
          .andReturn();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private static CreateAccommodationCommand accommodation(String name, int start, int end) {
    return new CreateAccommodationCommand(
        null,
        name,
        LocalDate.of(2026, 9, start),
        LocalDate.of(2026, 9, end),
        LocalTime.of(15, 0),
        LocalTime.of(11, 0));
  }

  private static PutTransportEventCommand event(String kind, String transport, String time) {
    return new PutTransportEventCommand(
        kind, transport, null, "사용자 입력 터미널", OffsetDateTime.parse(time), null, null);
  }

  private static TripExpectedRevision expected(long revision) {
    return new TripExpectedRevision(TRIP, revision);
  }

  private static String tag(long revision) {
    return TripEntityTag.strong(TRIP, revision);
  }

  private static final class CountingJdbcTemplate extends JdbcTemplate {
    private int childQueries;
    private int failOnQuery;

    CountingJdbcTemplate(DataSource source) {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      if (sql.contains("public.trip_accommodations")
          || sql.contains("public.trip_transport_events")) {
        childQueries++;
        if (childQueries == failOnQuery)
          throw new DataAccessResourceFailureException("test-only child read failure");
      }
      return super.query(sql, mapper, args);
    }
  }
}
