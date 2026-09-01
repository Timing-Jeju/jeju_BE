package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
@Import(PostgreSqlTestcontainersConfiguration.class)
@Tag("integration")
class TripPreferencesHttpPostgreSqlIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = "test-" + "only-hs256-secret-with-at-least-32-bytes";
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000201");
  private static final UUID OTHER = UUID.fromString("46000000-0000-0000-0000-000000000202");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000203");
  private static final UUID START = UUID.fromString("46000000-0000-0000-0000-000000000204");
  private static final UUID END = UUID.fromString("46000000-0000-0000-0000-000000000205");
  private static final Instant ORIGINAL_AT = Instant.parse("2026-09-01T00:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanUp();
    insertOwner(OWNER, "owner");
    insertOwner(OTHER, "other");
    insertPlace(START, "start");
    insertPlace(END, "end");
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,created_at,updated_at
        ) values (?,?,?,'제주 여행','draft',?,?,'Asia/Seoul','normal','fixture','issue-46',?,?)
        """,
        TRIP,
        OWNER,
        "issue-46-http-" + TRIP,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-01"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_transport_modes (
          trip_plan_id,transport_mode,priority,is_primary,created_at
        ) values (?,'taxi',1,true,?)
        """,
        TRIP,
        Timestamp.from(ORIGINAL_AT));
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    jdbc.update("delete from public.tour_places where id in (?,?)", START, END);
    jdbc.update("delete from public.user_profiles where id in (?,?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?,?)", OWNER, OTHER);
  }

  @Test
  void owner_JWT의_PUT_preferences는_실제_DB_두_table을_전체교체한다() throws Exception {
    String expected = TripEntityTag.strong(TRIP, ORIGINAL_AT);

    mvc.perform(request(OWNER, expected, validBody()))
        .andExpect(status().isOk())
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(jsonPath("$.preferences.startPlaceId").value(START.toString()))
        .andExpect(jsonPath("$.preferences.endPlaceId").value(END.toString()))
        .andExpect(jsonPath("$.preferences.transportModes.length()").value(3));

    assertThat(
            jdbc.queryForList(
                """
                select transport_mode from public.trip_transport_modes
                where trip_plan_id=? order by priority
                """,
                String.class,
                TRIP))
        .containsExactly("public_transit", "rental_car", "taxi");
    assertThat(
            jdbc.queryForMap(
                """
                select arrival_region_code,departure_region_code,start_place_id,end_place_id
                from public.trip_preferences where trip_plan_id=?
                """,
                TRIP))
        .containsEntry("arrival_region_code", "jeju-si")
        .containsEntry("departure_region_code", "seogwipo-si")
        .containsEntry("start_place_id", START)
        .containsEntry("end_place_id", END);
  }

  @Test
  void cross_owner는_404로_은닉하고_기존_mode를_유지한다() throws Exception {
    mvc.perform(request(OTHER, TripEntityTag.strong(TRIP, ORIGINAL_AT), validBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));

    assertThat(currentModes()).containsExactly("taxi");
  }

  @Test
  void walk와_stale_ETag는_각각_422와_409이며_기존_mode를_유지한다() throws Exception {
    mvc.perform(
            request(
                OWNER,
                TripEntityTag.strong(TRIP, ORIGINAL_AT),
                validBody().replace("public_transit", "walk")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("PREFERENCE_CONSTRAINT_VIOLATION"));
    mvc.perform(request(OWNER, "\"trip-stale\"", validBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRIP_VERSION_CONFLICT"));

    assertThat(currentModes()).containsExactly("taxi");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      UUID user, String etag, String body) throws Exception {
    return put("/api/v1/trips/{tripId}/preferences", TRIP)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user))
        .header(HttpHeaders.IF_MATCH, etag)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private List<String> currentModes() {
    return jdbc.queryForList(
        "select transport_mode from public.trip_transport_modes where trip_plan_id=? order by priority",
        String.class,
        TRIP);
  }

  private static String validBody() {
    return """
        {
          "preferredCategories":["tourist_attraction","cafe"],
          "arrivalRegionCode":"jeju-si",
          "departureRegionCode":"seogwipo-si",
          "preferredRegionCodes":["seongsan","aewol"],
          "startPlaceId":"46000000-0000-0000-0000-000000000204",
          "endPlaceId":"46000000-0000-0000-0000-000000000205",
          "transportModes":[
            {"mode":"public_transit","priority":1,"primary":true},
            {"mode":"rental_car","priority":2,"primary":false},
            {"mode":"taxi","priority":3,"primary":false}
          ]
        }
        """;
  }

  private void insertOwner(UUID id, String suffix) {
    jdbc.update(
        "insert into auth.users (id,email,raw_user_meta_data) values (?,?, '{}'::jsonb)",
        id,
        suffix + "-http@issue46.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)",
        id,
        suffix + "-http@issue46.test");
  }

  private void insertPlace(UUID id, String suffix) {
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,region_code,region_label,location,
          source_provider,source_service
        ) values (?,?,?,?,'VE','50110','제주시',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','issue-46-http')
        """,
        id,
        "issue-46-http-" + suffix,
        suffix,
        suffix);
  }

  private static String token(UUID user) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(user.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
