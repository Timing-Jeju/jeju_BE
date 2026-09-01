package com.timingjeju.api.domain.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class ScheduleHttpPostgreSqlIntegrationTest {
  private static final String ISSUER = "http://127.0.0.1:54321/auth/v1";
  private static final String SECRET = "test-" + "only-hs256-secret-with-at-least-32-bytes";
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000301");
  private static final UUID OTHER = UUID.fromString("49000000-0000-0000-0000-000000000302");
  private static final UUID TRIP = UUID.fromString("49000000-0000-0000-0000-000000000303");
  private static final UUID DAY = UUID.fromString("49000000-0000-0000-0000-000000000304");
  private static final UUID VERSION = UUID.fromString("49000000-0000-0000-0000-000000000305");
  private static final UUID ITEM = UUID.fromString("49000000-0000-0000-0000-000000000306");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUpActiveSchedule() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.user_profiles where id = ?", OWNER);
    jdbc.update("delete from auth.users where id = ?", OWNER);
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", OWNER, "schedule-http@issue49.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        "schedule-http@issue49.test");
    jdbc.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, title, status, start_date, end_date, source_mode, data_version
        ) values (?, ?, 'issue49-http-token', 'HTTP 일정', 'draft',
                  '2026-09-01', '2026-09-01', 'fixture', 'issue49-http-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, '2026-09-01')",
        DAY,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_schedule_versions (
          id, trip_plan_id, version_no, status, source_type, resulting_score
        ) values (?, ?, 1, 'draft', 'initial', 0)
        """,
        VERSION,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_items (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type,
          title, planned_start_at, planned_end_at, stay_minutes, source, facts
        ) values (?, ?, ?, ?, 1, 'custom', '공항 도착', ?, ?, 60, 'user_input',
                  '{"location":{"lat":33.5,"lng":126.5}}'::jsonb)
        """,
        ITEM,
        TRIP,
        DAY,
        VERSION,
        Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")));
    jdbc.update(
        "update public.trip_schedule_versions set status = 'active', applied_at = now() where id = ?",
        VERSION);
    jdbc.update(
        "update public.trip_plans set active_schedule_version_id = ?, status = 'planned' where id = ?",
        VERSION,
        TRIP);
    jdbc.execute("set constraints all immediate");
  }

  @Test
  void JWT부터_PostgreSQL까지_active_schedule을_읽고_어떤_행도_변경하지_않는다() throws Exception {
    String before = databaseFingerprint();

    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(OWNER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scheduleVersion.scheduleVersionId").value(VERSION.toString()))
        .andExpect(jsonPath("$.scheduleVersion.score").value(0))
        .andExpect(jsonPath("$.scheduleVersion.feasibilityStale").value(true))
        .andExpect(jsonPath("$.days[0].items[0].title").value("공항 도착"))
        .andExpect(jsonPath("$.days[0].items[0].progress").value((Object) null))
        .andExpect(jsonPath("$.days[0].legs").isEmpty());

    assertThat(databaseFingerprint()).isEqualTo(before);
  }

  @Test
  void cross_owner와_인증_경계는_각각_은닉_404와_canonical_401이다() throws Exception {
    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(OTHER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));

    mvc.perform(get("/api/v1/trips/{tripId}/schedule", TRIP))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mvc.perform(
            get("/api/v1/trips/{tripId}/schedule", TRIP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
  }

  private String databaseFingerprint() {
    return jdbc.queryForObject(
        """
        select md5(concat_ws('|', p.active_schedule_version_id::text, v.status,
                             v.resulting_score::text, i.title, i.updated_at::text))
        from public.trip_plans p
        join public.trip_schedule_versions v on v.id = p.active_schedule_version_id
        join public.trip_items i on i.schedule_version_id = v.id
        where p.id = ?
        """,
        String.class,
        TRIP);
  }

  private static String token(UUID subject) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience("authenticated")
            .subject(subject.toString())
            .claim("role", "authenticated")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
