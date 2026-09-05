package com.timingjeju.api.domain.schedule.controller;

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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
      "app.security.jwt.secret=test-" + "only-hs256-signing-key-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "timing-jeju.test.context=schedule-mutation-http-postgresql"
    })
@Import(PostgreSqlTestcontainersConfiguration.class)
@Tag("integration")
class ScheduleMutationHttpPostgreSqlIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SIGNING_KEY = "test-only-hs256-signing-key-32-bytes";
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000401");
  private static final UUID OTHER = UUID.fromString("49000000-0000-0000-0000-000000000402");
  private static final UUID TRIP = UUID.fromString("49000000-0000-0000-0000-000000000403");
  private static final UUID DAY = UUID.fromString("49000000-0000-0000-0000-000000000404");
  private static final UUID ACTIVE = UUID.fromString("49000000-0000-0000-0000-000000000405");
  private static final UUID FIRST = UUID.fromString("49000000-0000-0000-0000-000000000406");
  private static final UUID SECOND = UUID.fromString("49000000-0000-0000-0000-000000000407");
  private static final UUID FIRST_PLACE = UUID.fromString("49000000-0000-0000-0000-000000000408");
  private static final UUID SECOND_PLACE = UUID.fromString("49000000-0000-0000-0000-000000000409");
  private static final UUID ADDED_PLACE = UUID.fromString("49000000-0000-0000-0000-000000000410");

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactionManager;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeEach
  void setUp() {
    cleanUp();
    jdbc.update(
        "insert into auth.users(id,email) values (?,?)",
        OWNER,
        "schedule-mutation-http@issue50.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)",
        OWNER,
        "schedule-mutation-http@issue50.test");
    insertPlace(FIRST_PLACE, "첫 장소", 126.5000, 33.5000);
    insertPlace(SECOND_PLACE, "둘째 장소", 126.5020, 33.5000);
    insertPlace(ADDED_PLACE, "추가 장소", 126.5010, 33.5000);
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision)
        values (?,?,'issue50-http-token','HTTP 일정 추가','draft','2026-09-01','2026-09-01',
                'fixture','issue50-http-v1',1)
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?,?,1,'2026-09-01')",
        DAY,
        TRIP);
    jdbc.update(
        "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type) values (?,?,1,'draft','initial')",
        ACTIVE,
        TRIP);
    insertItem(FIRST, FIRST_PLACE, 1, "2026-09-01T00:00:00Z");
    insertItem(SECOND, SECOND_PLACE, 2, "2026-09-01T03:00:00Z");
    jdbc.update(
        """
        insert into public.trip_legs
          (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
           transport_mode,planned_departure_at,planned_arrival_at,walk_minutes,wait_minutes,
           ride_minutes,transfer_minutes,duration_minutes,buffer_minutes,distance_meters,
           estimated_fare,facts)
        values (?,?,?,1,?,?,'walk',?,?,10,0,0,0,10,0,500,0,'{}'::jsonb)
        """,
        TRIP,
        DAY,
        ACTIVE,
        FIRST,
        SECOND,
        Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T01:10:00Z")));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "update public.trip_schedule_versions set status='active',applied_at=now() where id=?",
                  ACTIVE);
              jdbc.update(
                  "update public.trip_plans set active_schedule_version_id=?,status='planned' where id=?",
                  ACTIVE,
                  TRIP);
            });
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("delete from public.api_idempotency_records where owner_sub=?", OWNER);
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    jdbc.update("delete from public.user_profiles where id=?", OWNER);
    jdbc.update("delete from auth.users where id=?", OWNER);
    jdbc.update(
        "delete from public.tour_places where id in (?,?,?)",
        FIRST_PLACE,
        SECOND_PLACE,
        ADDED_PLACE);
  }

  @ParameterizedTest
  @CsvSource({"1,07:30:00", "2,10:30:00", "3,14:00:00"})
  void 실제_HTTP_POST는_first_middle_last에서_DB에_새_active를_저장하고_GET과_replay가_같다(
      int sequence, String localTime) throws Exception {
    String original = originalFingerprint();
    String key = UUID.randomUUID().toString();

    String requestBody = validBody(ACTIVE, sequence, localTime);
    HttpResponse<byte[]> created = post(token(OWNER), key, ACTIVE, requestBody);

    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.headers().firstValue("Idempotency-Replayed")).contains("false");
    JsonNode body = objectMapper.readTree(created.body());
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "tripId",
                "previousScheduleVersionId",
                "activeScheduleVersionId",
                "versionNo",
                "sourceType",
                "feasibilityStale",
                "changedItemIds",
                "etag",
                "updatedAt"));
    assertThat(body.get("tripId").asText()).isEqualTo(TRIP.toString());
    assertThat(body.get("previousScheduleVersionId").asText()).isEqualTo(ACTIVE.toString());
    assertThat(body.get("versionNo").asInt()).isEqualTo(2);
    assertThat(body.get("sourceType").asText()).isEqualTo("user_edit");
    assertThat(body.get("feasibilityStale").asBoolean()).isTrue();
    assertThat(body.get("changedItemIds").size()).isEqualTo(1);
    assertThat(body.get("updatedAt").asText()).endsWith("+09:00");
    assertThat(created.headers().firstValue("ETag")).contains(body.get("etag").asText());
    assertThat(originalFingerprint()).isEqualTo(original);

    UUID active = UUID.fromString(body.get("activeScheduleVersionId").asText());
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id from public.trip_plans where id=?",
                UUID.class,
                TRIP))
        .isEqualTo(active);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_items where schedule_version_id=?",
                Integer.class,
                active))
        .isEqualTo(3);
    List<UUID> expectedPlaces =
        switch (sequence) {
          case 1 -> List.of(ADDED_PLACE, FIRST_PLACE, SECOND_PLACE);
          case 2 -> List.of(FIRST_PLACE, ADDED_PLACE, SECOND_PLACE);
          case 3 -> List.of(FIRST_PLACE, SECOND_PLACE, ADDED_PLACE);
          default -> throw new AssertionError("unexpected sequence: " + sequence);
        };
    assertThat(
            jdbc.queryForList(
                "select place_id from public.trip_items where schedule_version_id=? order by sequence_no",
                UUID.class,
                active))
        .containsExactlyElementsOf(expectedPlaces);
    assertThat(
            jdbc.query(
                """
                select source.place_id::text || '>' || target.place_id::text
                from public.trip_legs leg
                join public.trip_items source on source.id=leg.from_item_id
                join public.trip_items target on target.id=leg.to_item_id
                where leg.schedule_version_id=? order by leg.sequence_no
                """,
                (rows, row) -> rows.getString(1),
                active))
        .containsExactly(
            expectedPlaces.get(0) + ">" + expectedPlaces.get(1),
            expectedPlaces.get(1) + ">" + expectedPlaces.get(2));

    HttpResponse<byte[]> get = get(token(OWNER));
    assertThat(get.statusCode()).isEqualTo(200);
    JsonNode read = objectMapper.readTree(get.body());
    assertThat(read.at("/scheduleVersion/scheduleVersionId").asText()).isEqualTo(active.toString());
    assertThat(read.at("/days/0/items").size()).isEqualTo(3);
    String committedFingerprint = aggregateFingerprint();
    assertThat(idempotencyCount(key)).isEqualTo(1);

    HttpResponse<byte[]> replay = post(token(OWNER), key, ACTIVE, requestBody);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
    assertThat(replay.headers().firstValue("ETag")).isEqualTo(created.headers().firstValue("ETag"));
    assertThat(replay.body()).containsExactly(created.body());
    assertThat(aggregateFingerprint()).isEqualTo(committedFingerprint);
    assertThat(idempotencyCount(key)).isEqualTo(1);
    HttpResponse<byte[]> differentPayload =
        post(
            token(OWNER),
            key,
            ACTIVE,
            requestBody.replace("\"stayMinutes\":30", "\"stayMinutes\":31"));
    assertProblem(differentPayload, 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(differentPayload.headers().firstValue("Retry-After")).isEmpty();
    assertThat(aggregateFingerprint()).isEqualTo(committedFingerprint);
    assertThat(idempotencyCount(key)).isEqualTo(1);
  }

  @Test
  void 실제_HTTP는_auth_owner_stale_invalid와_멱등키_길이_경계를_구분한다() throws Exception {
    String body = validBody(ACTIVE, 2, "10:30:00");
    String key = UUID.randomUUID().toString();
    String before = aggregateFingerprint();

    HttpResponse<byte[]> unauthorized = post(null, key, ACTIVE, body);
    assertProblem(unauthorized, 401, "AUTHENTICATION_REQUIRED");
    HttpResponse<byte[]> nonOwner = post(token(OTHER), UUID.randomUUID().toString(), ACTIVE, body);
    assertProblem(nonOwner, 404, "TRIP_NOT_FOUND");
    HttpResponse<byte[]> stale =
        post(token(OWNER), UUID.randomUUID().toString(), UUID.randomUUID(), body);
    assertProblem(stale, 409, "ACTIVE_SCHEDULE_VERSION_CONFLICT");
    HttpResponse<byte[]> staleIfMatch =
        post(token(OWNER), UUID.randomUUID().toString(), ACTIVE, body, 2);
    assertProblem(staleIfMatch, 409, "TRIP_VERSION_CONFLICT");
    HttpResponse<byte[]> invalid =
        post(
            token(OWNER),
            UUID.randomUUID().toString(),
            ACTIVE,
            body.replace("\"dayNo\":1", "\"dayNo\":0"));
    assertProblem(invalid, 422, "SCHEDULE_ITEM_INVALID");
    HttpResponse<byte[]> oversizedKey = post(token(OWNER), "x".repeat(129), ACTIVE, body);
    assertProblem(oversizedKey, 400, "IDEMPOTENCY_KEY_INVALID");

    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  private HttpResponse<byte[]> post(String bearer, String key, UUID expected, String body)
      throws Exception {
    return post(bearer, key, expected, body, 1);
  }

  private HttpResponse<byte[]> post(
      String bearer, String key, UUID expected, String body, long expectedRevision)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint("/api/v1/trips/" + TRIP + "/schedule-items"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("If-Match", "\"trip-" + TRIP + "-r" + expectedRevision + "\"")
            .header("Idempotency-Key", key)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    body.replace(ACTIVE.toString(), expected.toString())));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private HttpResponse<byte[]> get(String bearer) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint("/api/v1/trips/" + TRIP + "/schedule"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }

  private void assertProblem(HttpResponse<byte[]> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    JsonNode problem = objectMapper.readTree(response.body());
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
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
  }

  private URI endpoint(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String validBody(UUID expected, int sequence, String localTime) {
    return """
        {"expectedActiveScheduleVersionId":"%s","dayNo":1,"sequenceNo":%d,
         "itemType":"place_visit","placeId":"%s",
         "plannedStartAt":"2026-09-01T%s+09:00","stayMinutes":30}
        """
        .formatted(expected, sequence, ADDED_PLACE, localTime);
  }

  private void insertPlace(UUID id, String name, double longitude, double latitude) {
    jdbc.update(
        """
        insert into public.tour_places(id,name,normalized_name,category,location,source_provider)
        values (?,?,?,'ATTRACTION',ST_SetSRID(ST_MakePoint(?,?),4326)::geography,'fixture')
        """,
        id,
        name,
        name,
        longitude,
        latitude);
  }

  private void insertItem(UUID id, UUID place, int sequence, String start) {
    Instant startsAt = Instant.parse(start);
    jdbc.update(
        """
        insert into public.trip_items
          (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
           planned_start_at,planned_end_at,stay_minutes,source)
        values (?,?,?,?,?,'place_visit',?,?,?,60,'user_input')
        """,
        id,
        TRIP,
        DAY,
        ACTIVE,
        sequence,
        place,
        Timestamp.from(startsAt),
        Timestamp.from(startsAt.plusSeconds(3600)));
  }

  private String originalFingerprint() {
    return jdbc.queryForObject(
        "select md5(string_agg(concat_ws('|',id,sequence_no,place_id,planned_start_at,planned_end_at,stay_minutes),',' order by sequence_no)) from public.trip_items where schedule_version_id=?",
        String.class,
        ACTIVE);
  }

  private String aggregateFingerprint() {
    return jdbc.queryForObject(
        "select md5(concat_ws('|',revision,active_schedule_version_id,(select count(*) from public.trip_schedule_versions where trip_plan_id=?),(select count(*) from public.trip_items where trip_plan_id=?),(select count(*) from public.trip_legs where trip_plan_id=?))) from public.trip_plans where id=?",
        String.class,
        TRIP,
        TRIP,
        TRIP,
        TRIP);
  }

  private int idempotencyCount(String key) {
    return jdbc.queryForObject(
        "select count(*) from public.api_idempotency_records where owner_sub=? and idempotency_key=?::uuid",
        Integer.class,
        OWNER,
        key);
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
                .claim("role", "authenticated")
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    jwt.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
