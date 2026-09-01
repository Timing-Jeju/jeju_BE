package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferences;
import com.timingjeju.api.application.trip.TripPreferencesStore;
import com.timingjeju.api.application.trip.TripPreferencesUpdate;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripPreferencesRollbackIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000301");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000302");
  private static final Instant ORIGINAL_AT = Instant.parse("2026-09-01T00:00:00Z");

  @Autowired private TripPreferencesStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanUp();
    jdbc.update(
        "insert into auth.users (id,email,raw_user_meta_data) values (?,?,'{}'::jsonb)",
        OWNER,
        "rollback@issue46.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", OWNER, "rollback@issue46.test");
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,created_at,updated_at
        ) values (?,?,?,'제주 여행','draft',?,?,'Asia/Seoul','normal','fixture','issue-46',?,?)
        """,
        TRIP,
        OWNER,
        "issue-46-rollback-" + TRIP,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-01"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_preferences (
          trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,
          preferred_region_codes,raw_answers,created_at,updated_at
        ) values (?,cast(? as text[]),?,?,cast(? as text[]),'{}'::jsonb,?,?)
        """,
        TRIP,
        new String[] {"shopping"},
        "old-arrival",
        "old-departure",
        new String[] {"old-region"},
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
    jdbc.update("delete from public.user_profiles where id=?", OWNER);
    jdbc.update("delete from auth.users where id=?", OWNER);
  }

  @Test
  void mode_batch_constraint가_실패하면_먼저_쓴_preferences와_delete한_mode도_rollback된다() {
    TripPreferences invalid =
        new TripPreferences(
            List.of("cafe"),
            "new-arrival",
            "new-departure",
            List.of("new-region"),
            null,
            null,
            List.of(
                new TripTransportMode("public_transit", 1, true),
                new TripTransportMode("taxi", 1, false)));

    assertThatThrownBy(
            () ->
                store.replaceOwned(
                    new TripPreferencesUpdate(
                        OWNER,
                        TRIP,
                        TripEntityTag.strong(TRIP, ORIGINAL_AT),
                        invalid,
                        ORIGINAL_AT.plusSeconds(60))))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("PREFERENCE_CONSTRAINT_VIOLATION");

    assertThat(
            jdbc.queryForMap(
                """
                select arrival_region_code,departure_region_code
                from public.trip_preferences where trip_plan_id=?
                """,
                TRIP))
        .containsEntry("arrival_region_code", "old-arrival")
        .containsEntry("departure_region_code", "old-departure");
    assertThat(
            jdbc.queryForList(
                "select transport_mode from public.trip_transport_modes where trip_plan_id=?",
                String.class,
                TRIP))
        .containsExactly("taxi");
  }
}
