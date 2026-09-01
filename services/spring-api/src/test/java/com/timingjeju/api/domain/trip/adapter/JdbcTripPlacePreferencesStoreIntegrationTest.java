package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.TripPlacePreferencesStore;
import com.timingjeju.api.application.trip.TripPlacePreferencesUpdate;
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

class JdbcTripPlacePreferencesStoreIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("48000000-0000-0000-0000-000000000101");
  private static final UUID OTHER = UUID.fromString("48000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("48000000-0000-0000-0000-000000000103");
  private static final UUID PLACE_A = UUID.fromString("48000000-0000-0000-0000-000000000104");
  private static final UUID PLACE_B = UUID.fromString("48000000-0000-0000-0000-000000000105");
  private static final UUID PLACE_STALE = UUID.fromString("48000000-0000-0000-0000-000000000106");
  private static final UUID ACTIVE = UUID.fromString("48000000-0000-0000-0000-000000000107");
  private static final UUID DAY = UUID.fromString("48000000-0000-0000-0000-000000000108");
  private static final UUID ITEM = UUID.fromString("48000000-0000-0000-0000-000000000109");
  private static final UUID DAY_2 = UUID.fromString("48000000-0000-0000-0000-000000000110");
  private static final UUID DAY_3 = UUID.fromString("48000000-0000-0000-0000-000000000111");
  private static final UUID ITEM_2 = UUID.fromString("48000000-0000-0000-0000-000000000112");
  private static final UUID ITEM_3 = UUID.fromString("48000000-0000-0000-0000-000000000113");
  private static final UUID CALENDAR_TRIP = UUID.fromString("48000000-0000-0000-0000-000000000114");
  private static final Instant ORIGINAL_AT = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant UPDATE_AT = Instant.parse("2026-09-01T01:00:00.123456Z");

  @Autowired private TripPlacePreferencesStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    insertOwner(OWNER, "owner");
    insertOwner(OTHER, "other");
    insertPlace(PLACE_A, "a", false);
    insertPlace(PLACE_B, "b", false);
    insertPlace(PLACE_STALE, "stale", true);
    savePlace(OWNER, PLACE_A);
    savePlace(OTHER, PLACE_B);
    savePlace(OWNER, PLACE_STALE);
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,created_at,updated_at
        ) values (?,?,?,'제주 여행','draft',?,?,'Asia/Seoul','normal','fixture','issue-48',?,?)
        """,
        TRIP,
        OWNER,
        "issue-48-" + TRIP,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-03"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_days (
          id,trip_plan_id,day_no,trip_date,start_time,end_time,created_at,updated_at
        ) values
          (?,?,2,?,'09:00','18:00',?,?),
          (?,?,3,?,'09:00','18:00',?,?)
        """,
        DAY_2,
        TRIP,
        LocalDate.parse("2026-09-02"),
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT),
        DAY_3,
        TRIP,
        LocalDate.parse("2026-09-03"),
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
  }

  @Test
  void replaceOwned는_owner의_유효한_저장장소를_전체교체하고_canonical_순서로_반환한다() {
    savePlace(OWNER, PLACE_B);
    List<TripPlacePreference> requested =
        List.of(
            new TripPlacePreference(PLACE_A, "must_visit", 2, 100),
            new TripPlacePreference(PLACE_B, "avoid", null, 10));

    TripPlacePreferencesMutation result = store.replaceOwned(update(requested));

    assertThat(result.scheduleEffect()).isEqualTo("none");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(result.tripStatus()).isEqualTo("draft");
    assertThat(result.updatedAt()).isEqualTo(UPDATE_AT);
    assertThat(result.preferences()).containsExactlyElementsOf(requested);
    assertThat(
            jdbc.queryForList(
                """
                select place_id::text||':'||preference_type||':'||coalesce(target_day_no::text,'all')||':'||priority||':'||source
                from public.trip_place_preferences
                where trip_plan_id=? order by priority desc,place_id
                """,
                String.class,
                TRIP))
        .containsExactly(
            PLACE_A + ":must_visit:2:100:saved_place", PLACE_B + ":avoid:all:10:saved_place");
  }

  @Test
  void replaceOwned는_빈_배열로_기존_장소를_모두_삭제한다() {
    store.replaceOwned(update(List.of(new TripPlacePreference(PLACE_A, "must_visit", null, 50))));

    TripPlacePreferencesMutation cleared =
        store.replaceOwned(
            new TripPlacePreferencesUpdate(
                OWNER, TRIP, etag(), List.of(), UPDATE_AT.plusSeconds(1)));

    assertThat(cleared.preferences()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_place_preferences where trip_plan_id=?",
                Integer.class,
                TRIP))
        .isZero();
  }

  @Test
  void replaceOwned는_여행_Day_범위를_벗어나면_422이고_기존_목록을_유지한다() {
    store.replaceOwned(update(List.of(new TripPlacePreference(PLACE_A, "must_visit", 3, 50))));

    assertCode(
        () ->
            store.replaceOwned(
                new TripPlacePreferencesUpdate(
                    OWNER,
                    TRIP,
                    etag(),
                    List.of(new TripPlacePreference(PLACE_A, "must_visit", 4, 50)),
                    UPDATE_AT.plusSeconds(1))),
        "PLACE_PREFERENCE_CONSTRAINT_VIOLATION");

    assertStoredTargetDay(3);
  }

  @Test
  void replaceOwned는_타인에게만_저장됐거나_stale인_장소를_같은_404로_숨긴다() {
    store.replaceOwned(update(List.of(new TripPlacePreference(PLACE_A, "must_visit", null, 50))));

    for (UUID invalid : List.of(PLACE_B, PLACE_STALE)) {
      assertCode(
          () ->
              store.replaceOwned(
                  new TripPlacePreferencesUpdate(
                      OWNER,
                      TRIP,
                      etag(),
                      List.of(new TripPlacePreference(invalid, "avoid", null, 50)),
                      UPDATE_AT.plusSeconds(1))),
          "PLACE_NOT_FOUND");
      assertThat(storedPlaceIds()).containsExactly(PLACE_A);
    }
  }

  @Test
  void replaceOwned는_stale_ETag_타인_trip과_terminal_trip을_원자_거부한다() {
    List<TripPlacePreference> requested =
        List.of(new TripPlacePreference(PLACE_A, "must_visit", null, 50));

    assertCode(
        () ->
            store.replaceOwned(
                new TripPlacePreferencesUpdate(OTHER, TRIP, etag(), requested, UPDATE_AT)),
        "TRIP_NOT_FOUND");
    assertCode(
        () ->
            store.replaceOwned(
                new TripPlacePreferencesUpdate(
                    OWNER, TRIP, "\"trip-stale\"", requested, UPDATE_AT)),
        "TRIP_VERSION_CONFLICT");
    jdbc.update("update public.trip_plans set status='cancelled' where id=?", TRIP);
    assertCode(() -> store.replaceOwned(update(requested)), "TRIP_TERMINAL_STATE_CONFLICT");
    assertThat(storedPlaceIds()).isEmpty();
  }

  @Test
  void replaceOwned는_canonical_noop이면_active_schedule과_updatedAt을_유지한다() {
    List<TripPlacePreference> requested =
        List.of(new TripPlacePreference(PLACE_A, "must_visit", null, 50));
    TripPlacePreferencesMutation first = store.replaceOwned(update(requested));
    activateSchedule();

    TripPlacePreferencesMutation noOp =
        store.replaceOwned(
            new TripPlacePreferencesUpdate(
                OWNER,
                TRIP,
                TripEntityTag.strong(TRIP, first.updatedAt()),
                requested,
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
  void replaceOwned는_변경이면_active_schedule을_superseded하고_trip을_draft로_전환한다() {
    activateSchedule();

    TripPlacePreferencesMutation result =
        store.replaceOwned(update(List.of(new TripPlacePreference(PLACE_A, "must_visit", 1, 50))));
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
  }

  @Test
  void schema는_같은_trip_place의_두_role을_direct_DML에서도_거부한다() {
    jdbc.update(
        """
        insert into public.trip_place_preferences (
          trip_plan_id,place_id,preference_type,target_day_no,priority
        ) values (?,?,'must_visit',1,50)
        """,
        TRIP,
        PLACE_A);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.trip_place_preferences (
                      trip_plan_id,place_id,preference_type,target_day_no,priority
                    ) values (?,?,'avoid',1,50)
                    """,
                    TRIP,
                    PLACE_A))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void schema는_Day_범위를_direct_DML에서도_거부한다() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.trip_place_preferences (
                      trip_plan_id,place_id,preference_type,target_day_no,priority
                    ) values (?,?,'must_visit',4,50)
                    """,
                    TRIP,
                    PLACE_A))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("target day");
  }

  @Test
  void schema는_기존_선호_Day를_제외하는_여행기간_축소도_거부한다() {
    jdbc.update(
        """
        insert into public.trip_place_preferences (
          trip_plan_id,place_id,preference_type,target_day_no,priority
        ) values (?,?,'must_visit',3,50)
        """,
        TRIP,
        PLACE_A);

    assertThatThrownBy(
            () ->
                jdbc.update("update public.trip_plans set end_date='2026-09-01' where id=?", TRIP))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("calendar excludes");
  }

  @Test
  void schema는_선호_Day가_남는_여행기간_축소를_허용한다() {
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version,created_at,updated_at
        ) values (?,?,'issue48-calendar-trip','draft','2026-09-01','2026-09-03',
          'Asia/Seoul','normal','fixture','issue-48',?,?)
        """,
        CALENDAR_TRIP,
        OWNER,
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_place_preferences (
          trip_plan_id,place_id,preference_type,target_day_no,priority
        ) values (?,?,'must_visit',2,50)
        """,
        CALENDAR_TRIP,
        PLACE_A);

    assertThat(
            jdbc.update(
                "update public.trip_plans set end_date='2026-09-02' where id=?", CALENDAR_TRIP))
        .isOne();
  }

  @Test
  void schema는_priority_범위를_direct_DML에서도_거부한다() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into public.trip_place_preferences (
                      trip_plan_id,place_id,preference_type,target_day_no,priority
                    ) values (?,?,'must_visit',1,101)
                    """,
                    TRIP,
                    PLACE_A))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void schema는_브라우저_role의_direct_table_권한을_제거하고_service_role만_Crud를_허용한다() {
    for (String role : List.of("anon", "authenticated")) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_table_privilege(?, 'public.trip_place_preferences', ?)",
                    Boolean.class,
                    role,
                    privilege))
            .isFalse();
      }
    }
    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('service_role', 'public.trip_place_preferences', ?)",
                  Boolean.class,
                  privilege))
          .isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege('service_role', 'public.trip_place_preferences', 'TRUNCATE')",
                Boolean.class))
        .isFalse();
  }

  private TripPlacePreferencesUpdate update(List<TripPlacePreference> preferences) {
    return new TripPlacePreferencesUpdate(OWNER, TRIP, etag(), preferences, UPDATE_AT);
  }

  private String etag() {
    Instant updatedAt =
        jdbc.queryForObject(
            "select updated_at from public.trip_plans where id=?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            TRIP);
    return TripEntityTag.strong(TRIP, updatedAt);
  }

  private List<UUID> storedPlaceIds() {
    return jdbc.queryForList(
        "select place_id from public.trip_place_preferences where trip_plan_id=? order by place_id",
        UUID.class,
        TRIP);
  }

  private void assertStoredTargetDay(int expected) {
    assertThat(
            jdbc.queryForObject(
                "select target_day_no from public.trip_place_preferences where trip_plan_id=? and place_id=?",
                Integer.class,
                TRIP,
                PLACE_A))
        .isEqualTo(expected);
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
        PLACE_A,
        "2026-09-01T09:00:00+09:00",
        "2026-09-01T10:00:00+09:00",
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT));
    jdbc.update(
        """
        insert into public.trip_items (
          id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
          planned_start_at,planned_end_at,stay_minutes,required,source,created_at,updated_at
        ) values
          (?,?,?,?,1,'place_visit',?,?::timestamptz,?::timestamptz,60,true,'ai_generated',?,?),
          (?,?,?,?,1,'place_visit',?,?::timestamptz,?::timestamptz,60,true,'ai_generated',?,?)
        """,
        ITEM_2,
        TRIP,
        DAY_2,
        ACTIVE,
        PLACE_A,
        "2026-09-02T09:00:00+09:00",
        "2026-09-02T10:00:00+09:00",
        Timestamp.from(ORIGINAL_AT),
        Timestamp.from(ORIGINAL_AT),
        ITEM_3,
        TRIP,
        DAY_3,
        ACTIVE,
        PLACE_A,
        "2026-09-03T09:00:00+09:00",
        "2026-09-03T10:00:00+09:00",
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

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(expected);
  }

  private void insertOwner(UUID id, String suffix) {
    jdbc.update(
        "insert into auth.users (id,email,raw_user_meta_data) values (?,?, '{}'::jsonb)",
        id,
        suffix + "@issue48.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)", id, suffix + "@issue48.test");
  }

  private void insertPlace(UUID id, String suffix, boolean stale) {
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,region_code,region_label,location,
          source_provider,source_service,stale
        ) values (?,?,?,?,'VE','50110','제주시',
          ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','issue-48',?)
        """,
        id,
        "issue-48-" + suffix,
        suffix,
        suffix,
        stale);
  }

  private void savePlace(UUID owner, UUID placeId) {
    jdbc.update("insert into public.saved_places (user_id,place_id) values (?,?)", owner, placeId);
  }
}
