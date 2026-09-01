package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleLookup;
import com.timingjeju.api.application.schedule.ScheduleSnapshot;
import com.timingjeju.api.domain.schedule.adapter.JdbcScheduleStore;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcScheduleStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000101");
  private static final UUID OTHER_OWNER = UUID.fromString("49000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("49000000-0000-0000-0000-000000000103");
  private static final UUID OTHER_TRIP = UUID.fromString("49000000-0000-0000-0000-000000000104");
  private static final UUID NO_ACTIVE_TRIP =
      UUID.fromString("49000000-0000-0000-0000-000000000105");
  private static final UUID DAY_ONE = UUID.fromString("49000000-0000-0000-0000-000000000111");
  private static final UUID DAY_TWO = UUID.fromString("49000000-0000-0000-0000-000000000112");
  private static final UUID ACTIVE = UUID.fromString("49000000-0000-0000-0000-000000000121");
  private static final UUID CANDIDATE = UUID.fromString("49000000-0000-0000-0000-000000000122");
  private static final UUID OTHER_VERSION = UUID.fromString("49000000-0000-0000-0000-000000000123");
  private static final UUID INVALID_VERSION =
      UUID.fromString("49000000-0000-0000-0000-000000000124");
  private static final UUID FIRST = UUID.fromString("49000000-0000-0000-0000-000000000131");
  private static final UUID SECOND = UUID.fromString("49000000-0000-0000-0000-000000000132");
  private static final UUID THIRD = UUID.fromString("49000000-0000-0000-0000-000000000133");
  private static final UUID LEG = UUID.fromString("49000000-0000-0000-0000-000000000141");
  private static final Instant RESPONSE = Instant.parse("2026-09-01T03:04:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcScheduleStore store;

  @BeforeEach
  void setUpCanonicalSchedule() {
    insertOwner(OWNER, "schedule-owner@issue49.test");
    insertOwner(OTHER_OWNER, "schedule-other@issue49.test");
    insertTrip(TRIP, OWNER, "schedule-trip", true);
    insertTrip(NO_ACTIVE_TRIP, OWNER, "schedule-no-active", false);
    insertTrip(OTHER_TRIP, OTHER_OWNER, "schedule-other-trip", false);
    insertDay(TRIP, DAY_ONE, 1, "2026-09-01");
    insertDay(TRIP, DAY_TWO, 2, "2026-09-02");
    UUID otherDay = UUID.fromString("49000000-0000-0000-0000-000000000113");
    insertDay(OTHER_TRIP, otherDay, 1, "2026-09-01");
    insertVersion(ACTIVE, TRIP, 1, "draft", "initial", null, 81);
    insertVersion(CANDIDATE, TRIP, 2, "draft", "ai_generation", ACTIVE, null);
    insertVersion(INVALID_VERSION, TRIP, 3, "draft", "user_edit", ACTIVE, null);
    insertVersion(OTHER_VERSION, OTHER_TRIP, 1, "draft", "initial", null, null);
    insertItem(FIRST, DAY_ONE, ACTIVE, 1, "place_visit", "성산일출봉", "2026-09-01T00:00:00Z");
    insertItem(SECOND, DAY_ONE, ACTIVE, 2, "meal", "점심", "2026-09-01T01:30:00Z");
    insertItem(THIRD, DAY_TWO, ACTIVE, 1, "custom", "둘째 날", "2026-09-02T00:00:00Z");
    insertLeg();
    jdbc.update(
        """
        insert into public.trip_item_progress (
          trip_plan_id, schedule_version_id, trip_item_id, status,
          actual_started_at, actual_arrived_at, actual_completed_at, updated_at
        ) values (?, ?, ?, 'arrived', ?, null, null, ?)
        """,
        TRIP,
        ACTIVE,
        FIRST,
        Timestamp.from(Instant.parse("2026-09-01T00:01:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T00:02:00Z")));
    insertFreshRun();
    UUID candidateItem = UUID.fromString("49000000-0000-0000-0000-000000000134");
    insertItem(candidateItem, DAY_ONE, CANDIDATE, 1, "custom", "후보 일정", "2026-09-01T02:00:00Z");
    insertItem(
        UUID.fromString("49000000-0000-0000-0000-000000000135"),
        DAY_TWO,
        CANDIDATE,
        1,
        "custom",
        "후보 둘째 날",
        "2026-09-02T02:00:00Z");
    insertInvalidDraft();
    jdbc.update(
        "update public.trip_schedule_versions set status = 'active', applied_at = now() where id = ?",
        ACTIVE);
    jdbc.update(
        "update public.trip_schedule_versions set status = 'candidate' where id = ?", CANDIDATE);
    jdbc.update(
        "update public.trip_plans set active_schedule_version_id = ? where id = ?", ACTIVE, TRIP);
    jdbc.execute("set constraints all immediate");
  }

  @Test
  void active_조회는_day_item_leg_progress를_안정_순서와_fresh_score로_반환한다() {
    ScheduleLookup lookup = store.readOwned(OWNER, TRIP, null, RESPONSE);

    assertThat(lookup.status()).isEqualTo(ScheduleLookup.Status.FOUND);
    ScheduleSnapshot schedule = lookup.schedule();
    assertThat(schedule.scheduleVersion().scheduleVersionId()).isEqualTo(ACTIVE);
    assertThat(schedule.scheduleVersion().score()).isEqualTo(81);
    assertThat(schedule.scheduleVersion().feasibilityStale()).isFalse();
    assertThat(schedule.days()).extracting(day -> day.dayNo()).containsExactly(1, 2);
    assertThat(schedule.days().getFirst().items())
        .extracting(item -> item.itemId())
        .containsExactly(FIRST, SECOND);
    assertThat(schedule.days().getFirst().items().getFirst().progress().status())
        .isEqualTo("arrived");
    assertThat(schedule.days().getFirst().items().get(1).progress()).isNull();
    assertThat(schedule.days().getFirst().legs()).hasSize(1);
    assertThat(schedule.days().getFirst().legs().getFirst().fromItemId()).isEqualTo(FIRST);
    assertThat(schedule.days().getFirst().legs().getFirst().toItemId()).isEqualTo(SECOND);
    assertThat(schedule.days().get(1).legs()).isEmpty();
  }

  @Test
  void explicit_candidate는_active_pointer와_무관하게_같은_trip_version을_반환한다() {
    ScheduleLookup lookup = store.readOwned(OWNER, TRIP, CANDIDATE, RESPONSE);

    assertThat(lookup.status()).isEqualTo(ScheduleLookup.Status.FOUND);
    assertThat(lookup.schedule().scheduleVersion().scheduleVersionId()).isEqualTo(CANDIDATE);
    assertThat(lookup.schedule().scheduleVersion().status()).isEqualTo("candidate");
    assertThat(lookup.schedule().scheduleVersion().score()).isNull();
    assertThat(lookup.schedule().scheduleVersion().feasibilityStale()).isTrue();
    assertThat(lookup.schedule().days().getFirst().items()).hasSize(1);
    assertThat(lookup.schedule().days().get(1).items()).hasSize(1);
  }

  @Test
  void owner_trip과_version_선택자는_resource_존재를_누설하지_않는다() {
    assertThat(store.readOwned(OTHER_OWNER, TRIP, null, RESPONSE).status())
        .isEqualTo(ScheduleLookup.Status.TRIP_NOT_FOUND);
    assertThat(store.readOwned(OWNER, NO_ACTIVE_TRIP, null, RESPONSE).status())
        .isEqualTo(ScheduleLookup.Status.VERSION_NOT_FOUND);
    assertThat(store.readOwned(OWNER, TRIP, OTHER_VERSION, RESPONSE).status())
        .isEqualTo(ScheduleLookup.Status.VERSION_NOT_FOUND);
    assertThat(
            store
                .readOwned(
                    OWNER, UUID.fromString("49000000-0000-0000-0000-000000000199"), null, RESPONSE)
                .status())
        .isEqualTo(ScheduleLookup.Status.TRIP_NOT_FOUND);
  }

  @Test
  void expiry_정각과_malformed_freshness는_stale_true로_fail_closed한다() {
    ScheduleSnapshot atExpiry =
        store.readOwned(OWNER, TRIP, null, Instant.parse("2026-09-01T03:05:00Z")).schedule();
    assertThat(atExpiry.scheduleVersion().feasibilityStale()).isTrue();

    jdbc.update(
        """
        update public.compute_runs
        set result_summary = '{"observedAt":"bad","expiresAt":"2026-09-01T03:10:00Z"}'::jsonb
        where schedule_version_id = ? and run_type = 'feasibility'
        """,
        ACTIVE);
    ScheduleSnapshot malformed = store.readOwned(OWNER, TRIP, null, RESPONSE).schedule();
    assertThat(malformed.scheduleVersion().score()).isEqualTo(81);
    assertThat(malformed.scheduleVersion().feasibilityStale()).isTrue();
  }

  @Test
  void 불완전한_required_projection과_인접하지_않은_leg는_가짜값없이_data_unavailable이다() {
    assertThatThrownBy(() -> store.readOwned(OWNER, TRIP, INVALID_VERSION, RESPONSE))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("TRIP_DATA_UNAVAILABLE");
  }

  private void insertOwner(UUID owner, String email) {
    jdbc.update("insert into auth.users (id, email) values (?, ?)", owner, email);
    jdbc.update("insert into public.user_profiles (id, email) values (?, ?)", owner, email);
  }

  private void insertTrip(UUID trip, UUID owner, String token, boolean planned) {
    jdbc.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, title, status, start_date, end_date, source_mode, data_version
        ) values (?, ?, ?, '일정 조회', ?, '2026-09-01', '2026-09-02', 'fixture', 'issue49-v1')
        """,
        trip,
        owner,
        token,
        planned ? "planned" : "draft");
  }

  private void insertDay(UUID trip, UUID day, int dayNo, String date) {
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, ?, ?::date)",
        day,
        trip,
        dayNo,
        date);
  }

  private void insertVersion(
      UUID version,
      UUID trip,
      int versionNo,
      String status,
      String sourceType,
      UUID base,
      Integer score) {
    jdbc.update(
        """
        insert into public.trip_schedule_versions (
          id, trip_plan_id, version_no, base_schedule_version_id, status, source_type, resulting_score
        ) values (?, ?, ?, ?, ?, ?, ?)
        """,
        version,
        trip,
        versionNo,
        base,
        status,
        sourceType,
        score);
  }

  private void insertItem(
      UUID item, UUID day, UUID version, int sequence, String type, String title, String start) {
    Instant startedAt = Instant.parse(start);
    jdbc.update(
        """
        insert into public.trip_items (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type,
          title, planned_start_at, planned_end_at, stay_minutes, buffer_after_minutes,
          required, source, facts
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 60, 10, true, 'user_input',
                  '{"location":{"lat":33.4,"lng":126.5}}'::jsonb)
        """,
        item,
        TRIP,
        day,
        version,
        sequence,
        type,
        title,
        Timestamp.from(startedAt),
        Timestamp.from(startedAt.plusSeconds(3600)));
  }

  private void insertLeg() {
    jdbc.update(
        """
        insert into public.trip_legs (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
          from_item_id, to_item_id, transport_mode, planned_departure_at, planned_arrival_at,
          walk_minutes, wait_minutes, ride_minutes, transfer_minutes, duration_minutes,
          buffer_minutes, distance_meters, estimated_fare, risk_score
        ) values (?, ?, ?, ?, 1, ?, ?, 'public_transit', ?, ?, 5, 3, 10, 2, 20, 0, 5000, 1250, 20)
        """,
        LEG,
        TRIP,
        DAY_ONE,
        ACTIVE,
        FIRST,
        SECOND,
        Timestamp.from(Instant.parse("2026-09-01T01:10:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T01:30:00Z")));
  }

  private void insertFreshRun() {
    jdbc.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version, facts_snapshot_at,
          source_data_version, result_summary, started_at, completed_at
        ) values (?, ?, ?, ?, 'feasibility', 'succeeded', 'issue49-input', 'feasibility.v1',
                  'issue49-v1', ?, 'issue49-source-v1', ?::jsonb, ?, ?)
        """,
        UUID.fromString("49000000-0000-0000-0000-000000000151"),
        TRIP,
        DAY_ONE,
        ACTIVE,
        Timestamp.from(Instant.parse("2026-09-01T02:59:00Z")),
        "{\"observedAt\":\"2026-09-01T02:59:00Z\",\"expiresAt\":\"2026-09-01T03:05:00Z\"}",
        Timestamp.from(Instant.parse("2026-09-01T03:00:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T03:00:00Z")));
  }

  private void insertInvalidDraft() {
    UUID from = UUID.fromString("49000000-0000-0000-0000-000000000136");
    UUID to = UUID.fromString("49000000-0000-0000-0000-000000000137");
    insertItem(from, DAY_ONE, INVALID_VERSION, 1, "custom", "불완전 출발", "2026-09-01T04:00:00Z");
    insertItem(to, DAY_ONE, INVALID_VERSION, 2, "custom", "불완전 도착", "2026-09-01T05:30:00Z");
    jdbc.update(
        """
        insert into public.trip_legs (
          id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
          from_item_id, to_item_id, transport_mode, planned_departure_at, planned_arrival_at,
          walk_minutes, wait_minutes, ride_minutes, transfer_minutes, duration_minutes,
          buffer_minutes
        ) values (?, ?, ?, ?, 1, ?, ?, 'walk', ?, ?, 20, 0, 0, 0, null, 0)
        """,
        UUID.fromString("49000000-0000-0000-0000-000000000142"),
        TRIP,
        DAY_ONE,
        INVALID_VERSION,
        from,
        to,
        Timestamp.from(Instant.parse("2026-09-01T05:10:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T05:30:00Z")));
  }
}
