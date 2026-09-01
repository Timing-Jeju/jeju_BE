package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferences;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripPreferencesStore;
import com.timingjeju.api.application.trip.TripPreferencesUpdate;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTripPreferencesStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000101");
  private static final UUID OTHER = UUID.fromString("46000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000103");
  private static final UUID START = UUID.fromString("46000000-0000-0000-0000-000000000104");
  private static final UUID END = UUID.fromString("46000000-0000-0000-0000-000000000105");
  private static final UUID ACTIVE = UUID.fromString("46000000-0000-0000-0000-000000000106");
  private static final UUID DAY = UUID.fromString("46000000-0000-0000-0000-000000000107");
  private static final UUID ITEM = UUID.fromString("46000000-0000-0000-0000-000000000108");
  private static final Instant ORIGINAL_AT = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant UPDATE_AT = Instant.parse("2026-09-01T01:00:00Z");

  @Autowired private TripPreferencesStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
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
        "issue-46-" + TRIP,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-01"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_days (
          id,trip_plan_id,day_no,trip_date,start_time,end_time,created_at,updated_at
        ) values (?,?,1,?,'09:00','18:00',?,?)
        """,
        DAY,
        TRIP,
        LocalDate.parse("2026-09-01"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_preferences (
          trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,
          preferred_region_codes,start_place_id,end_place_id,raw_answers,created_at,updated_at
        ) values (?,cast(? as text[]),?,?,cast(? as text[]),null,null,'{}'::jsonb,?,?)
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

  @Test
  void replaceOwned는_선호와_3개_mode를_원자_전체교체한다() {
    TripPreferencesMutation result = store.replaceOwned(update(preferences(START, END)));

    assertThat(result.scheduleEffect()).isEqualTo("none");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(result.tripStatus()).isEqualTo("draft");
    assertThat(result.preferences()).isEqualTo(preferences(START, END));
    assertThat(
            jdbc.query(
                """
                select transport_mode,priority,is_primary
                from public.trip_transport_modes where trip_plan_id=? order by priority
                """,
                (rs, row) ->
                    new TripTransportMode(
                        rs.getString("transport_mode"),
                        rs.getInt("priority"),
                        rs.getBoolean("is_primary")),
                TRIP))
        .containsExactly(
            new TripTransportMode("public_transit", 1, true),
            new TripTransportMode("rental_car", 2, false),
            new TripTransportMode("taxi", 3, false));
    assertThat(
            jdbc.queryForMap(
                """
                select preferred_categories,arrival_region_code,departure_region_code,
                       preferred_region_codes,start_place_id,end_place_id,raw_answers::text as raw_answers
                from public.trip_preferences where trip_plan_id=?
                """,
                TRIP))
        .containsEntry("arrival_region_code", "jeju-si")
        .containsEntry("departure_region_code", "seogwipo-si")
        .containsEntry("start_place_id", START)
        .containsEntry("end_place_id", END)
        .containsEntry("raw_answers", "{}");
  }

  @Test
  void replaceOwned는_다른_owner와_stale_ETag를_구분해_기존_선호를_보존한다() {
    assertCode(
        () ->
            store.replaceOwned(
                new TripPreferencesUpdate(
                    OTHER, TRIP, currentEtag(), preferences(START, END), UPDATE_AT)),
        "TRIP_NOT_FOUND");
    assertCode(
        () ->
            store.replaceOwned(
                new TripPreferencesUpdate(
                    OWNER, TRIP, "\"trip-stale\"", preferences(START, END), UPDATE_AT)),
        "TRIP_VERSION_CONFLICT");

    assertOldPreferencesRemain();
  }

  @Test
  void replaceOwned는_없는_place를_404로_거부하고_두_table을_rollback한다() {
    UUID missing = UUID.fromString("46000000-0000-0000-0000-000000000199");

    assertCode(() -> store.replaceOwned(update(preferences(START, missing))), "PLACE_NOT_FOUND");

    assertOldPreferencesRemain();
  }

  @Test
  void replaceOwned는_active_schedule을_superseded로_바꾸고_pointer와_status를_원자_무효화한다() {
    activateSchedule();

    TripPreferencesMutation result = store.replaceOwned(update(preferences(START, END)));
    jdbc.execute("set constraints all immediate");

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(result.tripStatus()).isEqualTo("draft");
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                ACTIVE))
        .isEqualTo("superseded");
    assertThat(
            jdbc.queryForMap(
                "select status,active_schedule_version_id from public.trip_plans where id=?", TRIP))
        .containsEntry("status", "draft")
        .containsEntry("active_schedule_version_id", null);
  }

  @Test
  void replaceOwned는_canonical_noop이면_active_schedule과_updatedAt을_유지한다() {
    TripPreferencesMutation first = store.replaceOwned(update(preferences(START, END)));
    activateSchedule();

    TripPreferencesMutation noOp =
        store.replaceOwned(
            new TripPreferencesUpdate(
                OWNER,
                TRIP,
                TripEntityTag.strong(TRIP, first.updatedAt()),
                preferences(START, END),
                UPDATE_AT.plusSeconds(60)));
    jdbc.execute("set constraints all immediate");

    assertThat(noOp.scheduleEffect()).isEqualTo("maintained");
    assertThat(noOp.regenerationRequired()).isFalse();
    assertThat(noOp.activeScheduleVersionId()).isEqualTo(ACTIVE);
    assertThat(noOp.tripStatus()).isEqualTo("planned");
    assertThat(noOp.updatedAt()).isEqualTo(first.updatedAt());
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                ACTIVE))
        .isEqualTo("active");
  }

  @Test
  void replaceOwned는_terminal_trip을_409로_거부하고_기존_선호를_보존한다() {
    jdbc.update("update public.trip_plans set status='cancelled' where id=?", TRIP);

    assertCode(
        () -> store.replaceOwned(update(preferences(START, END))), "TRIP_TERMINAL_STATE_CONFLICT");

    assertOldPreferencesRemain();
  }

  private TripPreferencesUpdate update(TripPreferences preferences) {
    return new TripPreferencesUpdate(OWNER, TRIP, currentEtag(), preferences, UPDATE_AT);
  }

  private String currentEtag() {
    Instant updatedAt =
        jdbc.queryForObject(
            "select updated_at from public.trip_plans where id=?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            TRIP);
    return TripEntityTag.strong(TRIP, updatedAt);
  }

  private static TripPreferences preferences(UUID start, UUID end) {
    return new TripPreferences(
        List.of("tourist_attraction", "cafe"),
        "jeju-si",
        "seogwipo-si",
        List.of("seongsan", "aewol"),
        start,
        end,
        List.of(
            new TripTransportMode("public_transit", 1, true),
            new TripTransportMode("rental_car", 2, false),
            new TripTransportMode("taxi", 3, false)));
  }

  private void activateSchedule() {
    jdbc.update(
        """
        insert into public.trip_schedule_versions (
          id,trip_plan_id,version_no,status,source_type,created_by_user_id,created_at,applied_at
        ) values (?,?,1,'draft','ai_generation',?,?,null)
        """,
        ACTIVE,
        TRIP,
        OWNER,
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_items (
          id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
          planned_start_at,planned_end_at,stay_minutes,required,source,created_at,updated_at
        ) values (?,?,?,?,1,'place_visit',?,?::timestamptz,?::timestamptz,60,true,'ai_generated',?,?)
        """,
        ITEM,
        TRIP,
        DAY,
        ACTIVE,
        START,
        "2026-09-01T09:00:00+09:00",
        "2026-09-01T10:00:00+09:00",
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        "update public.trip_schedule_versions set status='active',applied_at=? where id=?",
        Timestamp.from(ORIGINAL_AT),
        ACTIVE);
    jdbc.update(
        "update public.trip_plans set status='planned',active_schedule_version_id=? where id=?",
        ACTIVE,
        TRIP);
    jdbc.execute("set constraints all immediate");
    jdbc.execute("set constraints all deferred");
  }

  private void assertOldPreferencesRemain() {
    assertThat(
            jdbc.queryForMap(
                """
                select arrival_region_code,departure_region_code,start_place_id,end_place_id
                from public.trip_preferences where trip_plan_id=?
                """,
                TRIP))
        .containsEntry("arrival_region_code", "old-arrival")
        .containsEntry("departure_region_code", "old-departure")
        .containsEntry("start_place_id", null)
        .containsEntry("end_place_id", null);
    assertThat(
            jdbc.queryForList(
                "select transport_mode from public.trip_transport_modes where trip_plan_id=?",
                String.class,
                TRIP))
        .containsExactly("taxi");
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String code) {
    assertThatThrownBy(operation)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(code);
  }

  private void insertOwner(UUID id, String suffix) {
    jdbc.update(
        "insert into auth.users (id,email,raw_user_meta_data) values (?,?, '{}'::jsonb)",
        id,
        suffix + "@issue46.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", id, suffix + "@issue46.test");
  }

  private void insertPlace(UUID id, String suffix) {
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,region_code,region_label,location,
          source_provider,source_service
        ) values (?,?,?,?,'VE','50110','제주시',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','issue-46')
        """,
        id,
        "issue-46-" + suffix,
        suffix,
        suffix);
  }
}
