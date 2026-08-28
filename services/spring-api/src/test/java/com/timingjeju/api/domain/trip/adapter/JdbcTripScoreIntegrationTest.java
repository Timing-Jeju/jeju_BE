package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcTripScoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000961");
  private static final UUID TRIP = UUID.fromString("44000000-0000-0000-0000-000000000962");
  private static final UUID DAY = UUID.fromString("44000000-0000-0000-0000-000000000963");
  private static final UUID VERSION = UUID.fromString("44000000-0000-0000-0000-000000000964");
  private static final UUID ITEM = UUID.fromString("44000000-0000-0000-0000-000000000966");
  private static final Instant FACTS = Instant.parse("2026-08-25T00:00:00Z");
  private static final Instant RESPONSE = Instant.parse("2026-08-25T00:04:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate namedJdbc;
  @Autowired private JdbcTripStore store;

  @BeforeEach
  void setUpTripWithActiveSuccessfulRun() {
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", OWNER, "trip-score@issue44.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        "trip-score@issue44.test");
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
        values (?, ?, ?, 'score test', current_date, current_date, 'fixture', 'issue44-score-v1')
        """,
        TRIP,
        OWNER,
        "issue44-score-token");
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, current_date)",
        DAY,
        TRIP);
    MapSqlParameterSource lineage =
        new MapSqlParameterSource()
            .addValue("versionId", VERSION)
            .addValue("tripId", TRIP)
            .addValue("dayId", DAY)
            .addValue("itemId", ITEM);
    namedJdbc.update(
        """
        insert into public.trip_schedule_versions
          (id, trip_plan_id, version_no, status, source_type)
        values (:versionId, :tripId, 1, 'draft', 'initial')
        """,
        lineage);
    namedJdbc.update(
        """
        insert into public.trip_items (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type,
          title, planned_start_at, planned_end_at, stay_minutes, source, facts
        ) values (:itemId, :tripId, :dayId, :versionId, 1, 'custom', 'score fixture',
                  (current_date + time '09:00') at time zone 'Asia/Seoul',
                  (current_date + time '10:00') at time zone 'Asia/Seoul',
                  60, 'user_input', '{"location":{"lat":33.4,"lng":126.5}}'::jsonb)
        """,
        lineage);
    namedJdbc.update(
        """
        update public.trip_plans set active_schedule_version_id = :versionId
        where id = :tripId
        """,
        lineage);
    namedJdbc.update(
        """
        update public.trip_schedule_versions set status = 'active', applied_at = now()
        where id = :versionId and trip_plan_id = :tripId
        """,
        lineage);
    jdbc.execute("set constraints all immediate");
  }

  @Test
  void json_number_string과_observedAt_absent_malformed_offset을_쌍으로_구분한다() {
    assertScore(1, "{\"score\":81,\"expiresAt\":\"2026-08-25T00:05:00Z\"}", 81, FACTS);
    assertScore(2, "{\"score\":\"81\",\"expiresAt\":\"2026-08-25T00:05:00Z\"}", null, null);
    assertScore(
        3,
        "{\"score\":81,\"observedAt\":\"bad\",\"expiresAt\":\"2026-08-25T00:05:00Z\"}",
        null,
        null);
    assertScore(
        4,
        "{\"score\":81,\"observedAt\":\"2026-08-25T09:00:00+09:00\",\"expiresAt\":\"2026-08-25T00:05:00Z\"}",
        81,
        FACTS);
  }

  private void assertScore(
      int sequence, String summary, Integer expectedScore, Instant expectedObservedAt) {
    UUID run =
        UUID.nameUUIDFromBytes(
            ("issue44-score-run-" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version, facts_snapshot_at,
          source_data_version, result_summary, started_at, completed_at
        ) values (?, ?, ?, ?, 'feasibility', 'succeeded', ?, 'feasibility.v1',
                  'issue44-score-v1', ?, 'issue44-source-v1', ?::jsonb, ?, ?)
        """,
        run,
        TRIP,
        DAY,
        VERSION,
        "issue44-score-input-" + sequence,
        Timestamp.from(FACTS),
        summary,
        Timestamp.from(FACTS.plusSeconds(30)),
        Timestamp.from(FACTS.plusSeconds(60L + sequence)));

    TripAggregate trip = store.findOwnedDiagnosticForTest(OWNER, TRIP, RESPONSE).orElseThrow();

    assertThat(trip.totalScore()).isEqualTo(expectedScore);
    if (expectedScore == null) {
      assertThat(trip.scoreProvenance()).isNull();
    } else {
      assertThat(trip.scoreProvenance().observedAt()).isEqualTo(expectedObservedAt);
    }
  }
}
