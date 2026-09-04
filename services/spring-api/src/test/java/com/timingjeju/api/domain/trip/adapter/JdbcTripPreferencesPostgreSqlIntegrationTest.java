package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.ReplaceTripPreferencesRecord;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcTripPreferencesPostgreSqlIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("46000000-0000-0000-0000-000000000002");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000046");
  private static final UUID PLACE = UUID.fromString("46000000-0000-0000-0000-000000000047");
  private static final Instant INITIAL = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant MUTATION = Instant.parse("2026-09-02T00:00:00Z");

  @Autowired private JdbcTripStore store;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertUser(OWNER, "owner");
    insertUser(OTHER, "other");
    insertPlace();
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
           source_mode,data_version,created_at,updated_at,revision)
        values (?,?,'issue46-public','Issue 46','draft',date '2026-09-10',date '2026-09-12',
                'Asia/Seoul','normal','fixture','issue46-v1',?,?,7)
        """,
        TRIP,
        OWNER,
        Timestamp.from(INITIAL),
        Timestamp.from(INITIAL));
    insertCurrentPreferences();
  }

  @AfterEach
  void clean() {
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    jdbc.update("delete from public.tour_places where id=?", PLACE);
    jdbc.update("delete from public.user_profiles where id in (?,?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?,?)", OWNER, OTHER);
  }

  @Test
  void absent_preferences도_current와_modes를_DB에서_reload해_한번에생성한다() {
    jdbc.update("delete from public.trip_preferences where trip_plan_id=?", TRIP);
    jdbc.update("delete from public.trip_transport_modes where trip_plan_id=?", TRIP);

    TripPreferencesMutation result = store.replacePreferences(record(7, changed()));

    assertThat(result.preferences()).isEqualTo(changed());
    assertThat(result.revision()).isEqualTo(8);
    assertThat(revision()).isEqualTo(8);
    assertThat(modeNames()).containsExactly("rental_car", "taxi");
  }

  @Test
  void exact_canonical_noop은_revision_updatedAt_active_pointer_score와_rows를모두보존한다() {
    UUID active = activateScheduleAndScore();
    String before = fingerprint();

    TripPreferencesMutation result = store.replacePreferences(record(7, current()));

    assertThat(result.revision()).isEqualTo(7);
    assertThat(result.updatedAt()).isEqualTo(INITIAL);
    assertThat(result.scheduleEffect()).isEqualTo("maintained");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.activeScheduleVersionId()).isEqualTo(active);
    assertThat(result.tripStatus()).isEqualTo("planned");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void inactive_exact_canonical_noop도_maintained이며_revision_updatedAt_rows를보존한다() {
    String before = fingerprint();

    TripPreferencesMutation result = store.replacePreferences(record(7, current()));

    assertThat(result.revision()).isEqualTo(7);
    assertThat(result.updatedAt()).isEqualTo(INITIAL);
    assertThat(result.scheduleEffect()).isEqualTo("maintained");
    assertThat(result.regenerationRequired()).isFalse();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void persisted_noncanonical_region은_DB경계에서거부되고기존rows를보존한다() {
    UUID active = activateScheduleAndScore();
    String before = fingerprint();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_preferences set arrival_region_code=? where trip_plan_id=?",
                    "  \u110C\u1166\u110C\u116E\u1109\u1175  ",
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_preferences set departure_region_code=? where trip_plan_id=?",
                    " seogwipo-si ",
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_preferences set preferred_region_codes=array[?] where"
                        + " trip_plan_id=?",
                    " aewol ",
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id from public.trip_plans where id=?",
                UUID.class,
                TRIP))
        .isEqualTo(active);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void unknown_persisted_category는_DB경계에서거부되고기존rows를보존한다() {
    String before = fingerprint();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update public.trip_preferences set preferred_categories=array['unknown']"
                        + " where trip_plan_id=?",
                    TRIP))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void changed_without_active는_revision을정확히한번올리고_DB_reload를응답한다() {
    TripPreferencesMutation result = store.replacePreferences(record(7, changed()));

    assertThat(result.revision()).isEqualTo(8);
    assertThat(result.updatedAt()).isEqualTo(MUTATION);
    assertThat(result.scheduleEffect()).isEqualTo("none");
    assertThat(result.preferences().preferredRegionCodes()).containsExactly("seongsan");
    assertThat(modeNames()).containsExactly("rental_car", "taxi");
  }

  @Test
  void active_change는_version_supersede_pointer와_score_clear_draft_revision1을원자commit한다() {
    UUID version = activateScheduleAndScore();

    TripPreferencesMutation result = store.replacePreferences(record(7, changed()));

    assertThat(result.revision()).isEqualTo(8);
    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(result.tripStatus()).isEqualTo("draft");
    assertThat(
            jdbc.queryForObject(
                "select status from public.trip_schedule_versions where id=?",
                String.class,
                version))
        .isEqualTo("superseded");
    assertThat(
            jdbc.queryForObject(
                "select active_schedule_version_id is null from public.trip_plans where id=?",
                Boolean.class,
                TRIP))
        .isTrue();
    assertThat(store.findOwned(OWNER, TRIP, MUTATION).orElseThrow().totalScore()).isNull();
  }

  @Test
  void stale_cross_owner_terminal과_missing_place는_기존fingerprint를보존한다() {
    String before = fingerprint();
    assertCode(() -> store.replacePreferences(record(6, changed())), "TRIP_VERSION_CONFLICT");
    assertCode(
        () ->
            store.replacePreferences(
                new ReplaceTripPreferencesRecord(OTHER, TRIP, 7, changed(), MUTATION)),
        "TRIP_NOT_FOUND");
    jdbc.update("update public.trip_plans set status='completed' where id=?", TRIP);
    assertCode(
        () -> store.replacePreferences(record(7, changed())), "TRIP_TERMINAL_STATE_CONFLICT");
    jdbc.update("update public.trip_plans set status='draft' where id=?", TRIP);
    ReplaceTripPreferencesCommand missing =
        new ReplaceTripPreferencesCommand(
            List.of("cafe"),
            "jeju-si",
            "seogwipo-si",
            List.of(),
            UUID.randomUUID(),
            null,
            List.of(new TripTransportMode("taxi", 1, true)));
    assertCode(() -> store.replacePreferences(record(7, missing)), "PLACE_NOT_FOUND");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void same_revision_concurrent_replace는_정확히한winner와한version_conflict다() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Callable<String>> calls = List.of(() -> outcome(changed()), () -> outcome(alternate()));
      var futures = executor.invokeAll(calls);
      assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
          .containsExactlyInAnyOrder("SUCCESS", "TRIP_VERSION_CONFLICT");
    }
    assertThat(revision()).isEqualTo(8);
  }

  @ParameterizedTest(name = "inactive normalized place: {0}")
  @MethodSource("inactivePlaceStates")
  void stale_effective_tombstone_source_delete는전체rollback한다(String state, String mutation) {
    jdbc.update(mutation, PLACE);
    String before = fingerprint();

    assertCode(() -> store.replacePreferences(record(7, changed())), "PLACE_NOT_FOUND");

    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void future_stale_at은_current_place로허용하고_replace한다() {
    jdbc.update(
        "update public.tour_places set stale=false,stale_at=now()+interval '1 hour' where id=?",
        PLACE);
    assertThat(store.replacePreferences(record(7, changed())).revision()).isEqualTo(8);
  }

  private static Stream<Arguments> inactivePlaceStates() {
    return Stream.of(
        Arguments.of("stale", "update public.tour_places set stale=true,stale_at=now() where id=?"),
        Arguments.of(
            "effective-stale",
            "update public.tour_places set stale=false,stale_at=now()-interval '1 second' where"
                + " id=?"),
        Arguments.of("tombstone", "update public.tour_places set tombstoned_at=now() where id=?"),
        Arguments.of(
            "source-delete", "update public.tour_places set source_deleted_at=now() where id=?"));
  }

  private ReplaceTripPreferencesRecord record(
      long revision, ReplaceTripPreferencesCommand command) {
    return new ReplaceTripPreferencesRecord(OWNER, TRIP, revision, command, MUTATION);
  }

  private String outcome(ReplaceTripPreferencesCommand command) {
    try {
      store.replacePreferences(record(7, command));
      return "SUCCESS";
    } catch (TripException failure) {
      return failure.code();
    }
  }

  private void insertCurrentPreferences() {
    jdbc.update(
        "insert into public.trip_transport_modes"
            + " (trip_plan_id,transport_mode,priority,is_primary,created_at) values"
            + " (?,'public_transit',1,true,?)",
        TRIP,
        Timestamp.from(INITIAL));
    jdbc.update(
        "insert into public.trip_preferences"
            + " (trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,preferred_region_codes,start_place_id,end_place_id,raw_answers,created_at,updated_at)"
            + " values (?,array['cafe'],'jeju-si','seogwipo-si',array['aewol'],null,null,'{}',?,?)",
        TRIP,
        Timestamp.from(INITIAL),
        Timestamp.from(INITIAL));
  }

  private void insertUser(UUID id, String suffix) {
    jdbc.update(
        "insert into auth.users(id,email,raw_user_meta_data) values (?,?,'{}')",
        id,
        suffix + "@issue46.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", id, suffix + "@issue46.test");
  }

  private void insertPlace() {
    jdbc.update(
        "insert into"
            + " public.tour_places(id,content_id,content_type_id,name,normalized_name,category,region_code,region_label,location,source_provider,source_service,stale)"
            + " values"
            + " (?,'issue46-place','12','장소','장소','VE','50110','제주시',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture','issue46',false)",
        PLACE);
  }

  private UUID activateScheduleAndScore() {
    UUID version = UUID.fromString("46000000-0000-0000-0000-000000000048");
    jdbc.update(
        "insert into"
            + " public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type,resulting_score,created_at,applied_at)"
            + " values (?,?,1,'active','initial',80,?,?)",
        version,
        TRIP,
        Timestamp.from(INITIAL),
        Timestamp.from(INITIAL));
    jdbc.update(
        "update public.trip_plans set status='planned',active_schedule_version_id=? where id=?",
        version,
        TRIP);
    jdbc.update(
        "insert into"
            + " public.compute_runs(id,trip_plan_id,schedule_version_id,run_type,status,input_hash,contract_version,algorithm_version,facts_snapshot_at,source_data_version,result_summary,completed_at)"
            + " values"
            + " (?,?,?,'feasibility','succeeded','issue46','v1','v1',?,'fixture','{\"score\":80}',?)",
        UUID.fromString("46000000-0000-0000-0000-000000000049"),
        TRIP,
        version,
        Timestamp.from(INITIAL),
        Timestamp.from(INITIAL));
    return version;
  }

  private long revision() {
    return jdbc.queryForObject(
        "select revision from public.trip_plans where id=?", Long.class, TRIP);
  }

  private List<String> modeNames() {
    return jdbc.queryForList(
        "select transport_mode from public.trip_transport_modes where trip_plan_id=? order by"
            + " priority",
        String.class,
        TRIP);
  }

  private String fingerprint() {
    return jdbc.queryForObject(
            "select"
                + " revision||':'||updated_at||':'||status||':'||coalesce(active_schedule_version_id::text,'')"
                + " from public.trip_plans where id=?",
            String.class,
            TRIP)
        + jdbc.queryForObject(
            "select row_to_json(p)::text from public.trip_preferences p where trip_plan_id=?",
            String.class,
            TRIP)
        + modeNames();
  }

  private static ReplaceTripPreferencesCommand current() {
    return new ReplaceTripPreferencesCommand(
        List.of("cafe"),
        "jeju-si",
        "seogwipo-si",
        List.of("aewol"),
        null,
        null,
        List.of(new TripTransportMode("public_transit", 1, true)));
  }

  private static ReplaceTripPreferencesCommand changed() {
    return new ReplaceTripPreferencesCommand(
        List.of("cafe"),
        "jeju-si",
        "seogwipo-si",
        List.of("seongsan"),
        PLACE,
        null,
        List.of(
            new TripTransportMode("rental_car", 1, true), new TripTransportMode("taxi", 2, false)));
  }

  private static ReplaceTripPreferencesCommand alternate() {
    return new ReplaceTripPreferencesCommand(
        List.of("restaurant"),
        "jeju-si",
        "seogwipo-si",
        List.of(),
        null,
        PLACE,
        List.of(new TripTransportMode("taxi", 1, true)));
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(code);
  }
}
