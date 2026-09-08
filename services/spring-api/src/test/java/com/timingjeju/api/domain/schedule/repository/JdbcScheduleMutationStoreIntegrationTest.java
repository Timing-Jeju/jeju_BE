package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.schedule.CreateScheduleItemCommand;
import com.timingjeju.api.application.schedule.DeleteScheduleItemCommand;
import com.timingjeju.api.application.schedule.MoveScheduleItemCommand;
import com.timingjeju.api.application.schedule.PatchScheduleItemCommand;
import com.timingjeju.api.application.schedule.ReorderScheduleCommand;
import com.timingjeju.api.application.schedule.ScheduleEditRecord;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
  private static final UUID ACCOMMODATION_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000111");
  private static final UUID ARRIVAL_ID = UUID.fromString("50000000-0000-0000-0000-000000000112");
  private static final UUID DEPARTURE_ID = UUID.fromString("50000000-0000-0000-0000-000000000113");
  private static final UUID DAY_TWO = UUID.fromString("50000000-0000-0000-0000-000000000114");
  private static final UUID DAY_TWO_ITEM = UUID.fromString("50000000-0000-0000-0000-000000000115");
  private static final UUID THIRD = UUID.fromString("50000000-0000-0000-0000-000000000116");
  private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcScheduleMutationStore store;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void 활성_일정_fixture를_준비한다() {
    new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> prepareFixture());
  }

  private void prepareFixture() {
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

  @ParameterizedTest
  @EnumSource(ItemKind.class)
  void planned_item_anchor는_소유_버전의_공개_참조에서만_좌표를_해석한다(ItemKind kind) {
    ScheduleMutationResult result = store.addItem(record(kind.command(), ACTIVE, 1));
    var anchor =
        jdbc.queryForMap(
            "select anchor_kind,anchor_id,source_place_id from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
            result.changedItemIds().getFirst(),
            result.activeScheduleVersionId(),
            TRIP);
    String expectedKind =
        kind == ItemKind.ACCOMMODATION
            ? "accommodation"
            : kind == ItemKind.ARRIVAL || kind == ItemKind.DEPARTURE ? "transport_event" : "place";
    UUID expectedId =
        kind == ItemKind.ACCOMMODATION
            ? ACCOMMODATION_ID
            : kind == ItemKind.ARRIVAL
                ? ARRIVAL_ID
                : kind == ItemKind.DEPARTURE ? DEPARTURE_ID : ADDED_PLACE;
    assertThat(anchor)
        .containsEntry("anchor_kind", expectedKind)
        .containsEntry("anchor_id", expectedId)
        .containsEntry("source_place_id", ADDED_PLACE);
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                result.changedItemIds().getFirst(),
                ACTIVE,
                TRIP))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                result.changedItemIds().getFirst(),
                result.activeScheduleVersionId(),
                UUID.randomUUID()))
        .isEmpty();
  }

  @Test
  void planned_stop_anchor는_공개_정류장만_해석하고_임의_kind는_거부한다() {
    UUID stop = UUID.randomUUID();
    jdbc.update(
        "insert into public.bus_stops(id,node_id,node_name,location) values (?,'issue225-stop','공개 정류장',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography)",
        stop);
    assertThat(
            jdbc.queryForMap(
                "select anchor_kind,anchor_id,source_stop_id from timing_jeju_planner_private.resolve_planned_anchor('stop',?,?)",
                stop,
                TRIP))
        .containsEntry("anchor_kind", "stop")
        .containsEntry("anchor_id", stop)
        .containsEntry("source_stop_id", stop);
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_anchor('current_location',?,?)",
                stop,
                TRIP))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_anchor('accommodation',?,?)",
                ACCOMMODATION_ID,
                UUID.randomUUID()))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_anchor('transport_event',?,?)",
                ARRIVAL_ID,
                UUID.randomUUID()))
        .isEmpty();
  }

  @Test
  void planned_anchor_helper는_service_role만_실행하고_기존_owner_schema_ACL은_유지한다() {
    for (String role : List.of("anon", "authenticated", "service_role")) {
      boolean allowed = role.equals("service_role");
      assertThat(
              jdbc.queryForObject(
                  "select has_schema_privilege(?, 'timing_jeju_planner_private', 'USAGE')",
                  Boolean.class,
                  role))
          .isEqualTo(allowed);
      for (String function :
          List.of(
              "timing_jeju_planner_private.resolve_planned_anchor(text,uuid,uuid)",
              "timing_jeju_planner_private.resolve_planned_item_anchor(uuid,uuid,uuid)")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_function_privilege(?, ?, 'EXECUTE')",
                    Boolean.class,
                    role,
                    function))
            .isEqualTo(allowed);
      }
    }
    assertThat(
            jdbc.queryForObject(
                "select has_schema_privilege('service_role', 'timing_jeju_private', 'USAGE')",
                Boolean.class))
        .isFalse();
    jdbc.execute("set local role service_role");
    try {
      assertThat(
              jdbc.queryForObject(
                  "select source_place_id from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                  UUID.class,
                  FIRST,
                  ACTIVE,
                  TRIP))
          .isEqualTo(FIRST_PLACE);
    } finally {
      jdbc.execute("reset role");
    }
  }

  @Test
  void planned_accommodation은_중복_placeId_없이_해석하고_다른_placeId는_거부한다() {
    mutateLegacyItem(
        "update public.trip_items set item_type='accommodation', accommodation_id=?, place_id=null, title='공개 숙소' where id=?",
        ACCOMMODATION_ID,
        FIRST);
    assertThat(
            jdbc.queryForObject(
                "select source_place_id from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                UUID.class,
                FIRST,
                ACTIVE,
                TRIP))
        .isEqualTo(ADDED_PLACE);
    mutateLegacyItem("update public.trip_items set place_id=? where id=?", FIRST_PLACE, FIRST);
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                FIRST,
                ACTIVE,
                TRIP))
        .isEmpty();
  }

  @Test
  void planned_anchor는_삭제된_공개_장소를_계산에_재사용하지_않는다() {
    jdbc.update("update public.tour_places set tombstoned_at=now() where id=?", FIRST_PLACE);
    assertThat(
            jdbc.queryForList(
                "select * from timing_jeju_planner_private.resolve_planned_item_anchor(?,?,?)",
                FIRST,
                ACTIVE,
                TRIP))
        .isEmpty();
  }

  @Test
  void POST는_completed_arrived_survivor_progress를_새_ID에_보존하고_완료_guard를_유지한다() {
    Instant started = Instant.parse("2026-09-01T00:05:00Z");
    Instant arrived = Instant.parse("2026-09-01T00:10:00Z");
    Instant completed = Instant.parse("2026-09-01T00:50:00Z");
    jdbc.update(
        """
        insert into public.trip_item_progress
          (trip_plan_id, schedule_version_id, trip_item_id, status,
           actual_started_at, actual_arrived_at, actual_completed_at)
        values (?, ?, ?, 'completed', ?, ?, ?),
               (?, ?, ?, 'arrived', ?, ?, null)
        """,
        TRIP,
        ACTIVE,
        FIRST,
        Timestamp.from(started),
        Timestamp.from(arrived),
        Timestamp.from(completed),
        TRIP,
        ACTIVE,
        SECOND,
        Timestamp.from(started.plusSeconds(3 * 3600)),
        Timestamp.from(arrived.plusSeconds(3 * 3600)));

    ScheduleMutationResult result = store.addItem(record(Position.LAST, ACTIVE, 1));
    UUID completedCopy = copiedItem(result.activeScheduleVersionId(), FIRST_PLACE, DAY);
    UUID arrivedCopy = copiedItem(result.activeScheduleVersionId(), SECOND_PLACE, DAY);

    assertThat(progress(completedCopy))
        .containsEntry("status", "completed")
        .containsEntry("actual_started_at", Timestamp.from(started))
        .containsEntry("actual_arrived_at", Timestamp.from(arrived))
        .containsEntry("actual_completed_at", Timestamp.from(completed));
    assertThat(progress(arrivedCopy))
        .containsEntry("status", "arrived")
        .containsEntry("actual_started_at", Timestamp.from(started.plusSeconds(3 * 3600)))
        .containsEntry("actual_arrived_at", Timestamp.from(arrived.plusSeconds(3 * 3600)))
        .containsEntry("actual_completed_at", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_item_progress where trip_item_id=?",
                Integer.class,
                result.changedItemIds().getFirst()))
        .isZero();

    var patch =
        new PatchScheduleItemCommand(
            result.activeScheduleVersionId(),
            Set.of("memo"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "완료 항목");
    var order =
        new ReorderScheduleCommand(
            result.activeScheduleVersionId(),
            List.of(
                new ReorderScheduleCommand.DayOrder(
                    1, List.of(completedCopy, arrivedCopy, result.changedItemIds().getFirst())),
                new ReorderScheduleCommand.DayOrder(
                    2,
                    List.of(copiedItem(result.activeScheduleVersionId(), SECOND_PLACE, DAY_TWO)))));

    assertCompleted(() -> store.patchItem(edit(completedCopy, patch, 2)));
    assertCompleted(
        () ->
            store.deleteItem(
                edit(
                    completedCopy,
                    new DeleteScheduleItemCommand(result.activeScheduleVersionId()),
                    2)));
    assertCompleted(() -> store.reorder(edit(null, order, 2)));
    assertCompleted(
        () ->
            store.moveItem(
                edit(
                    completedCopy,
                    new MoveScheduleItemCommand(
                        result.activeScheduleVersionId(),
                        2,
                        2,
                        OffsetDateTime.parse("2026-09-02T10:30:00+09:00")),
                    2)));
  }

  @Test
  void POST의_progress_copy실패는_새_version과_pointer를_전부_rollback한다() {
    jdbc.update(
        "insert into public.trip_item_progress (trip_plan_id,schedule_version_id,trip_item_id,status) values (?,?,?,'arrived')",
        TRIP,
        ACTIVE,
        FIRST);
    jdbc.execute(
        """
        create function pg_temp.reject_progress_copy() returns trigger language plpgsql as $$
        begin
          if new.schedule_version_id <> '50000000-0000-0000-0000-000000000104'::uuid then
            raise exception using errcode='23514', message='forced progress copy failure';
          end if;
          return new;
        end
        $$
        """);
    jdbc.execute(
        "create trigger reject_progress_copy before insert on public.trip_item_progress "
            + "for each row execute function pg_temp.reject_progress_copy()");
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> addInNestedTransaction(record(Position.LAST, ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");

    assertThat(aggregateFingerprint()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_item_progress where trip_plan_id=?",
                Integer.class,
                TRIP))
        .isEqualTo(1);
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

  @Test
  void legacy_invalid_source_item_copy는_필수참조_validator에서_aggregate_전체를_rollback한다() {
    jdbc.execute("drop trigger trg_trip_items_required_references on public.trip_items");
    jdbc.execute(
        "alter table public.trip_items disable trigger trg_trip_items_require_draft_version");
    jdbc.execute(
        "alter table public.trip_items drop constraint chk_trip_items_required_references");
    jdbc.update("update public.trip_items set item_type='accommodation' where id=?", FIRST);
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> addInNestedTransaction(record(Position.MIDDLE, ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");

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
  void route_snapshot은_좌표만_같은_출처없는_요청을_거부한다() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
            insert into public.mobility_route_snapshots
              (request_hash,origin_location,destination_location,transport_mode,
               duration_minutes,source_provider,expires_at)
            select ?,origin.location,destination.location,'walk',
                   10,'fixture',now()+interval '1 hour'
            from public.tour_places origin, public.tour_places destination
            where origin.id=? and destination.id=?
            """,
                    "unproven-route-marker",
                    FIRST_PLACE,
                    SECOND_PLACE))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageNotContaining("unproven-route-marker");
  }

  @Test
  void route_snapshot은_계획_버전과_양끝_공개_anchor로_좌표와_hash를_고정한다() {
    UUID routeId = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.mobility_route_snapshots
          (id,trip_plan_id,schedule_version_id,origin_item_id,destination_item_id,
           origin_anchor_kind,origin_anchor_id,destination_anchor_kind,destination_anchor_id,
           transport_mode,duration_minutes,source_provider,source_operation,expires_at)
        values (?,?,?,?,?,'place',?,'place',?,'walk',10,'fixture','route',now()+interval '1 hour')
        """,
        routeId,
        TRIP,
        ACTIVE,
        FIRST,
        SECOND,
        FIRST_PLACE,
        SECOND_PLACE);
    var route =
        jdbc.queryForMap(
            """
        select snapshot.anchor_contract_version,snapshot.request_hash,
               snapshot.origin_source_place_id,snapshot.destination_source_place_id,
               ST_Equals(snapshot.origin_location::geometry,origin.location::geometry) as origin_matches,
               ST_Equals(snapshot.destination_location::geometry,destination.location::geometry) as destination_matches
        from public.mobility_route_snapshots snapshot
        join public.tour_places origin on origin.id=?
        join public.tour_places destination on destination.id=? where snapshot.id=?
        """,
            FIRST_PLACE,
            SECOND_PLACE,
            routeId);
    assertThat(route)
        .containsEntry("anchor_contract_version", "planned-anchor.v1")
        .containsEntry("origin_source_place_id", FIRST_PLACE)
        .containsEntry("destination_source_place_id", SECOND_PLACE)
        .containsEntry("origin_matches", true)
        .containsEntry("destination_matches", true);
    assertThat((String) route.get("request_hash")).matches("^[0-9a-f]{64}$");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "owner",
        "version",
        "item",
        "missing_origin",
        "wrong_kind",
        "wrong_anchor",
        "coordinates",
        "hash",
        "contract",
        "self_loop"
      })
  void route_snapshot은_잘못된_계보와_좌표_hash를_거부한다(String invalidCase) {
    var overrides = new java.util.LinkedHashMap<String, Object>();
    switch (invalidCase) {
      case "owner" -> overrides.put("owner_user_id", UUID.randomUUID());
      case "version" -> overrides.put("schedule_version_id", UUID.randomUUID());
      case "item" -> overrides.put("origin_item_id", UUID.randomUUID());
      case "missing_origin" -> overrides.put("origin_anchor_id", null);
      case "wrong_kind" -> overrides.put("origin_anchor_kind", "current_location");
      case "wrong_anchor" -> overrides.put("origin_anchor_id", ADDED_PLACE);
      case "coordinates" -> overrides.put("origin_location", "SRID=4326;POINT(127 34)");
      case "hash" -> overrides.put("request_hash", "0".repeat(64));
      case "contract" -> overrides.put("anchor_contract_version", "coordinates-only.v0");
      case "self_loop" -> {
        overrides.put("destination_item_id", FIRST);
        overrides.put("destination_anchor_id", FIRST_PLACE);
      }
      default -> throw new IllegalArgumentException(invalidCase);
    }
    assertThatThrownBy(() -> insertPlannedSnapshot(overrides))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void route_snapshot의_공개_출처와_결과는_삽입_후_변경할_수_없다() {
    UUID snapshot = insertPlannedSnapshot(java.util.Map.of());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.mobility_route_snapshots set duration_minutes=11 where id=?",
                    snapshot))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route snapshots are immutable");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"raw_payload", "route_summary"})
  void route_snapshot은_원문과_임의_JSON_위치를_저장하지_않는다(String column) {
    assertThatThrownBy(
            () ->
                insertPlannedSnapshot(
                    java.util.Map.of(column, "{\"currentLocation\":{\"latitude\":34}}")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route payload is not approved");
  }

  @Test
  void route_snapshot은_승인되지_않은_provider_결과_저장을_차단한다() {
    assertThatThrownBy(() -> insertPlannedSnapshot(java.util.Map.of("source_provider", "tmap")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route storage approval is required");
  }

  @Test
  void route_snapshot의_원천_payload_보존기간_정리는_계획_계보와_hash를_보존한다() {
    UUID importRun = UUID.randomUUID();
    UUID rawSnapshot = UUID.randomUUID();
    jdbc.update(
        """
        insert into public.data_import_runs
          (id,source_kind,source_name,source_provider,source_service,source_operation,
           data_version,status,finished_at)
        values (?,'fixture','route retention QA','fixture','mobility','route',
                'issue225-v1','succeeded',now())
        """,
        importRun);
    jdbc.update(
        """
        insert into public.external_api_snapshots
          (id,import_run_id,source_provider,source_service,source_operation,
           request_hash,parser_version,payload_hash,raw_payload,parse_status,parsed_at)
        values (?,?,'fixture','mobility','route',repeat('a',64),'fixture-v1',repeat('b',64),
                '{}'::jsonb,'parsed',now())
        """,
        rawSnapshot,
        importRun);
    UUID snapshot =
        insertPlannedSnapshot(
            java.util.Map.of("source_snapshot_id", rawSnapshot, "import_run_id", importRun));
    String hash =
        jdbc.queryForObject(
            "select request_hash from public.mobility_route_snapshots where id=?",
            String.class,
            snapshot);
    jdbc.update("delete from public.external_api_snapshots where id=?", rawSnapshot);
    assertThat(
            jdbc.queryForMap(
                "select source_snapshot_id,import_run_id,request_hash,schedule_version_id "
                    + "from public.mobility_route_snapshots where id=?",
                snapshot))
        .containsEntry("source_snapshot_id", null)
        .containsEntry("import_run_id", importRun)
        .containsEntry("request_hash", hash)
        .containsEntry("schedule_version_id", ACTIVE);
  }

  @Test
  void route_snapshot을_복사한_수동_일정은_새_버전의_별도_계보를_갖는다() {
    UUID snapshot =
        insertPlannedSnapshot(java.util.Map.of("distance_meters", 500, "estimated_fare", 0));
    mutateLegacyItem(
        "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
        snapshot,
        ACTIVE);
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "검증");
    ScheduleMutationResult result = store.patchItem(edit(FIRST, patch));
    var copied =
        jdbc.queryForMap(
            """
        select snapshot.id,snapshot.schedule_version_id,snapshot.origin_item_id,snapshot.destination_item_id,
               leg.from_item_id,leg.to_item_id
        from public.trip_legs leg join public.mobility_route_snapshots snapshot
          on snapshot.id=leg.mobility_route_snapshot_id where leg.schedule_version_id=?
        """,
            result.activeScheduleVersionId());
    assertThat(copied.get("id")).isNotEqualTo(snapshot);
    assertThat(copied)
        .containsEntry("schedule_version_id", result.activeScheduleVersionId())
        .containsEntry("origin_item_id", copied.get("from_item_id"))
        .containsEntry("destination_item_id", copied.get("to_item_id"));
  }

  @Test
  void route_snapshot과_다른_버전의_leg_연결은_명확한_계보_오류로_거부한다() {
    UUID snapshot = insertPlannedSnapshot(java.util.Map.of());
    UUID draft = UUID.randomUUID();
    jdbc.update(
        "insert into public.trip_schedule_versions "
            + "(id,trip_plan_id,version_no,status,source_type) values (?,?,2,'draft','user_edit')",
        draft,
        TRIP);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
            insert into public.trip_legs
              (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,
               transport_mode,mobility_route_snapshot_id,planned_departure_at,planned_arrival_at,
               walk_minutes,wait_minutes,ride_minutes,transfer_minutes,duration_minutes,buffer_minutes)
            values (?,?,?,1,?,?,'walk',?,'2026-09-01T01:00:00Z','2026-09-01T01:10:00Z',10,0,0,0,10,0)
            """,
                    TRIP,
                    DAY,
                    draft,
                    FIRST,
                    SECOND,
                    snapshot))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route leg lineage does not match");
  }

  @Test
  void route_snapshot은_항목의_공개_anchor가_바뀌면_seal을_거부한다() {
    UUID snapshot =
        insertPlannedSnapshot(java.util.Map.of("distance_meters", 500, "estimated_fare", 0));
    mutateLegacyItem(
        "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
        snapshot,
        ACTIVE);
    mutateLegacyItem(
        "update public.trip_schedule_versions set status='draft',applied_at=null where id=?",
        ACTIVE);
    jdbc.update("update public.trip_items set place_id=? where id=?", SECOND_PLACE, FIRST);
    assertThatThrownBy(
            () ->
                jdbc.queryForObject(
                    "select public.assert_schedule_version_sealable(?,?)",
                    Object.class,
                    ACTIVE,
                    TRIP))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route anchor lineage does not match");
  }

  @Test
  void route_snapshot은_연결된_정류장_변경을_거부한다() {
    prepareStopSnapshot();
    mutateLegacyItem(
        "update public.trip_schedule_versions set status='draft',applied_at=null where id=?",
        ACTIVE);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_legs set origin_stop_id=null where schedule_version_id=?",
                    ACTIVE))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("planned route leg lineage does not match");
  }

  @Test
  void route_snapshot의_양끝_정류장은_메모_수정에서도_복사된다() {
    UUID stop = prepareStopSnapshot();
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "정류장 보존");
    ScheduleMutationResult result = store.patchItem(edit(FIRST, patch));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=? and origin_stop_id=? "
                    + "and destination_stop_id=? and mobility_route_snapshot_id is not null",
                Integer.class,
                result.activeScheduleVersionId(),
                stop,
                stop))
        .isEqualTo(1);
  }

  @Test
  void 만료된_route_snapshot_복사_실패는_새_leg와_버전을_모두_롤백한다() {
    UUID snapshot =
        insertPlannedSnapshot(
            java.util.Map.of(
                "distance_meters",
                500,
                "estimated_fare",
                0,
                "observed_at",
                Timestamp.from(NOW.minusSeconds(60)),
                "expires_at",
                Timestamp.from(NOW)));
    mutateLegacyItem(
        "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
        snapshot,
        ACTIVE);
    String before = aggregateFingerprint();
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "만료 검증");
    assertThatThrownBy(() -> patchInNestedTransaction(edit(FIRST, patch)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");
    assertThat(aggregateFingerprint()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.mobility_route_snapshots", Integer.class))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"expiry_equal", "before_observed", "departure_changed"})
  void route_snapshot_복사는_유효기간_등호와_관측전_시각과_변경된_출발을_거부한다(String boundary) {
    UUID snapshot =
        insertPlannedSnapshot(
            java.util.Map.of(
                "observed_at",
                Timestamp.from(NOW.minusSeconds(60)),
                "expires_at",
                Timestamp.from(NOW.plusSeconds(60))));
    Instant targetTime =
        boundary.equals("expiry_equal")
            ? NOW.plusSeconds(60)
            : boundary.equals("before_observed") ? NOW.minusSeconds(61) : NOW;
    Instant departure =
        Instant.parse("2026-09-01T01:00:00Z")
            .plusSeconds(boundary.equals("departure_changed") ? 60 : 0);
    assertThat(
            jdbc.queryForObject(
                "select timing_jeju_planner_private.clone_planned_route_snapshot(?,?,?,?,?,?,?)",
                UUID.class,
                snapshot,
                TRIP,
                ACTIVE,
                FIRST,
                SECOND,
                Timestamp.from(departure),
                Timestamp.from(targetTime)))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.mobility_route_snapshots", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 여행_삭제는_계획_route를_제거하고_공개_anchor와_사용자를_보존한다() {
    UUID snapshot =
        insertPlannedSnapshot(java.util.Map.of("distance_meters", 500, "estimated_fare", 0));
    mutateLegacyItem(
        "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
        snapshot,
        ACTIVE);
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.mobility_route_snapshots where id=?",
                Integer.class,
                snapshot))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tour_places where id in (?,?,?)",
                Integer.class,
                FIRST_PLACE,
                SECOND_PLACE,
                ADDED_PLACE))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.user_profiles where id=?", Integer.class, OWNER))
        .isEqualTo(1);
  }

  private UUID prepareStopSnapshot() {
    UUID stop = UUID.randomUUID();
    jdbc.update(
        "insert into public.bus_stops(id,node_id,node_name,location) "
            + "values (?,'planned-stop','공개 정류장',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography)",
        stop);
    mutateLegacyItem(
        "update public.trip_legs set origin_stop_id=?,destination_stop_id=? where schedule_version_id=?",
        stop,
        stop,
        ACTIVE);
    UUID snapshot =
        insertPlannedSnapshot(
            java.util.Map.of(
                "origin_anchor_kind",
                "stop",
                "origin_anchor_id",
                stop,
                "destination_anchor_kind",
                "stop",
                "destination_anchor_id",
                stop,
                "distance_meters",
                500,
                "estimated_fare",
                0));
    mutateLegacyItem(
        "update public.trip_legs set mobility_route_snapshot_id=? where schedule_version_id=?",
        snapshot,
        ACTIVE);
    return stop;
  }

  private UUID insertPlannedSnapshot(java.util.Map<String, Object> overrides) {
    var fields = new java.util.LinkedHashMap<String, Object>();
    UUID snapshot = UUID.randomUUID();
    fields.put("id", snapshot);
    fields.put("trip_plan_id", TRIP);
    fields.put("schedule_version_id", ACTIVE);
    fields.put("origin_item_id", FIRST);
    fields.put("destination_item_id", SECOND);
    fields.put("origin_anchor_kind", "place");
    fields.put("origin_anchor_id", FIRST_PLACE);
    fields.put("destination_anchor_kind", "place");
    fields.put("destination_anchor_id", SECOND_PLACE);
    fields.put("transport_mode", "walk");
    fields.put("duration_minutes", 10);
    fields.put("source_provider", "fixture");
    fields.put("source_operation", "route");
    fields.put("expires_at", Timestamp.from(Instant.now().plusSeconds(3600)));
    fields.putAll(overrides);
    String values =
        fields.keySet().stream()
            .map(
                key ->
                    key.endsWith("_location")
                        ? "ST_GeogFromText(?)"
                        : key.equals("raw_payload") || key.equals("route_summary")
                            ? "?::jsonb"
                            : "?")
            .collect(java.util.stream.Collectors.joining(","));
    jdbc.update(
        "insert into public.mobility_route_snapshots ("
            + String.join(",", fields.keySet())
            + ") values ("
            + values
            + ")",
        fields.values().toArray());
    return snapshot;
  }

  @Test
  void 좌표가_같아도_다른_버전의_route_snapshot을_전역_cache로_선택하지_않는다() {
    UUID candidateVersion = UUID.randomUUID();
    UUID candidateFrom = UUID.randomUUID();
    UUID candidateTo = UUID.randomUUID();
    jdbc.update(
        "insert into public.trip_schedule_versions "
            + "(id,trip_plan_id,version_no,status,source_type) values (?,?,2,'draft','user_edit')",
        candidateVersion,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_items
          (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
           planned_start_at,planned_end_at,stay_minutes,source)
        select case when id=? then ?::uuid else ?::uuid end,trip_plan_id,trip_day_id,?,
               sequence_no,item_type,case when id=? then place_id else ?::uuid end,
               planned_start_at,planned_end_at,stay_minutes,source
        from public.trip_items where id in (?,?)
        """,
        FIRST,
        candidateFrom,
        candidateTo,
        candidateVersion,
        FIRST,
        ADDED_PLACE,
        FIRST,
        SECOND);
    UUID snapshot =
        insertPlannedSnapshot(
            java.util.Map.of(
                "schedule_version_id",
                candidateVersion,
                "origin_item_id",
                candidateFrom,
                "destination_item_id",
                candidateTo,
                "destination_anchor_id",
                ADDED_PLACE,
                "distance_meters",
                120,
                "duration_minutes",
                5,
                "estimated_fare",
                0,
                "route_summary",
                "{\"walkMinutes\":5,\"waitMinutes\":0,\"rideMinutes\":0,\"transferMinutes\":0}"));
    ScheduleMutationResult result = store.addItem(record(Position.MIDDLE, ACTIVE, 1));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=? and mobility_route_snapshot_id=?",
                Integer.class,
                result.activeScheduleVersionId(),
                snapshot))
        .isZero();
    assertThat(result.versionNo()).isEqualTo(3);
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
  void 위치없는_숙소와_교통_event가_인접_leg를_요구하면_422이고_aggregate를_보존한다() {
    jdbc.update(
        "update public.trip_accommodations set place_id=null, custom_name='직접 숙소' where id=?",
        ACCOMMODATION_ID);
    jdbc.update(
        "update public.trip_transport_events set terminal_place_id=null, terminal_name='직접 터미널' where id=?",
        ARRIVAL_ID);
    String before = aggregateFingerprint();

    assertThatThrownBy(
            () -> addInNestedTransaction(record(ItemKind.ACCOMMODATION.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");
    assertThatThrownBy(() -> addInNestedTransaction(record(ItemKind.ARRIVAL.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(LocationlessTitleKind.class)
  void 위치없는_title_item이_인접_leg를_새로_요구하면_422이고_aggregate를_보존한다(LocationlessTitleKind kind) {
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> addInNestedTransaction(record(kind.command(), ACTIVE, 1)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void sole_locationless_title_item의_memo_only_PATCH는_새_version으로_copy된다() {
    mutateLegacyItem(
        "update public.trip_items set item_type='custom', place_id=null, title='메모 일정' where id=?",
        DAY_TWO_ITEM);
    var command =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "변경 메모");

    ScheduleMutationResult result = store.patchItem(edit(DAY_TWO_ITEM, command));

    assertThat(
            jdbc.queryForMap(
                "select item_type,place_id,title,memo from public.trip_items where schedule_version_id=? and trip_day_id=?",
                result.activeScheduleVersionId(),
                DAY_TWO))
        .containsEntry("item_type", "custom")
        .containsEntry("place_id", null)
        .containsEntry("title", "메모 일정")
        .containsEntry("memo", "변경 메모");
  }

  @Test
  void 기존_adjacent_leg가_있는_locationless_title_item의_memo_only_PATCH는_leg를_재사용한다() {
    mutateLegacyItem(
        "update public.trip_items set item_type='free_time', place_id=null, title='자유 시간' where id=?",
        FIRST);
    var command =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "천천히 이동");

    ScheduleMutationResult result = store.patchItem(edit(FIRST, command));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=?",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select memo from public.trip_items where schedule_version_id=? and trip_day_id=? and sequence_no=1",
                String.class,
                result.activeScheduleVersionId(),
                DAY))
        .isEqualTo("천천히 이동");
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

  @Test
  void PATCH는_memo_explicit_null과_survivor_progress를_새_namespace에_복제한다() {
    jdbc.update(
        "insert into public.trip_item_progress (trip_plan_id,schedule_version_id,trip_item_id,status) values (?,?,?,'planned')",
        TRIP,
        ACTIVE,
        FIRST);
    var command =
        new PatchScheduleItemCommand(
            ACTIVE,
            Set.of("stayMinutes", "memo"),
            null,
            null,
            null,
            null,
            null,
            45,
            null,
            null,
            null);

    ScheduleMutationResult result = store.patchItem(edit(FIRST, command));

    UUID copiedPatchedItemId =
        jdbc.queryForObject(
            "select id from public.trip_items where schedule_version_id=? and trip_day_id=? and sequence_no=1",
            UUID.class,
            result.activeScheduleVersionId(),
            DAY);
    assertThat(result.changedItemIds()).containsExactly(copiedPatchedItemId).doesNotContain(FIRST);

    assertThat(
            jdbc.queryForMap(
                "select id,stay_minutes,memo from public.trip_items where schedule_version_id=? and sequence_no=1 and trip_day_id=?",
                result.activeScheduleVersionId(),
                DAY))
        .containsEntry("stay_minutes", 45)
        .containsEntry("memo", null)
        .doesNotContainEntry("id", FIRST);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_item_progress where schedule_version_id=? and status='planned'",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select stay_minutes from public.trip_items where id=?", Integer.class, FIRST))
        .isEqualTo(60);
    assertThat(
            jdbc.queryForObject(
                "select facts->>'derivation' from public.trip_legs where schedule_version_id=?",
                String.class,
                result.activeScheduleVersionId()))
        .isEqualTo("conservative_walk_v1");
  }

  @Test
  void PATCH로_장소가_바뀌면_이전_leg_근거를_재사용하지_않는다() {
    var command =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("placeId"), ADDED_PLACE, null, null, null, null, null, null, null, null);

    ScheduleMutationResult result = store.patchItem(edit(FIRST, command));

    assertThat(
            jdbc.queryForMap(
                "select transport_mode,facts->>'derivation' as derivation from public.trip_legs where schedule_version_id=?",
                result.activeScheduleVersionId()))
        .containsEntry("transport_mode", "walk")
        .containsEntry("derivation", "conservative_walk_v1");
  }

  @Test
  void PATCH로_기존_leg가_시간창을_넘으면_422이고_새_version을_전부_rollback한다() {
    String before = aggregateFingerprint();
    var command =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("stayMinutes"), null, null, null, null, null, 179, null, null, null);

    assertThatThrownBy(() -> patchInNestedTransaction(edit(FIRST, command)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_LEG_INCOMPLETE");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void DELETE는_middle_survivor를_compact하고_progress를_새_item_ID에_보존한다() {
    jdbc.update(
        "insert into public.trip_item_progress (trip_plan_id,schedule_version_id,trip_item_id,status) values (?,?,?,'arrived')",
        TRIP,
        ACTIVE,
        SECOND);

    ScheduleMutationResult result =
        store.deleteItem(edit(FIRST, new DeleteScheduleItemCommand(ACTIVE)));

    assertThat(result.changedItemIds()).isEmpty();

    assertThat(
            jdbc.queryForList(
                "select sequence_no from public.trip_items where schedule_version_id=? and trip_day_id=? order by sequence_no",
                Integer.class,
                result.activeScheduleVersionId(),
                DAY))
        .containsExactly(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_item_progress where schedule_version_id=? and status='arrived'",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(1);
    assertThat(originalFingerprint()).isNotNull();
  }

  @ParameterizedTest
  @EnumSource(DeletePosition.class)
  void DELETE_first_middle_last는_연속_sequence와_정확한_N_minus_1_leg를_만든다(DeletePosition position) {
    jdbc.execute("set local session_replication_role = replica");
    jdbc.update("update public.trip_schedule_versions set status='draft' where id=?", ACTIVE);
    insertItem(THIRD, ADDED_PLACE, 3, "2026-09-01T06:00:00Z");
    insertLeg(SECOND, THIRD, 2, "2026-09-01T04:00:00Z", "2026-09-01T04:10:00Z");
    jdbc.update("update public.trip_schedule_versions set status='active' where id=?", ACTIVE);
    jdbc.execute("set local session_replication_role = origin");

    ScheduleMutationResult result =
        store.deleteItem(edit(position.itemId, new DeleteScheduleItemCommand(ACTIVE)));

    assertThat(
            jdbc.queryForList(
                "select sequence_no from public.trip_items where schedule_version_id=? and trip_day_id=? order by sequence_no",
                Integer.class,
                result.activeScheduleVersionId(),
                DAY))
        .containsExactly(1, 2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=?",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(1);
  }

  @Test
  void completed_item의_PATCH_DELETE와_identical_reorder는_422로_원자거부한다() {
    jdbc.update(
        "insert into public.trip_item_progress (trip_plan_id,schedule_version_id,trip_item_id,status,actual_completed_at) values (?,?,?,'completed',?)",
        TRIP,
        ACTIVE,
        FIRST,
        Timestamp.from(NOW));
    String before = aggregateFingerprint();
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "완료");
    var order =
        new ReorderScheduleCommand(
            ACTIVE,
            List.of(
                new ReorderScheduleCommand.DayOrder(1, List.of(FIRST, SECOND)),
                new ReorderScheduleCommand.DayOrder(2, List.of(DAY_TWO_ITEM))));

    assertCompleted(() -> store.patchItem(edit(FIRST, patch)));
    assertCompleted(() -> store.deleteItem(edit(FIRST, new DeleteScheduleItemCommand(ACTIVE))));
    assertCompleted(() -> store.reorder(edit(null, order)));
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void reorder의_duplicate_missing_foreign_ID는_400이고_active를_보존한다() {
    var invalid =
        new ReorderScheduleCommand(
            ACTIVE,
            List.of(
                new ReorderScheduleCommand.DayOrder(1, List.of(FIRST, FIRST)),
                new ReorderScheduleCommand.DayOrder(2, List.of(DAY_TWO_ITEM))));
    String before = aggregateFingerprint();

    assertThatThrownBy(() -> store.reorder(edit(null, invalid)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ORDER_NOT_PERMUTATION");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void reorder는_기존_Day_시간_slot에_새_순서를_배치하고_leg를_재구성한다() {
    String original = originalFingerprint();
    var command =
        new ReorderScheduleCommand(
            ACTIVE,
            List.of(
                new ReorderScheduleCommand.DayOrder(1, List.of(SECOND, FIRST)),
                new ReorderScheduleCommand.DayOrder(2, List.of(DAY_TWO_ITEM))));

    ScheduleMutationResult result = store.reorder(edit(null, command));

    assertThat(result.changedItemIds())
        .containsExactlyElementsOf(
            jdbc.queryForList(
                "select id from public.trip_items where schedule_version_id=? order by trip_day_id, sequence_no",
                UUID.class,
                result.activeScheduleVersionId()));
    assertThat(result.changedItemIds()).doesNotContain(FIRST, SECOND, DAY_TWO_ITEM);

    assertThat(
            jdbc.queryForList(
                "select place_id from public.trip_items where schedule_version_id=? and trip_day_id=? order by sequence_no",
                UUID.class,
                result.activeScheduleVersionId(),
                DAY))
        .containsExactly(SECOND_PLACE, FIRST_PLACE);
    assertThat(
            jdbc.queryForList(
                "select planned_start_at from public.trip_items where schedule_version_id=? and trip_day_id=? order by sequence_no",
                Timestamp.class,
                result.activeScheduleVersionId(),
                DAY))
        .extracting(Timestamp::toInstant)
        .containsExactly(
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_items where schedule_version_id=? and id in (?,?)",
                Integer.class,
                result.activeScheduleVersionId(),
                FIRST,
                SECOND))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "select facts->>'derivation' as derivation from public.trip_legs where schedule_version_id=? and trip_day_id=?",
                result.activeScheduleVersionId(),
                DAY))
        .containsEntry("derivation", "conservative_walk_v1");
    assertThat(originalFingerprint()).isEqualTo(original);
  }

  @Test
  void MOVE는_source_target_Day를_compact하고_모든_leg를_새_ID로_완성한다() {
    var command =
        new MoveScheduleItemCommand(
            ACTIVE, 2, 2, OffsetDateTime.parse("2026-09-02T10:30:00+09:00"));

    ScheduleMutationResult result = store.moveItem(edit(FIRST, command));

    assertThat(result.changedItemIds())
        .containsExactly(
            jdbc.queryForObject(
                "select id from public.trip_items where schedule_version_id=? and trip_day_id=? and place_id=?",
                UUID.class,
                result.activeScheduleVersionId(),
                DAY_TWO,
                FIRST_PLACE))
        .doesNotContain(FIRST);

    assertThat(
            jdbc.queryForList(
                "select sequence_no from public.trip_items where schedule_version_id=? and trip_day_id=? order by sequence_no",
                Integer.class,
                result.activeScheduleVersionId(),
                DAY))
        .containsExactly(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_items where schedule_version_id=? and trip_day_id=?",
                Integer.class,
                result.activeScheduleVersionId(),
                DAY_TWO))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_legs where schedule_version_id=?",
                Integer.class,
                result.activeScheduleVersionId()))
        .isEqualTo(1);
  }

  @Test
  void 단일_item_Day의_DELETE와_cross_Day_MOVE는_422_DAY_EMPTY로_원자거부한다() {
    String before = aggregateFingerprint();

    assertDayEmpty(
        () -> store.deleteItem(edit(DAY_TWO_ITEM, new DeleteScheduleItemCommand(ACTIVE))));
    assertDayEmpty(
        () ->
            store.moveItem(
                edit(
                    DAY_TWO_ITEM,
                    new MoveScheduleItemCommand(
                        ACTIVE, 1, 3, OffsetDateTime.parse("2026-09-01T15:00:00+09:00")))));

    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "cancelled", "failed"})
  void terminal_trip의_네_편집_endpoint는_409이고_DB를_변경하지_않는다(String status) {
    jdbc.update("update public.trip_plans set status=? where id=?", status, TRIP);
    String before = aggregateFingerprint();
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "terminal");
    var reorder =
        new ReorderScheduleCommand(
            ACTIVE,
            List.of(
                new ReorderScheduleCommand.DayOrder(1, List.of(FIRST, SECOND)),
                new ReorderScheduleCommand.DayOrder(2, List.of(DAY_TWO_ITEM))));

    assertTerminal(() -> store.patchItem(edit(FIRST, patch)));
    assertTerminal(() -> store.deleteItem(edit(FIRST, new DeleteScheduleItemCommand(ACTIVE))));
    assertTerminal(() -> store.reorder(edit(null, reorder)));
    assertTerminal(
        () ->
            store.moveItem(
                edit(
                    FIRST,
                    new MoveScheduleItemCommand(
                        ACTIVE, 2, 2, OffsetDateTime.parse("2026-09-02T10:30:00+09:00")))));
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  void legacy_invalid_non_target_reference의_PATCH는_aggregate_전체를_rollback한다() {
    jdbc.execute("drop trigger trg_trip_items_required_references on public.trip_items");
    jdbc.execute(
        "alter table public.trip_items drop constraint chk_trip_items_required_references");
    mutateLegacyItem("update public.trip_items set item_type='accommodation' where id=?", SECOND);
    String before = aggregateFingerprint();
    var patch =
        new PatchScheduleItemCommand(
            ACTIVE, Set.of("memo"), null, null, null, null, null, null, null, null, "검증");

    assertThatThrownBy(() -> patchInNestedTransaction(edit(FIRST, patch)))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");
    assertThat(aggregateFingerprint()).isEqualTo(before);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void 동시_device의_같은_ETag_PATCH는_하나만_commit하고_다른_요청을_409로_종료한다() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var calls =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await();
                            try {
                              store.patchItem(
                                  edit(
                                      FIRST,
                                      new PatchScheduleItemCommand(
                                          ACTIVE,
                                          Set.of("memo"),
                                          null,
                                          null,
                                          null,
                                          null,
                                          null,
                                          null,
                                          null,
                                          null,
                                          "device-" + index)));
                              return "SUCCESS";
                            } catch (TripException failure) {
                              return failure.code();
                            }
                          }))
              .toList();
      ready.await();
      start.countDown();
      assertThat(calls.stream().map(future -> get(future)).toList())
          .containsExactlyInAnyOrder("SUCCESS", "TRIP_VERSION_CONFLICT");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from public.trip_schedule_versions where trip_plan_id=? and status='active'",
                  Integer.class,
                  TRIP))
          .isEqualTo(1);
    } finally {
      jdbc.update("delete from public.trip_plans where id=?", TRIP);
      jdbc.update(
          "delete from public.tour_places where id in (?,?,?)",
          FIRST_PLACE,
          SECOND_PLACE,
          ADDED_PLACE);
      jdbc.update("delete from public.user_profiles where id=?", OWNER);
      jdbc.update("delete from auth.users where id=?", OWNER);
    }
  }

  private static String get(java.util.concurrent.Future<String> future) {
    try {
      return future.get();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private <T> ScheduleEditRecord<T> edit(UUID itemId, T command) {
    return edit(itemId, command, 1);
  }

  private <T> ScheduleEditRecord<T> edit(UUID itemId, T command, long revision) {
    return new ScheduleEditRecord<>(
        OWNER, TRIP, itemId, new TripExpectedRevision(TRIP, revision), command, NOW);
  }

  private UUID copiedItem(UUID versionId, UUID placeId, UUID dayId) {
    return jdbc.queryForObject(
        "select id from public.trip_items where schedule_version_id=? and trip_day_id=? and place_id=?",
        UUID.class,
        versionId,
        dayId,
        placeId);
  }

  private java.util.Map<String, Object> progress(UUID itemId) {
    return jdbc.queryForMap(
        """
        select status, actual_started_at, actual_arrived_at, actual_completed_at
        from public.trip_item_progress where trip_item_id=?
        """,
        itemId);
  }

  private void mutateLegacyItem(String sql, Object... arguments) {
    jdbc.execute("set local session_replication_role = replica");
    try {
      jdbc.update(sql, arguments);
    } finally {
      jdbc.execute("set local session_replication_role = origin");
    }
  }

  private ScheduleMutationResult patchInNestedTransaction(
      ScheduleEditRecord<PatchScheduleItemCommand> record) {
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    return nested.execute(ignored -> store.patchItem(record));
  }

  private static void assertCompleted(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_COMPLETED");
  }

  private static void assertDayEmpty(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_DAY_EMPTY");
  }

  private static void assertTerminal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_TERMINAL_STATE_CONFLICT");
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
          (id, trip_plan_id, place_id, check_in_date, check_out_date,
           check_in_time, check_out_time, sequence_no)
        values (?, ?, ?, '2026-09-01', '2026-09-02', '15:00:00', '11:00:00', 1)
        """,
        ACCOMMODATION_ID,
        TRIP,
        ADDED_PLACE);
    jdbc.update(
        """
        insert into public.trip_transport_events
          (id, trip_plan_id, event_type, transport_type, terminal_place_id, scheduled_at)
        values (?, ?, 'arrival', 'flight', ?, '2026-09-01T00:00:00Z'),
               (?, ?, 'departure', 'flight', ?, '2026-09-02T00:00:00Z')
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

  private void insertLeg(UUID from, UUID to, int sequence, String departure, String arrival) {
    jdbc.update(
        """
        insert into public.trip_legs
          (trip_plan_id, trip_day_id, schedule_version_id, sequence_no, from_item_id, to_item_id,
           transport_mode, planned_departure_at, planned_arrival_at, walk_minutes, wait_minutes,
           ride_minutes, transfer_minutes, duration_minutes, buffer_minutes, distance_meters,
           estimated_fare, facts)
        values (?, ?, ?, ?, ?, ?, 'walk', ?, ?, 10, 0, 0, 0, 10, 0, 500, 0,
                '{"derivation":"fixture"}'::jsonb)
        """,
        TRIP,
        DAY,
        ACTIVE,
        sequence,
        from,
        to,
        Timestamp.from(Instant.parse(departure)),
        Timestamp.from(Instant.parse(arrival)));
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

  private enum DeletePosition {
    FIRST(JdbcScheduleMutationStoreIntegrationTest.FIRST),
    MIDDLE(JdbcScheduleMutationStoreIntegrationTest.SECOND),
    LAST(JdbcScheduleMutationStoreIntegrationTest.THIRD);

    private final UUID itemId;

    DeletePosition(UUID itemId) {
      this.itemId = itemId;
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
