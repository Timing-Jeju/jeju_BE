package com.timingjeju.api.domain.accommodation.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import com.timingjeju.api.application.accommodation.service.AccommodationService;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcAccommodationStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000101");
  private static final UUID OTHER = UUID.fromString("68000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000103");
  private static final UUID PLACE = UUID.fromString("68000000-0000-0000-0000-000000000104");
  private static final UUID VERSION = UUID.fromString("68000000-0000-0000-0000-000000000105");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private AccommodationService service;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.tour_places where id = ?", PLACE);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", OWNER, OTHER);
    owner(OWNER);
    owner(OTHER);
    place();
    trip();
  }

  @Test
  void POST는_place와_custom_identity를_저장하고_같은_key의_원응답을_replay한다() {
    var first =
        service.create(OWNER, TRIP, "accommodation-replay", expected(1), custom("숙소 A", 1, 2));
    var replay =
        service.create(OWNER, TRIP, "accommodation-replay", expected(1), custom("숙소 A", 1, 2));

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.snapshot().status()).isEqualTo(201);
    assertThat(replay.snapshot().etag()).isEqualTo(first.snapshot().etag());
    assertThat(replay.snapshot().body()).isEqualTo(first.snapshot().body());
    assertThat(count()).isOne();
    assertThat(revision()).isEqualTo(2L);

    assertCode(
        () ->
            service.create(OWNER, TRIP, "accommodation-replay", expected(2), custom("다른 숙소", 1, 2)),
        "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void 복수숙소는_날짜정렬_sequence_compaction과_내부연속성을_보장한다() {
    service.create(OWNER, TRIP, "middle", expected(1), custom("가운데", 2, 3));
    service.create(OWNER, TRIP, "after", expected(2), custom("뒤", 3, 5));
    service.create(OWNER, TRIP, "before", expected(3), place(1, 2));

    assertThat(
            jdbc.queryForList(
                "select sequence_no || ':' || check_in_date::text from public.trip_accommodations where trip_plan_id = ? order by sequence_no",
                String.class,
                TRIP))
        .containsExactly("1:2026-09-01", "2:2026-09-02", "3:2026-09-03");
    assertThat(revision()).isEqualTo(4L);
  }

  @Test
  void gap_overlap_trip범위밖은_422이고_여행과숙소를_바꾸지않는다() {
    service.create(OWNER, TRIP, "first", expected(1), custom("첫 숙소", 1, 2));
    String before = fingerprint();

    assertCode(
        () -> service.create(OWNER, TRIP, "gap", expected(2), custom("공백", 3, 4)),
        "ACCOMMODATION_DATE_GAP_OR_OVERLAP");
    assertCode(
        () -> service.create(OWNER, TRIP, "overlap", expected(2), custom("중복", 1, 3)),
        "ACCOMMODATION_DATE_GAP_OR_OVERLAP");
    assertCode(
        () -> service.create(OWNER, TRIP, "outside", expected(2), custom("범위 밖", 0, 2)),
        "ACCOMMODATION_DATE_GAP_OR_OVERLAP");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void PATCH_noop은_ETag_timestamp를_보존하고_identity_switch는_명시적_null만_허용한다() {
    var created = service.create(OWNER, TRIP, "patch-source", expected(1), custom("기존 숙소", 1, 3));
    UUID id = accommodationId();
    String before = fingerprint();
    PatchAccommodationCommand noOp =
        new PatchAccommodationCommand(
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.present("기존 숙소"),
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.omitted(),
            AccommodationPatchValue.omitted());

    var unchanged = service.patch(OWNER, TRIP, id, expected(2), noOp);
    assertThat(unchanged.snapshot().etag()).isEqualTo(created.snapshot().etag());
    assertThat(fingerprint()).isEqualTo(before);

    assertCode(
        () ->
            service.patch(
                OWNER,
                TRIP,
                id,
                expected(2),
                PatchAccommodationCommand.identity(
                    AccommodationPatchValue.present(PLACE), AccommodationPatchValue.omitted())),
        "INVALID_REQUEST");

    service.patch(
        OWNER,
        TRIP,
        id,
        expected(2),
        PatchAccommodationCommand.identity(
            AccommodationPatchValue.present(PLACE), AccommodationPatchValue.present(null)));
    assertThat(
            jdbc.queryForObject(
                "select custom_name is null and place_id = ? from public.trip_accommodations where id = ?",
                Boolean.class,
                PLACE,
                id))
        .isTrue();
    assertThat(revision()).isEqualTo(3L);
  }

  @Test
  void DELETE는_edge를_허용하고_middle_gap과_active를_거부한다() {
    service.create(OWNER, TRIP, "delete-a", expected(1), custom("A", 1, 2));
    service.create(OWNER, TRIP, "delete-b", expected(2), custom("B", 2, 3));
    service.create(OWNER, TRIP, "delete-c", expected(3), custom("C", 3, 5));
    var ids =
        jdbc.queryForList(
            "select id from public.trip_accommodations where trip_plan_id = ? order by sequence_no",
            UUID.class,
            TRIP);

    assertCode(
        () -> service.delete(OWNER, TRIP, ids.get(1), expected(4)),
        "ACCOMMODATION_DATE_GAP_OR_OVERLAP");
    service.delete(OWNER, TRIP, ids.getFirst(), expected(4));
    assertThat(
            jdbc.queryForList(
                "select sequence_no from public.trip_accommodations where trip_plan_id = ? order by sequence_no",
                Integer.class,
                TRIP))
        .containsExactly(1, 2);
  }

  @Test
  void active일정은_DELETE를_거부하고_실제_PATCH는_pointer_score_status를_원자무효화한다() {
    service.create(OWNER, TRIP, "active-source", expected(1), custom("활성 숙소", 1, 3));
    UUID id = accommodationId();
    installActiveSchedule();
    String beforeDelete = fingerprint();

    assertCode(
        () -> service.delete(OWNER, TRIP, id, expected(2)),
        "ACCOMMODATION_IN_USE_BY_ACTIVE_SCHEDULE");
    assertThat(fingerprint()).isEqualTo(beforeDelete);

    var patched =
        service.patch(
            OWNER,
            TRIP,
            id,
            expected(2),
            PatchAccommodationCommand.withCheckInTime(
                AccommodationPatchValue.present(LocalTime.parse("16:00"))));

    assertThat(new String(patched.snapshot().body(), StandardCharsets.UTF_8))
        .contains("\"scheduleEffect\":\"invalidated\"")
        .contains("\"regenerationRequired\":true")
        .contains("\"activeScheduleVersionId\":null")
        .contains("\"tripStatus\":\"draft\"");
    assertThat(
            jdbc.queryForMap(
                "select status,active_schedule_version_id,total_score,revision from public.trip_plans where id = ?",
                TRIP))
        .containsEntry("status", "draft")
        .containsEntry("active_schedule_version_id", null)
        .containsEntry("total_score", null)
        .containsEntry("revision", 3L);
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id = ?",
                String.class,
                VERSION))
        .isEqualTo("superseded");
  }

  @Test
  void active일정에서_POST도_version_pointer_score를_원자무효화한다() {
    installActiveSchedule();

    var created = service.create(OWNER, TRIP, "active-post", expected(1), custom("새 활성 숙소", 1, 3));

    assertThat(new String(created.snapshot().body(), StandardCharsets.UTF_8))
        .contains("\"scheduleEffect\":\"invalidated\"")
        .contains("\"regenerationRequired\":true");
    assertThat(
            jdbc.queryForMap(
                "select status,active_schedule_version_id,total_score,revision from public.trip_plans where id = ?",
                TRIP))
        .containsEntry("status", "draft")
        .containsEntry("active_schedule_version_id", null)
        .containsEntry("total_score", null)
        .containsEntry("revision", 2L);
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id = ?",
                String.class,
                VERSION))
        .isEqualTo("superseded");
  }

  @Test
  void 완료된_멱등_snapshot의_hash와_response는_DB_trigger가_불변으로_보호한다() {
    service.create(OWNER, TRIP, "immutable-snapshot", expected(1), custom("불변 숙소", 1, 3));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.accommodation_idempotency set request_hash = repeat('b', 64) where trip_plan_id = ? and idempotency_key = 'immutable-snapshot'",
                    TRIP))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void stale_cross_owner_missing_place는_정보를_숨기고_정확한_code를_반환한다() {
    assertCode(
        () -> service.create(OTHER, TRIP, "cross", expected(1), custom("숨김", 1, 2)),
        "TRIP_NOT_FOUND");
    assertCode(
        () -> service.create(OWNER, TRIP, "stale-etag", expected(99), custom("태그", 1, 2)),
        "TRIP_VERSION_CONFLICT");
    assertCode(
        () -> service.create(OWNER, TRIP, "missing-place", expected(1), missingPlace()),
        "PLACE_NOT_FOUND");
    assertThat(count()).isZero();
  }

  private long expected(long revision) {
    return revision;
  }

  private CreateAccommodationCommand custom(String name, int inDay, int outDay) {
    return command(null, name, inDay, outDay);
  }

  private CreateAccommodationCommand place(int inDay, int outDay) {
    return command(PLACE, null, inDay, outDay);
  }

  private CreateAccommodationCommand missingPlace() {
    return command(UUID.fromString("68000000-0000-0000-0000-000000000199"), null, 1, 2);
  }

  private CreateAccommodationCommand command(UUID placeId, String name, int inDay, int outDay) {
    return new CreateAccommodationCommand(
        placeId,
        name,
        LocalDate.parse("2026-09-01").plusDays(inDay - 1L),
        LocalDate.parse("2026-09-01").plusDays(outDay - 1L),
        LocalTime.parse("15:00"),
        LocalTime.parse("11:00"));
  }

  private void owner(UUID ownerId) {
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", ownerId, ownerId + "@issue68.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        ownerId,
        ownerId + "@issue68.test");
  }

  private void place() {
    jdbc.update(
        """
        insert into public.tour_places (
          id, content_id, name, normalized_name, category, region_code, region_label,
          location, source_provider, source_service
        ) values (?, 'issue68-place', '제주알호텔', '제주알호텔', 'ST', 'jeju-si', '제주시',
          ST_SetSRID(ST_MakePoint(126.5, 33.5), 4326)::geography, 'fixture', 'issue68')
        """,
        PLACE);
  }

  private void trip() {
    jdbc.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, title, status, start_date, end_date,
          timezone, user_pace, source_mode, data_version
        ) values (?, ?, 'issue68-trip-token', '복수 숙소 여행', 'draft',
          '2026-09-01', '2026-09-05', 'Asia/Seoul', 'normal', 'fixture', 'issue68-v1')
        """,
        TRIP,
        OWNER);
    for (int index = 0; index < 5; index++) {
      jdbc.update(
          "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, ?, ?)",
          UUID.nameUUIDFromBytes(("issue68-day-" + index).getBytes(StandardCharsets.UTF_8)),
          TRIP,
          index + 1,
          java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)));
    }
  }

  private void installActiveSchedule() {
    jdbc.update(
        "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status, source_type) values (?, ?, 1, 'draft', 'initial')",
        VERSION,
        TRIP);
    for (int index = 0; index < 5; index++) {
      UUID dayId =
          jdbc.queryForObject(
              "select id from public.trip_days where trip_plan_id = ? and day_no = ?",
              UUID.class,
              TRIP,
              index + 1);
      java.sql.Date date = java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index));
      jdbc.update(
          """
          insert into public.trip_items (
            id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
            item_type, title, planned_start_at, planned_end_at, stay_minutes, source, facts
          ) values (?, ?, ?, ?, 1, 'custom', '활성 일정',
                    (?::date + time '09:00') at time zone 'Asia/Seoul',
                    (?::date + time '10:00') at time zone 'Asia/Seoul',
                    60, 'user_input', '{"location":{"lat":33.5,"lng":126.5}}'::jsonb)
          """,
          UUID.nameUUIDFromBytes(("issue68-item-" + index).getBytes(StandardCharsets.UTF_8)),
          TRIP,
          dayId,
          VERSION,
          date,
          date);
    }
    jdbc.update(
        "update public.trip_plans set status = 'planned', active_schedule_version_id = ?, total_score = 88 where id = ?",
        VERSION,
        TRIP);
    jdbc.update(
        "update public.trip_schedule_versions set status = 'active', applied_at = now() where id = ? and trip_plan_id = ?",
        VERSION,
        TRIP);
  }

  private UUID accommodationId() {
    return jdbc.queryForObject(
        "select id from public.trip_accommodations where trip_plan_id = ? order by sequence_no limit 1",
        UUID.class,
        TRIP);
  }

  private int count() {
    return jdbc.queryForObject(
        "select count(*) from public.trip_accommodations where trip_plan_id = ?",
        Integer.class,
        TRIP);
  }

  private long revision() {
    return jdbc.queryForObject(
        "select revision from public.trip_plans where id = ?", Long.class, TRIP);
  }

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select p.revision::text || ':' || p.status || ':' || p.updated_at::text || ':' ||
               coalesce(string_agg(a.id::text || ':' || a.sequence_no::text || ':' ||
                 a.check_in_date::text || ':' || a.check_out_date::text || ':' || a.updated_at::text,
                 ',' order by a.sequence_no), '')
        from public.trip_plans p
        left join public.trip_accommodations a on a.trip_plan_id = p.id
        where p.id = ?
        group by p.revision, p.status, p.updated_at
        """,
        String.class,
        TRIP);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(AccommodationException.class)
        .extracting(failure -> ((AccommodationException) failure).code())
        .isEqualTo(code);
  }
}
