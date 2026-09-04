package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleMutationRecord;
import com.timingjeju.api.application.schedule.ScheduleMutationResult;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.domain.schedule.adapter.JdbcScheduleMutationStore;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcScheduleMutationStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("50000000-0000-0000-0000-000000000101");
  private static final UUID TRIP = UUID.fromString("50000000-0000-0000-0000-000000000102");
  private static final UUID DAY = UUID.fromString("50000000-0000-0000-0000-000000000103");
  private static final UUID ACTIVE = UUID.fromString("50000000-0000-0000-0000-000000000104");
  private static final UUID FIRST = UUID.fromString("50000000-0000-0000-0000-000000000105");
  private static final UUID SECOND = UUID.fromString("50000000-0000-0000-0000-000000000106");
  private static final UUID FIRST_PLACE = UUID.fromString("50000000-0000-0000-0000-000000000107");
  private static final UUID SECOND_PLACE = UUID.fromString("50000000-0000-0000-0000-000000000108");
  private static final UUID ADDED_PLACE = UUID.fromString("50000000-0000-0000-0000-000000000109");
  private static final UUID ROUTE_SNAPSHOT =
      UUID.fromString("50000000-0000-0000-0000-000000000110");
  private static final UUID ACCOMMODATION_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000111");
  private static final UUID ARRIVAL_ID = UUID.fromString("50000000-0000-0000-0000-000000000112");
  private static final UUID DEPARTURE_ID = UUID.fromString("50000000-0000-0000-0000-000000000113");
  private static final UUID DAY_TWO = UUID.fromString("50000000-0000-0000-0000-000000000114");
  private static final UUID DAY_TWO_ITEM = UUID.fromString("50000000-0000-0000-0000-000000000115");
  private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcScheduleMutationStore store;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void 활성_일정_fixture를_준비한다() {
    insertOwner();
    insertPlaces();
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, status, start_date, end_date,
           source_mode, data_version, revision)
        values (?, ?, 'issue50-trip', '일정 추가', 'draft', '2026-09-01', '2026-09-02',
                'fixture', 'issue50-v1', 1)
        """,
        TRIP,
        OWNER);
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, '2026-09-01')",
        DAY,
        TRIP);
    jdbc.update(
        "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 2, '2026-09-02')",
        DAY_TWO,
        TRIP);
    jdbc.update(
        "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status, source_type) values (?, ?, 1, 'draft', 'initial')",
        ACTIVE,
        TRIP);
    insertItem(FIRST, FIRST_PLACE, 1, "2026-09-01T00:00:00Z");
    insertItem(SECOND, SECOND_PLACE, 2, "2026-09-01T03:00:00Z");
    insertItem(DAY_TWO_ITEM, DAY_TWO, SECOND_PLACE, 1, "2026-09-02T00:00:00Z");
    jdbc.update(
        """
        insert into public.trip_legs
          (trip_plan_id, trip_day_id, schedule_version_id, sequence_no, from_item_id, to_item_id,
           transport_mode, planned_departure_at, planned_arrival_at, walk_minutes, wait_minutes,
           ride_minutes, transfer_minutes, duration_minutes, buffer_minutes, distance_meters,
           estimated_fare, facts)
        values (?, ?, ?, 1, ?, ?, 'walk', ?, ?, 10, 0, 0, 0, 10, 0, 500, 0,
                '{"derivation":"fixture"}'::jsonb)
        """,
        TRIP,
        DAY,
        ACTIVE,
        FIRST,
        SECOND,
        Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")),
        Timestamp.from(Instant.parse("2026-09-01T01:10:00Z")));
    jdbc.update(
        "update public.trip_schedule_versions set status='active', applied_at=now() where id=?",
        ACTIVE);
    jdbc.update(
        "update public.trip_plans set active_schedule_version_id=?, status='planned' where id=?",
        ACTIVE,
        TRIP);
    insertReferences();
    jdbc.execute("set constraints all immediate");
    jdbc.execute("set constraints all deferred");
  }

  @ParameterizedTest
  @EnumSource(Position.class)
  void first_middle_last_추가는_원본을_보존하고_완전한_새_version만_활성화한다(Position position) {
    String originalFingerprint = originalFingerprint();

    ScheduleMutationResult result = store.addItem(record(position, ACTIVE, 1));

    assertThat(result.previousScheduleVersionId()).isEqualTo(ACTIVE);
    assertThat(result.versionNo()).isEqualTo(2);
    assertThat(result.tripRevision()).isEqualTo(2);
    assertThat(result.changedItemIds()).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id from public.trip_plans where id=?",
                UUID.class,
                TRIP))
        .isEqualTo(result.activeScheduleVersionId());
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                ACTIVE))
        .isEqualTo("superseded");
    assertThat(originalFingerprint()).isEqualTo(originalFingerprint);
    assertThat(
            jdbc.queryForList(
                "select id from public.trip_items where schedule_version_id=? order by sequence_no",
                UUID.class,
                result.activeScheduleVersionId()))
        .hasSize(4)
        .doesNotContain(FIRST, SECOND, DAY_TWO_ITEM)
        .contains(result.changedItemIds().getFirst());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=?",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(2);
    jdbc.execute("set constraints all immediate");
  }

  @Test
  void stale_active_selector는_409이고_draft나_pointer_변경을_남기지_않는다() {
    UUID stale = UUID.fromString("50000000-0000-0000-0000-000000000199");
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(Position.MIDDLE, stale, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("ACTIVE_SCHEDULE_VERSION_CONFLICT");

    assertThat(aggregateFingerprint()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_schedule_versions where trip_plan_id=?",
                Integer.class,
                TRIP))
        .isEqualTo(1);
  }

  @Test
  void 이동시간이_다음_항목을_넘으면_422이고_새_version을_전부_rollback한다() {
    jdbc.update(
        "update public.tour_places set location=ST_SetSRID(ST_MakePoint(127.5, 33.5),4326)::geography where id=?",
        ADDED_PLACE);
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> addInNestedTransaction(record(Position.MIDDLE, ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");

    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  private ScheduleMutationResult addInNestedTransaction(ScheduleMutationRecord record) {
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    return nested.execute(ignored -> store.addItem(record));
  }

  @Test
  void stale_trip_ETag는_409이고_active_selector보다_먼저_원자거부한다() {
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(Position.MIDDLE, ACTIVE, 2)))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_VERSION_CONFLICT");

    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "cancelled", "failed"})
  void terminal_trip은_일정_항목_추가를_409로_원자거부한다(String status) {
    jdbc.update("update public.trip_plans set status=? where id=?", status, TRIP);
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(Position.MIDDLE, ACTIVE, 1)))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_TERMINAL_STATE_CONFLICT");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void 미만료_저장_route_snapshot은_도보_fallback보다_먼저_새_leg에_연결한다() {
    jdbc.update(
        """
        insert into public.mobility_route_snapshots
          (id, request_hash, origin_location, destination_location, transport_mode,
           distance_meters, duration_minutes, estimated_fare, source_provider, source_operation,
           route_summary, observed_at, expires_at, raw_payload)
        select ?, 'issue50-snapshot', origin.location, destination.location, 'walk',
               120, 5, 0, 'fixture', 'route',
               '{"walkMinutes":5,"waitMinutes":0,"rideMinutes":0,"transferMinutes":0}'::jsonb,
               ?, ?, '{}'::jsonb
        from public.tour_places origin, public.tour_places destination
        where origin.id=? and destination.id=?
        """,
        ROUTE_SNAPSHOT,
        Timestamp.from(NOW.minusSeconds(60)),
        Timestamp.from(NOW.plusSeconds(3600)),
        FIRST_PLACE,
        ADDED_PLACE);

    ScheduleMutationResult result = store.addItem(record(Position.MIDDLE, ACTIVE, 1));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=? and mobility_route_snapshot_id=?",
                Integer.class,
                result.activeScheduleVersionId(),
                ROUTE_SNAPSHOT))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(ItemKind.class)
  void 일곱_item_type은_저장된_정규화_장소를_결정론적으로_보존한다(ItemKind kind) {
    ScheduleMutationResult result = store.addItem(record(kind.command(), ACTIVE, 1));

    assertThat(
            jdbc.queryForMap(
                """
                select item_type, place_id, accommodation_id, transport_event_id, title
                from public.trip_items where schedule_version_id=? and id=?
                """,
                result.activeScheduleVersionId(),
                result.changedItemIds().getFirst()))
        .containsEntry("item_type", kind.itemType)
        .containsEntry("place_id", ADDED_PLACE);
  }

  @Test
  void 위치없는_숙소와_교통_event는_draft_전에_422이고_aggregate를_보존한다() {
    jdbc.update(
        "update public.trip_accommodations set place_id=null, custom_name='직접 숙소' where id=?",
        ACCOMMODATION_ID);
    jdbc.update(
        "update public.trip_transport_events set terminal_place_id=null, terminal_name='직접 터미널' where id=?",
        ARRIVAL_ID);
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(ItemKind.ACCOMMODATION.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");
    assertThatThrownBy(() -> store.addItem(record(ItemKind.ARRIVAL.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(LocationlessTitleKind.class)
  void 위치없는_title_item은_draft_전에_422이고_aggregate를_보존한다(LocationlessTitleKind kind) {
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(kind.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(UnusablePlace.class)
  void 사용할수없는_정규화_장소는_404이고_aggregate를_보존한다(UnusablePlace state) {
    jdbc.update(state.sql, ADDED_PLACE);
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.addItem(record(ItemKind.PLACE_VISIT.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("PLACE_NOT_FOUND");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  private ScheduleMutationRecord record(Position position, UUID expectedActive, long revision) {
    CreateScheduleItemCommand command =
        new CreateScheduleItemCommand(
            expectedActive,
            1,
            position.sequence,
            "place_visit",
            ADDED_PLACE,
            null,
            null,
            null,
            OffsetDateTime.parse(position.start),
            30,
            0,
            false,
            null);
    return record(command, expectedActive, revision);
  }

  private ScheduleMutationRecord record(
      CreateScheduleItemCommand command, UUID expectedActive, long revision) {
    return new ScheduleMutationRecord(
        OWNER, TRIP, new TripExpectedRevision(TRIP, revision), command, NOW);
  }

  private void insertOwner() {
    jdbc.update(
        "insert into auth.users (id, email) values (?, 'schedule-create@issue50.test')", OWNER);
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, 'schedule-create@issue50.test')",
        OWNER);
  }

  private void insertPlaces() {
    insertPlace(FIRST_PLACE, "첫 장소", 126.5000, 33.5000);
    insertPlace(SECOND_PLACE, "둘째 장소", 126.5020, 33.5000);
    insertPlace(ADDED_PLACE, "추가 장소", 126.5010, 33.5000);
  }

  private void insertReferences() {
    jdbc.update(
        """
        insert into public.trip_accommodations
          (id, trip_plan_id, place_id, check_in_date, check_out_date, sequence_no)
        values (?, ?, ?, '2026-09-01', '2026-09-02', 1)
        """,
        ACCOMMODATION_ID,
        TRIP,
        ADDED_PLACE);
    jdbc.update(
        """
        insert into public.trip_transport_events
          (id, trip_plan_id, event_type, transport_type, terminal_place_id, scheduled_at)
        values (?, ?, 'arrival', 'flight', ?, '2026-09-01T00:00:00Z'),
               (?, ?, 'departure', 'flight', ?, '2026-09-01T12:00:00Z')
        """,
        ARRIVAL_ID,
        TRIP,
        ADDED_PLACE,
        DEPARTURE_ID,
        TRIP,
        ADDED_PLACE);
  }

  private void insertPlace(UUID id, String name, double longitude, double latitude) {
    jdbc.update(
        """
        insert into public.tour_places
          (id, name, normalized_name, category, location, source_provider)
        values (?, ?, ?, 'ATTRACTION', ST_SetSRID(ST_MakePoint(?, ?),4326)::geography, 'fixture')
        """,
        id,
        name,
        name,
        longitude,
        latitude);
  }

  private void insertItem(UUID id, UUID placeId, int sequence, String start) {
    insertItem(id, DAY, placeId, sequence, start);
  }

  private void insertItem(UUID id, UUID dayId, UUID placeId, int sequence, String start) {
    Instant startsAt = Instant.parse(start);
    jdbc.update(
        """
        insert into public.trip_items
          (id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no, item_type, place_id,
           planned_start_at, planned_end_at, stay_minutes, source)
        values (?, ?, ?, ?, ?, 'place_visit', ?, ?, ?, 60, 'user_input')
        """,
        id,
        TRIP,
        dayId,
        ACTIVE,
        sequence,
        placeId,
        Timestamp.from(startsAt),
        Timestamp.from(startsAt.plusSeconds(3600)));
  }

  private String originalFingerprint() {
    return jdbc.queryForObject(
        """
        select md5(string_agg(concat_ws('|', id, sequence_no, place_id, planned_start_at,
                                         planned_end_at, stay_minutes), ',' order by sequence_no))
        from public.trip_items where schedule_version_id=?
        """,
        String.class,
        ACTIVE);
  }

  private String aggregateFingerprint() {
    return jdbc.queryForObject(
        """
        select md5(concat_ws('|', p.revision, p.active_schedule_version_id,
          (select count(*) from public.trip_schedule_versions v where v.trip_plan_id=p.id),
          (select count(*) from public.trip_items i where i.trip_plan_id=p.id),
          (select count(*) from public.trip_legs l where l.trip_plan_id=p.id)))
        from public.trip_plans p where p.id=?
        """,
        String.class,
        TRIP);
  }

  private enum Position {
    FIRST(1, "2026-09-01T07:30:00+09:00"),
    MIDDLE(2, "2026-09-01T10:30:00+09:00"),
    LAST(3, "2026-09-01T14:00:00+09:00");

    private final int sequence;
    private final String start;

    Position(int sequence, String start) {
      this.sequence = sequence;
      this.start = start;
    }
  }

  private enum ItemKind {
    PLACE_VISIT("place_visit", ADDED_PLACE, null, null, null),
    MEAL("meal", ADDED_PLACE, null, null, "점심"),
    ACCOMMODATION("accommodation", null, ACCOMMODATION_ID, null, null),
    ARRIVAL("arrival", null, null, ARRIVAL_ID, null),
    DEPARTURE("departure", null, null, DEPARTURE_ID, null),
    FREE_TIME("free_time", ADDED_PLACE, null, null, "자유 시간"),
    CUSTOM("custom", ADDED_PLACE, null, null, "사용자 일정");

    private final String itemType;
    private final UUID placeId;
    private final UUID accommodationId;
    private final UUID transportEventId;
    private final String title;

    ItemKind(
        String itemType, UUID placeId, UUID accommodationId, UUID transportEventId, String title) {
      this.itemType = itemType;
      this.placeId = placeId;
      this.accommodationId = accommodationId;
      this.transportEventId = transportEventId;
      this.title = title;
    }

    private CreateScheduleItemCommand command() {
      return new CreateScheduleItemCommand(
          ACTIVE,
          1,
          2,
          itemType,
          placeId,
          accommodationId,
          transportEventId,
          title,
          OffsetDateTime.parse("2026-09-01T10:30:00+09:00"),
          30,
          0,
          false,
          null);
    }
  }

  private enum UnusablePlace {
    STALE("update public.tour_places set stale=true where id=?"),
    EXPIRED("update public.tour_places set stale_at=now() where id=?"),
    TOMBSTONED("update public.tour_places set tombstoned_at=now() where id=?"),
    SOURCE_DELETED("update public.tour_places set source_deleted_at=now() where id=?");

    private final String sql;

    UnusablePlace(String sql) {
      this.sql = sql;
    }
  }

  private enum LocationlessTitleKind {
    MEAL("meal", "점심"),
    FREE_TIME("free_time", "자유 시간"),
    CUSTOM("custom", "사용자 일정");

    private final String itemType;
    private final String title;

    LocationlessTitleKind(String itemType, String title) {
      this.itemType = itemType;
      this.title = title;
    }

    private CreateScheduleItemCommand command() {
      return new CreateScheduleItemCommand(
          ACTIVE,
          1,
          2,
          itemType,
          null,
          null,
          null,
          title,
          OffsetDateTime.parse("2026-09-01T10:30:00+09:00"),
          30,
          0,
          false,
          null);
    }
  }
}
