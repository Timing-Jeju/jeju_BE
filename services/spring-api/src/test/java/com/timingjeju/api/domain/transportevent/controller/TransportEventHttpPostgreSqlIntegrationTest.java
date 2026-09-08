package com.timingjeju.api.domain.transportevent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=transport-event-http-postgresql"
    })
@Import(PostgreSqlTestcontainersConfiguration.class)
@Tag("integration")
class TransportEventHttpPostgreSqlIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = randomKey();
  private static final UUID OWNER = UUID.fromString("47300000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("47300000-0000-0000-0000-000000000002");
  private static final UUID TRIP = UUID.fromString("47300000-0000-0000-0000-000000000003");
  private static final UUID PLACE = UUID.fromString("47300000-0000-0000-0000-000000000004");
  private static final UUID ACTIVE = UUID.fromString("47300000-0000-0000-0000-000000000005");

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactionManager;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    cleanUp();
    owner(OWNER);
    owner(OTHER);
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,region_code,region_label,location,
          source_provider,source_service
        ) values (?, 'issue47-http-place', '제주국제공항', '제주국제공항', 'PC',
          'jeju-si', '제주시', ST_SetSRID(ST_MakePoint(126.493,33.510),4326)::geography,
          'fixture', 'issue47-http')
        """,
        PLACE);
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,revision
        ) values (?, ?, 'issue47-http-token', '항공 선박 HTTP 여행', 'draft',
          '2026-09-01', '2026-09-05', 'Asia/Seoul', 'normal', 'fixture', 'issue47-http-v1', 1)
        """,
        TRIP,
        OWNER);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.tour_places where id = ?", PLACE);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", OWNER, OTHER);
  }

  @Test
  void 실제_HTTP_PUT은_create_noop_replace와_active_무효화를_DB와_동일하게_반환한다() throws Exception {
    HttpResponse<byte[]> created = put(token(OWNER), 1, arrival(PLACE, null));

    assertThat(created.statusCode()).isEqualTo(200);
    JsonNode createdBody = success(created, 2);
    assertThat(createdBody.get("scheduleEffect").asText()).isEqualTo("none");
    assertThat(createdBody.get("regenerationRequired").asBoolean()).isFalse();
    assertThat(createdBody.get("eventType").asText()).isEqualTo("arrival");
    assertThat(createdBody.get("deleted").asBoolean()).isFalse();
    assertThat(createdBody.at("/event/terminalPlaceId").asText()).isEqualTo(PLACE.toString());
    assertThat(createdBody.at("/event/scheduledAt").asText())
        .isEqualTo("2026-09-01T09:00:00+09:00");
    String createdFingerprint = fingerprint();

    HttpResponse<byte[]> noOp = put(token(OWNER), 2, arrival(PLACE, null));
    JsonNode noOpBody = success(noOp, 2);
    assertThat(noOpBody.get("scheduleEffect").asText()).isEqualTo("maintained");
    assertThat(noOpBody.get("regenerationRequired").asBoolean()).isFalse();
    assertThat(fingerprint()).isEqualTo(createdFingerprint);

    installActiveSchedule();
    HttpResponse<byte[]> replaced = put(token(OWNER), 2, arrival(null, "  제주공항  "));
    JsonNode replacedBody = success(replaced, 3);
    assertThat(replacedBody.get("scheduleEffect").asText()).isEqualTo("invalidated");
    assertThat(replacedBody.get("regenerationRequired").asBoolean()).isTrue();
    assertThat(replacedBody.get("activeScheduleVersionId").isNull()).isTrue();
    assertThat(replacedBody.get("tripStatus").asText()).isEqualTo("draft");
    assertThat(replacedBody.at("/event/customTerminalName").asText()).isEqualTo("제주공항");
    assertThat(
            jdbc.queryForMap(
                "select status,active_schedule_version_id,total_score,revision from public.trip_plans where id=?",
                TRIP))
        .containsEntry("status", "draft")
        .containsEntry("active_schedule_version_id", null)
        .containsEntry("total_score", null)
        .containsEntry("revision", 3L);
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                ACTIVE))
        .isEqualTo("superseded");
  }

  @Test
  void 실제_HTTP_DELETE는_selected만_삭제하고_missing과_잘못된_query_body는_DB를_보존한다() throws Exception {
    success(put(token(OWNER), 1, arrival(PLACE, null)), 2);
    success(put(token(OWNER), 2, departure()), 3);

    HttpResponse<byte[]> deleted = delete(token(OWNER), 3, "eventType=arrival", null);
    JsonNode deletedBody = success(deleted, 4);
    assertThat(deletedBody.get("deleted").asBoolean()).isTrue();
    assertThat(deletedBody.get("eventType").asText()).isEqualTo("arrival");
    assertThat(deletedBody.get("event").isNull()).isTrue();
    assertThat(
            jdbc.queryForList(
                "select event_type from public.trip_transport_events where trip_plan_id=?",
                String.class,
                TRIP))
        .containsExactly("departure");
    String before = fingerprint();

    assertProblem(
        delete(token(OWNER), 4, "eventType=arrival", null), 404, "TRANSPORT_EVENT_NOT_FOUND");
    assertProblem(delete(token(OWNER), 4, "", null), 400, "INVALID_REQUEST");
    assertProblem(
        delete(token(OWNER), 4, "eventType=arrival&eventType=departure", null),
        400,
        "INVALID_REQUEST");
    assertProblem(delete(token(OWNER), 4, "eventType=train", null), 400, "INVALID_REQUEST");
    assertProblem(delete(token(OWNER), 4, "eventType=departure", "{}"), 400, "INVALID_REQUEST");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 실제_HTTP는_owner_CAS_terminal과_KST_date_XOR_length를_problem으로_구분하고_DB를_보존한다()
      throws Exception {
    String before = fingerprint();
    JsonNode nullProblem = assertProblem(put(token(OWNER), 1, "null"), 400, "INVALID_REQUEST");
    JsonNode wrappedNullProblem =
        assertProblem(put(token(OWNER), 1, " \n null \t"), 400, "INVALID_REQUEST");
    assertThat(registryProjection(wrappedNullProblem)).isEqualTo(registryProjection(nullProblem));
    assertProblem(put(token(OTHER), 1, arrival(PLACE, null)), 404, "TRIP_NOT_FOUND");
    assertProblem(put(token(OWNER), null, arrival(PLACE, null)), 400, "INVALID_REQUEST");
    assertProblem(put(token(OWNER), 2, arrival(PLACE, null)), 409, "TRIP_VERSION_CONFLICT");
    assertProblem(
        put(token(OWNER), 1, arrivalAt(PLACE, null, "2026-09-01T09:00:00+08:00")),
        422,
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertProblem(
        put(token(OWNER), 1, arrivalAt(PLACE, null, "2026-09-02T09:00:00+09:00")),
        422,
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertProblem(
        put(token(OWNER), 1, arrival(PLACE, "제주공항")), 422, "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertProblem(
        put(token(OWNER), 1, arrival(null, null)), 422, "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertProblem(
        put(token(OWNER), 1, arrival(null, "가".repeat(101))),
        422,
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);

    installActiveSchedule();
    jdbc.update("update public.trip_plans set status='completed' where id=?", TRIP);
    String terminalBefore = fingerprint();
    assertProblem(put(token(OWNER), 1, arrival(PLACE, null)), 409, "TRIP_TERMINAL_STATE_CONFLICT");
    assertThat(fingerprint()).isEqualTo(terminalBefore);
  }

  private JsonNode success(HttpResponse<byte[]> response, long revision) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    assertThat(response.headers().firstValue("ETag"))
        .contains("\"trip-" + TRIP + "-r" + revision + "\"");
    assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "tripId",
                "scheduleEffect",
                "regenerationRequired",
                "activeScheduleVersionId",
                "tripStatus",
                "updatedAt",
                "eventType",
                "deleted",
                "event"));
    assertThat(body.get("tripId").asText()).isEqualTo(TRIP.toString());
    assertThat(body.get("updatedAt").asText()).endsWith("+09:00");
    if (!body.get("event").isNull()) {
      assertThat(body.get("event").propertyNames())
          .containsExactlyInAnyOrderElementsOf(
              Set.of(
                  "eventType",
                  "transportType",
                  "terminalPlaceId",
                  "customTerminalName",
                  "scheduledAt",
                  "transportNumber",
                  "note"));
    }
    return body;
  }

  private JsonNode assertProblem(HttpResponse<byte[]> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    JsonNode problem = objectMapper.readTree(response.body());
    assertThat(problem.propertyNames())
        .containsExactlyInAnyOrder(
            "type", "title", "status", "detail", "instance", "code", "traceId", "fieldErrors");
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(problem.get("code").asText()).isEqualTo(code);
    assertThat(problem.get("type").asText()).startsWith("https://api.timing-jeju.com/problems/");
    assertThat(problem.get("traceId").asText()).isNotBlank();
    assertThat(response.headers().firstValue("X-Trace-Id"))
        .contains(problem.get("traceId").asText());
    assertThat(problem.get("instance").asText())
        .isEqualTo("urn:timing-jeju:problem:" + problem.get("traceId").asText());
    return problem;
  }

  private static String registryProjection(JsonNode problem) {
    return String.join(
        "|",
        problem.get("type").asText(),
        problem.get("title").asText(),
        problem.get("status").asText(),
        problem.get("detail").asText(),
        problem.get("code").asText(),
        problem.get("fieldErrors").toString());
  }

  private HttpResponse<byte[]> put(String bearer, Integer revision, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint("/api/v1/trips/" + TRIP + "/transport-event"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (revision != null) {
      request.header("If-Match", "\"trip-" + TRIP + "-r" + revision + "\"");
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private HttpResponse<byte[]> delete(String bearer, int revision, String query, String body)
      throws Exception {
    String suffix = query.isEmpty() ? "" : "?" + query;
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint("/api/v1/trips/" + TRIP + "/transport-event" + suffix))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + bearer)
            .header("If-Match", "\"trip-" + TRIP + "-r" + revision + "\"");
    request.method(
        "DELETE",
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) request.header("Content-Type", "application/json");
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private URI endpoint(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String arrival(UUID placeId, String customName) {
    return arrivalAt(placeId, customName, "2026-09-01T09:00:00+09:00");
  }

  private static String arrivalAt(UUID placeId, String customName, String scheduledAt) {
    return """
        {"eventType":"arrival","transportType":"flight","terminalPlaceId":%s,
         "customTerminalName":%s,"scheduledAt":"%s","transportNumber":"KE1001","note":null}
        """
        .formatted(json(placeId), json(customName), scheduledAt);
  }

  private static String departure() {
    return """
        {"eventType":"departure","transportType":"ferry","terminalPlaceId":null,
         "customTerminalName":"제주항","scheduledAt":"2026-09-05T19:00:00+09:00",
         "transportNumber":"퀸제누비아2호","note":null}
        """;
  }

  private static String json(Object value) {
    if (value == null) return "null";
    return "\"" + value + "\"";
  }

  private void owner(UUID id) {
    jdbc.update("insert into auth.users(id,email) values (?,?)", id, id + "@issue47-http.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", id, id + "@issue47-http.test");
  }

  private void installActiveSchedule() {
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) values (?,?,1,'draft','initial')",
        ACTIVE,
        TRIP);
    for (int index = 0; index < 5; index++) {
      UUID dayId =
          UUID.nameUUIDFromBytes(("issue47-http-day-" + index).getBytes(StandardCharsets.UTF_8));
      UUID itemId =
          UUID.nameUUIDFromBytes(("issue47-http-item-" + index).getBytes(StandardCharsets.UTF_8));
      java.time.LocalDate date = java.time.LocalDate.parse("2026-09-01").plusDays(index);
      jdbc.update(
          "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,?,?)",
          dayId,
          TRIP,
          index + 1,
          java.sql.Date.valueOf(date));
      jdbc.update(
          """
          insert into public.trip_items (
            id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,
            planned_start_at,planned_end_at,stay_minutes,source,facts
          ) values (?, ?, ?, ?, 1, 'custom', '검증 일정',
            (?::date + time '09:00') at time zone 'Asia/Seoul',
            (?::date + time '10:00') at time zone 'Asia/Seoul',
            60, 'user_input', '{"location":{"lat":33.5,"lng":126.5}}'::jsonb)
          """,
          itemId,
          TRIP,
          dayId,
          ACTIVE,
          java.sql.Date.valueOf(date),
          java.sql.Date.valueOf(date));
    }
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update public.trip_schedule_versions set status='active',applied_at=now() where id=?",
                  ACTIVE);
              jdbc.update(
                  "update public.trip_plans set status='planned',active_schedule_version_id=?,total_score=88 where id=?",
                  ACTIVE,
                  TRIP);
            });
  }

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select md5(concat_ws('|',p.revision,p.status,p.active_schedule_version_id,p.total_score,
          p.updated_at,(select string_agg(concat_ws(':',e.event_type,e.transport_type,
          e.terminal_place_id,e.terminal_name,e.scheduled_at,e.transport_number,e.note,e.updated_at),
          ',' order by e.event_type) from public.trip_transport_events e where e.trip_plan_id=p.id)))
        from public.trip_plans p where p.id=?
        """,
        String.class,
        TRIP);
  }

  private static String token(UUID subject) throws Exception {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(subject.toString())
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
