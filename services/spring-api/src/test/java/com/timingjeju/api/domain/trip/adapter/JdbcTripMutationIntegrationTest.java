package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.commandinput.CommandInputParent;
import com.timingjeju.api.application.commandinput.CommandInputRequest;
import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.trip.PatchTripCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.application.trip.TripMutationResult;
import com.timingjeju.api.application.trip.TripPatchValue;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.TripUpdateRecord;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripMutationIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("45000000-0000-0000-0000-000000000101");
  private static final UUID OTHER = UUID.fromString("45000000-0000-0000-0000-000000000102");
  private static final UUID TRIP = UUID.fromString("45000000-0000-0000-0000-000000000103");
  private static final UUID VERSION = UUID.fromString("45000000-0000-0000-0000-000000000104");
  private static final UUID IMPORT_RUN = UUID.fromString("45000000-0000-0000-0000-000000000105");
  private static final UUID PLACE = UUID.fromString("45000000-0000-0000-0000-000000000106");
  private static final UUID REVISION_RUN = UUID.fromString("45000000-0000-0000-0000-000000000107");
  private static final Instant NOW = Instant.parse("2026-09-01T04:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcTripStore store;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CommandInputCanonicalizer commandInputCanonicalizer;
  @Autowired private CommandInputSnapshotRepository commandInputRepository;

  @BeforeEach
  void setUp() {
    clean();
    insertOwner(OWNER, "trip-mutation-owner@issue45.test");
    insertOwner(OTHER, "trip-mutation-other@issue45.test");
    insertTrip("draft");
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from public.trip_plans where id = ?", TRIP);
    jdbc.update("delete from public.tour_places where id = ?", PLACE);
    jdbc.update("delete from public.data_import_runs where id = ?", IMPORT_RUN);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", OWNER, OTHER);
  }

  @Test
  void title_only는_revision만_한번_증가시키고_active와_status를_유지한다() {
    TripMutationResult result = store.updateOwned(record(title("제주 가족 여행"), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("maintained");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.trip().revision()).isEqualTo(2);
    assertThat(result.trip().title()).isEqualTo("제주 가족 여행");
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().activeScheduleVersionId()).isNull();
    assertThat(root("revision", Long.class)).isEqualTo(2L);
  }

  @Test
  void 일정없는_날짜변경은_달력을_정확히_재구성하고_교통이나숙소_범위밖이면_원자실패한다() {
    TripMutationResult changed =
        store.updateOwned(
            record(dates(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-05")), 1, NOW));

    assertThat(changed.scheduleEffect()).isEqualTo("none");
    assertThat(changed.trip().days())
        .extracting(day -> day.date().toString())
        .containsExactly("2026-09-02", "2026-09-03", "2026-09-04", "2026-09-05");
    assertThat(changed.trip().revision()).isEqualTo(2);

    jdbc.update(
        "insert into public.trip_transport_events (trip_plan_id, event_type, transport_type, terminal_name, scheduled_at) values (?, 'arrival', 'flight', '제주공항', '2026-09-02T00:00:00Z')",
        TRIP);
    String before = fingerprint();
    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-05")),
                    2,
                    NOW.plusSeconds(1))),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void pace변경은_active를_superseded하고_pointer_score_status를_원자무효화한다() {
    installActiveSchedule();

    TripMutationResult result = store.updateOwned(record(pace("slow"), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().activeScheduleVersionId()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id = ?",
                String.class,
                VERSION))
        .isEqualTo("superseded");
  }

  @Test
  void active가_없어도_교통선호_실제변경은_draft와_재생성필요를_명시하고_전체교체한다() {
    List<TripTransportMode> desired =
        List.of(
            new TripTransportMode("taxi", 1, true),
            new TripTransportMode("public_transit", 2, false));

    TripMutationResult result = store.updateOwned(record(modes(desired), 1, NOW));

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.trip().status()).isEqualTo("draft");
    assertThat(result.trip().transportModes()).containsExactlyElementsOf(desired);
    assertThat(result.trip().revision()).isEqualTo(2);
  }

  @Test
  void 일정없는_날짜변경은_1일과_30일을_허용하고_31일을_원자거부한다() {
    TripMutationResult oneDay =
        store.updateOwned(
            record(dates(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-02")), 1, NOW));
    assertThat(oneDay.trip().days()).hasSize(1);

    TripMutationResult thirtyDays =
        store.updateOwned(
            record(
                dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")),
                2,
                NOW.plusSeconds(1)));
    assertThat(thirtyDays.trip().days()).hasSize(30);
    String before = fingerprint();

    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-01")),
                    3,
                    NOW.plusSeconds(2))),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 일정버전_날짜변경_stale_cross_owner_terminal은_각_code와_무변경을_보장한다() {
    installDraftSchedule();
    String initial = fingerprint();
    assertCode(
        () ->
            store.updateOwned(
                record(
                    dates(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02")), 1, NOW)),
        "TRIP_REGENERATION_REQUIRED");
    assertThat(fingerprint()).isEqualTo(initial);

    assertCode(() -> store.updateOwned(record(title("stale"), 2, NOW)), "TRIP_VERSION_CONFLICT");
    TripUpdateRecord mismatchedTag =
        new TripUpdateRecord(
            OWNER, TRIP, new TripExpectedRevision(OTHER, 1), title("wrong-tag"), ids(), NOW);
    assertCode(() -> store.updateOwned(mismatchedTag), "TRIP_VERSION_CONFLICT");
    TripUpdateRecord other =
        new TripUpdateRecord(
            OTHER, TRIP, new TripExpectedRevision(TRIP, 1), title("other"), ids(), NOW);
    assertCode(() -> store.updateOwned(other), "TRIP_NOT_FOUND");

    jdbc.update("update public.trip_plans set status = 'failed' where id = ?", TRIP);
    assertCode(
        () -> store.updateOwned(record(title("terminal"), 1, NOW)), "TRIP_TERMINAL_STATE_CONFLICT");
  }

  @Test
  void 같은_revision의_동시_writer는_정확히_하나만_성공한다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> updateAfter(start, "동시 수정 A"));
      var second = pool.submit(() -> updateAfter(start, "동시 수정 B"));
      start.countDown();

      List<String> outcomes = List.of(first.get(), second.get());
      assertThat(outcomes).containsExactlyInAnyOrder("success", "TRIP_VERSION_CONFLICT");
      assertThat(root("revision", Long.class)).isEqualTo(2L);
    }
  }

  @Test
  void PATCH와_DELETE_경합은_직렬화되고_삭제뒤_aggregate가_남지않는다() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var update = pool.submit(() -> updateAfter(start, "삭제와 경합하는 수정"));
      var delete = pool.submit(() -> deleteAfter(start));
      start.countDown();

      assertThat(delete.get()).isEqualTo("success");
      assertThat(update.get()).isIn("success", "TRIP_NOT_FOUND");
      assertThat(count("trip_plans", "id", TRIP)).isZero();
    }
  }

  @Test
  void delete는_aggregate만_cascade하고_외부fact와_user를_보존하며_repeat_owner_live_run을_차단한다() {
    assertThat(
            jdbc.queryForList(
                """
                select conrelid::regclass::text || ':' || confdeltype::text
                from pg_constraint
                where contype = 'f'
                  and confrelid = 'public.trip_plans'::regclass
                  and confdeltype <> 'c'
                order by conrelid::regclass::text
                """,
                String.class))
        .as("trip_plans 직접 자식 FK는 모두 ON DELETE CASCADE여야 한다")
        .isEmpty();
    installRevisionCommandInputAggregate();
    installExternalFactReference();
    assertThat(count("schedule_revision_runs", "trip_plan_id", TRIP)).isOne();
    assertThat(count("compute_run_inputs", "trip_plan_id", TRIP)).isOne();
    store.deleteOwned(OWNER, TRIP);

    assertThat(count("trip_plans", "id", TRIP)).isZero();
    assertThat(count("trip_days", "trip_plan_id", TRIP)).isZero();
    assertThat(count("schedule_revision_runs", "trip_plan_id", TRIP)).isZero();
    assertThat(count("compute_run_inputs", "trip_plan_id", TRIP)).isZero();
    assertThat(count("tour_places", "id", PLACE)).isOne();
    assertThat(count("data_import_runs", "id", IMPORT_RUN)).isOne();
    assertThat(count("user_profiles", "id", OWNER)).isOne();
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_NOT_FOUND");

    insertTrip("draft");
    installActiveSchedule();
    jdbc.update("update public.trip_plans set status = 'live' where id = ?", TRIP);
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_DELETE_CONFLICT");
    jdbc.update("update public.trip_plans set status = 'draft' where id = ?", TRIP);
    insertQueuedGenerationRun();
    assertCode(() -> store.deleteOwned(OWNER, TRIP), "TRIP_DELETE_CONFLICT");
    assertThat(count("trip_plans", "id", TRIP)).isOne();
    assertCode(() -> store.deleteOwned(OTHER, TRIP), "TRIP_NOT_FOUND");

    jdbc.update(
        "update public.itinerary_generation_runs set status = 'failed' where trip_plan_id = ?",
        TRIP);
    jdbc.update("update public.trip_plans set status = 'failed' where id = ?", TRIP);
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).isZero();

    insertTrip("draft");
    installActiveSchedule();
    jdbc.update("update public.trip_plans set status = 'completed' where id = ?", TRIP);
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).as("completed").isZero();

    insertTrip("cancelled");
    store.deleteOwned(OWNER, TRIP);
    assertThat(count("trip_plans", "id", TRIP)).as("cancelled").isZero();
  }

  private String updateAfter(CountDownLatch start, String value) throws InterruptedException {
    start.await();
    try {
      store.updateOwned(record(title(value), 1, NOW));
      return "success";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private String deleteAfter(CountDownLatch start) throws InterruptedException {
    start.await();
    try {
      store.deleteOwned(OWNER, TRIP);
      return "success";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private void insertOwner(UUID id, String email) {
    jdbc.update("insert into auth.users (id, email) values (?, ?)", id, email);
    jdbc.update("insert into public.user_profiles (id, email) values (?, ?)", id, email);
  }

  private void insertTrip(String status) {
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, status, start_date, end_date,
           timezone, user_pace, source_mode, data_version)
        values (?, ?, ?, '제주 여행', ?, '2026-09-01', '2026-09-03',
                'Asia/Seoul', 'normal', 'fixture', 'issue45-v1')
        """,
        TRIP,
        OWNER,
        "issue45-trip-token-" + status,
        status);
    jdbc.update(
        "insert into public.trip_transport_modes (trip_plan_id, transport_mode, priority, is_primary) values (?, 'public_transit', 1, true)",
        TRIP);
    for (int index = 0; index < 3; index++) {
      jdbc.update(
          "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, ?, ?)",
          UUID.nameUUIDFromBytes(
              ("issue45-day-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          TRIP,
          index + 1,
          java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)));
    }
  }

  private void installDraftSchedule() {
    jdbc.update(
        "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status, source_type) values (?, ?, 1, 'draft', 'initial')",
        VERSION,
        TRIP);
  }

  private void installActiveSchedule() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "insert into public.trip_schedule_versions (id, trip_plan_id, version_no, status, source_type) values (?, ?, 1, 'draft', 'initial')",
                  VERSION,
                  TRIP);
              for (int index = 0; index < 3; index++) {
                UUID dayId =
                    jdbc.queryForObject(
                        "select id from public.trip_days where trip_plan_id = ? and day_no = ?",
                        UUID.class,
                        TRIP,
                        index + 1);
                jdbc.update(
                    """
                    insert into public.trip_items (
                      id, trip_plan_id, trip_day_id, schedule_version_id, sequence_no,
                      item_type, title, planned_start_at, planned_end_at, stay_minutes,
                      source, facts
                    ) values (?, ?, ?, ?, 1, 'custom', '일정 항목',
                              (?::date + time '09:00') at time zone 'Asia/Seoul',
                              (?::date + time '10:00') at time zone 'Asia/Seoul',
                              60, 'user_input',
                              '{"location":{"lat":33.5,"lng":126.5}}'::jsonb)
                    """,
                    UUID.nameUUIDFromBytes(
                        ("issue45-item-" + index)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    TRIP,
                    dayId,
                    VERSION,
                    java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)),
                    java.sql.Date.valueOf(LocalDate.parse("2026-09-01").plusDays(index)));
              }
              jdbc.update(
                  "update public.trip_plans set status = 'planned', active_schedule_version_id = ? where id = ?",
                  VERSION,
                  TRIP);
              jdbc.update(
                  "update public.trip_schedule_versions set status = 'active', applied_at = now() where id = ? and trip_plan_id = ?",
                  VERSION,
                  TRIP);
            });
  }

  private void installRevisionCommandInputAggregate() {
    installActiveSchedule();
    UUID targetDay =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id = ? and day_no = 1",
            UUID.class,
            TRIP);
    jdbc.update(
        """
        insert into public.schedule_revision_runs (
          id, owner_user_id, trip_plan_id, base_schedule_version_id,
          target_trip_day_id, contract_version, algorithm_version,
          idempotency_key, request_hash
        ) values (?, ?, ?, ?, ?, 'revision/v1', 'algorithm/v1', ?, repeat('a', 64))
        """,
        REVISION_RUN,
        OWNER,
        TRIP,
        VERSION,
        targetDay,
        UUID.fromString("45000000-0000-0000-0000-000000000109"));
    var structuredInput = objectMapper.createObjectNode();
    structuredInput.put("targetDayId", targetDay.toString());
    structuredInput.putArray("affectedItemIds");
    structuredInput.putArray("instructionCodes").add("MOVE_ITEM");
    commandInputRepository.save(
        commandInputCanonicalizer.canonicalize(
            new CommandInputRequest(
                new CommandInputParent.ScheduleRevision(REVISION_RUN),
                "schedule_revision",
                1,
                "command/v1",
                "algorithm/v1",
                structuredInput,
                OWNER,
                TRIP,
                VERSION,
                null)));
    jdbc.update(
        """
        update public.schedule_revision_runs
        set status = 'cancelled', failure_code = 'USER_CANCELLED',
            completed_at = now(), next_attempt_at = null
        where id = ?
        """,
        REVISION_RUN);
  }

  private void installExternalFactReference() {
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_provider, source_service,
          source_operation, data_version, status, finished_at
        ) values (?, 'tour_api', 'TourAPI', 'tour-api', 'KorService2',
                  'areaBasedList2', 'issue45-v1', 'succeeded', now())
        """,
        IMPORT_RUN);
    jdbc.update(
        """
        insert into public.tour_places
          (id, external_place_id, name, normalized_name, category, location,
           source_provider, source_service)
        values (?, 'issue45-place', '외부 장소', '외부 장소', '관광지',
                ST_SetSRID(ST_MakePoint(126.5, 33.5), 4326)::geography,
                'fixture', 'issue45-test')
        """,
        PLACE);
    jdbc.update(
        "insert into public.trip_preferences"
            + " (trip_plan_id, start_place_id, arrival_region_code, departure_region_code)"
            + " values (?, ?, 'jeju-si', 'seogwipo-si')",
        TRIP,
        PLACE);
  }

  private void insertQueuedGenerationRun() {
    UUID dayId =
        jdbc.queryForObject(
            "select id from public.trip_days where trip_plan_id = ? and day_no = 1",
            UUID.class,
            TRIP);
    jdbc.update(
        """
        insert into public.itinerary_generation_runs
          (id, trip_plan_id, trip_day_id, status, contract_version, algorithm_version,
           idempotency_key, requested_by_user_id)
        values (?, ?, ?, 'queued', 'recommendation.v1', 'issue45-v1', ?, ?)
        """,
        UUID.fromString("45000000-0000-0000-0000-000000000120"),
        TRIP,
        dayId,
        "issue45-generation-key",
        OWNER);
  }

  private TripUpdateRecord record(PatchTripCommand command, long revision, Instant updatedAt) {
    return new TripUpdateRecord(
        OWNER, TRIP, new TripExpectedRevision(TRIP, revision), command, ids(), updatedAt);
  }

  private static PatchTripCommand title(String title) {
    return command(
        TripPatchValue.present(title),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted());
  }

  private static PatchTripCommand pace(String pace) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(pace),
        TripPatchValue.omitted());
  }

  private static PatchTripCommand modes(List<TripTransportMode> modes) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(modes));
  }

  private static PatchTripCommand dates(LocalDate start, LocalDate end) {
    return command(
        TripPatchValue.omitted(),
        TripPatchValue.present(start),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.present(end));
  }

  private static PatchTripCommand command(
      TripPatchValue<String> title,
      TripPatchValue<LocalDate> start,
      TripPatchValue<String> pace,
      TripPatchValue<List<TripTransportMode>> modes) {
    return command(title, start, pace, modes, TripPatchValue.omitted());
  }

  private static PatchTripCommand command(
      TripPatchValue<String> title,
      TripPatchValue<LocalDate> start,
      TripPatchValue<String> pace,
      TripPatchValue<List<TripTransportMode>> modes,
      TripPatchValue<LocalDate> end) {
    return new PatchTripCommand(title, start, end, TripPatchValue.omitted(), pace, modes);
  }

  private static List<UUID> ids() {
    List<UUID> ids = new ArrayList<>(30);
    for (int index = 0; index < 30; index++) {
      ids.add(
          UUID.nameUUIDFromBytes(
              ("issue45-new-day-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    return ids;
  }

  private <T> T root(String column, Class<T> type) {
    return jdbc.queryForObject(
        "select " + column + " from public.trip_plans where id = ?", type, TRIP);
  }

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select concat_ws('|', revision, title, status, start_date, end_date,
          coalesce(active_schedule_version_id::text, ''),
          (select string_agg(concat(day_no, ':', trip_date), ',' order by day_no)
           from public.trip_days where trip_plan_id = p.id))
        from public.trip_plans p where id = ?
        """,
        String.class,
        TRIP);
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "select count(*) from public." + table + " where " + column + " = ?", Integer.class, id);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(code);
  }
}
