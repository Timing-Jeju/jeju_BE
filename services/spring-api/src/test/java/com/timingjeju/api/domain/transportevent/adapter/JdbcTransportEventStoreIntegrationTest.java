package com.timingjeju.api.domain.transportevent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.service.TransportEventService;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTransportEventStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("47100000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("47100000-0000-0000-0000-000000000002");
  private static final UUID TRIP = UUID.fromString("47100000-0000-0000-0000-000000000003");
  private static final UUID PLACE = UUID.fromString("47100000-0000-0000-0000-000000000004");
  private static final UUID VERSION = UUID.fromString("47100000-0000-0000-0000-000000000005");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransportEventService service;

  @BeforeEach
  void setUp() {
    owner(OWNER);
    owner(OTHER);
    place();
    trip();
  }

  @Test
  void PUT은_arrival_place와_departure_ferry를_eventType별_한행으로_저장한다() {
    var arrival = service.put(OWNER, TRIP, expected(1), arrival(PLACE, null));
    var departure = service.put(OWNER, TRIP, expected(2), departure("제주항"));

    assertThat(arrival.event().eventType()).isEqualTo("arrival");
    assertThat(arrival.event().scheduledAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    assertThat(departure.event().transportType()).isEqualTo("ferry");
    assertThat(
            jdbc.queryForList(
                "select event_type || ':' || transport_type from public.trip_transport_events where trip_plan_id = ? order by event_type",
                String.class,
                TRIP))
        .containsExactly("arrival:flight", "departure:ferry");
    assertThat(revision()).isEqualTo(3L);
  }

  @Test
  void 같은_eventType_PUT은_완전교체하고_canonical_noop은_root와_timestamp를_보존한다() {
    service.put(OWNER, TRIP, expected(1), arrival(PLACE, null));
    String rowId =
        jdbc.queryForObject(
            "select id::text from public.trip_transport_events where trip_plan_id = ?",
            String.class,
            TRIP);

    service.put(OWNER, TRIP, expected(2), arrival(null, "  제주공항  "));
    assertThat(
            jdbc.queryForObject(
                "select id::text from public.trip_transport_events where trip_plan_id = ?",
                String.class,
                TRIP))
        .isEqualTo(rowId);
    String before = fingerprint();

    var noOp = service.put(OWNER, TRIP, expected(3), arrival(null, "제주공항"));
    assertThat(noOp.scheduleEffect()).isEqualTo("maintained");
    assertThat(noOp.regenerationRequired()).isFalse();
    assertThat(noOp.etag()).endsWith("-r3\"");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void 실제변경은_active_version_pointer_score를_같은_transaction에서_무효화한다() {
    service.put(OWNER, TRIP, expected(1), arrival(PLACE, null));
    installActiveSchedule();

    var result = service.put(OWNER, TRIP, expected(2), arrival(null, "제주국제공항"));

    assertThat(result.scheduleEffect()).isEqualTo("invalidated");
    assertThat(result.regenerationRequired()).isTrue();
    assertThat(result.activeScheduleVersionId()).isNull();
    assertThat(result.tripStatus()).isEqualTo("draft");
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
  void DELETE는_선택한_event만_지우고_missing이면_다른_event를_보존한다() {
    service.put(OWNER, TRIP, expected(1), arrival(PLACE, null));
    service.put(OWNER, TRIP, expected(2), departure("제주항"));

    var deleted = service.delete(OWNER, TRIP, "arrival", expected(3));
    assertThat(deleted.deleted()).isTrue();
    assertThat(deleted.event()).isNull();
    assertThat(
            jdbc.queryForList(
                "select event_type from public.trip_transport_events where trip_plan_id = ?",
                String.class,
                TRIP))
        .containsExactly("departure");
    String before = fingerprint();
    assertCode(
        () -> service.delete(OWNER, TRIP, "arrival", expected(4)), "TRANSPORT_EVENT_NOT_FOUND");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void wrong_date_missing_place_stale_cross_owner_terminal_trip은_무수정으로_거부한다() {
    String before = fingerprint();
    assertCode(
        () -> service.put(OWNER, TRIP, expected(1), withDate("2026-09-02T09:00:00+09:00")),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () ->
            service.put(
                OWNER,
                TRIP,
                expected(1),
                arrival(UUID.fromString("47100000-0000-0000-0000-000000000099"), null)),
        "PLACE_NOT_FOUND");
    assertCode(() -> service.put(OTHER, TRIP, expected(1), arrival(PLACE, null)), "TRIP_NOT_FOUND");
    assertCode(
        () -> service.put(OWNER, TRIP, new TripExpectedRevision(OTHER, 1), arrival(PLACE, null)),
        "TRIP_VERSION_CONFLICT");
    jdbc.update("update public.trip_plans set status = 'completed' where id = ?", TRIP);
    assertCode(
        () -> service.put(OWNER, TRIP, expected(1), arrival(PLACE, null)),
        "TRIP_TERMINAL_STATE_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_transport_events where trip_plan_id = ?",
                Integer.class,
                TRIP))
        .isZero();
    assertThat(before).doesNotContain("arrival");
  }

  private TripExpectedRevision expected(long revision) {
    return new TripExpectedRevision(TRIP, revision);
  }

  private PutTransportEventCommand arrival(UUID placeId, String name) {
    return new PutTransportEventCommand(
        "arrival",
        "flight",
        placeId,
        name,
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        "KE1001",
        null);
  }

  private PutTransportEventCommand withDate(String value) {
    return new PutTransportEventCommand(
        "arrival", "flight", PLACE, null, OffsetDateTime.parse(value), null, null);
  }

  private PutTransportEventCommand departure(String name) {
    return new PutTransportEventCommand(
        "departure",
        "ferry",
        null,
        name,
        OffsetDateTime.parse("2026-09-05T19:00:00+09:00"),
        "퀸제누비아2호",
        null);
  }

  private void owner(UUID ownerId) {
    jdbc.update(
        "insert into auth.users (id,email) values (?,?)", ownerId, ownerId + "@issue47.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)",
        ownerId,
        ownerId + "@issue47.test");
  }

  private void place() {
    jdbc.update(
        """
        insert into public.tour_places (
          id,content_id,name,normalized_name,category,region_code,region_label,location,source_provider,source_service
        ) values (?, 'issue47-place', '제주국제공항', '제주국제공항', 'PC', 'jeju-si', '제주시',
          ST_SetSRID(ST_MakePoint(126.493,33.510),4326)::geography, 'fixture', 'issue47')
        """,
        PLACE);
  }

  private void trip() {
    jdbc.update(
        """
        insert into public.trip_plans (
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,source_mode,data_version
        ) values (?, ?, 'issue47-trip-token', '항공 선박 여행', 'draft', '2026-09-01', '2026-09-05',
          'Asia/Seoul', 'normal', 'fixture', 'issue47-v1')
        """,
        TRIP,
        OWNER);
  }

  private void installActiveSchedule() {
    jdbc.update(
        "insert into public.trip_schedule_versions (id,trip_plan_id,version_no,status,source_type) values (?,?,1,'draft','initial')",
        VERSION,
        TRIP);
    for (int index = 0; index < 5; index++) {
      UUID dayId =
          UUID.nameUUIDFromBytes(
              ("issue47-day-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      java.time.LocalDate date = java.time.LocalDate.parse("2026-09-01").plusDays(index);
      jdbc.update(
          "insert into public.trip_days (id,trip_plan_id,day_no,trip_date) values (?,?,?,?)",
          dayId,
          TRIP,
          index + 1,
          java.sql.Date.valueOf(date));
      jdbc.update(
          """
          insert into public.trip_items (
            id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,
            planned_start_at,planned_end_at,stay_minutes,source,facts
          ) values (?, ?, ?, ?, 1, 'custom', '검증 일정',
            (?::date + time '09:00') at time zone 'Asia/Seoul',
            (?::date + time '10:00') at time zone 'Asia/Seoul',
            60, 'user_input', '{"location":{"lat":33.5,"lng":126.5}}'::jsonb)
          """,
          UUID.nameUUIDFromBytes(
              ("issue47-item-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          TRIP,
          dayId,
          VERSION,
          java.sql.Date.valueOf(date),
          java.sql.Date.valueOf(date));
    }
    jdbc.update(
        "update public.trip_plans set status='planned',active_schedule_version_id=?,total_score=88 where id=?",
        VERSION,
        TRIP);
    jdbc.update(
        "update public.trip_schedule_versions set status='active',applied_at=now() where id=?",
        VERSION);
  }

  private long revision() {
    return jdbc.queryForObject(
        "select revision from public.trip_plans where id = ?", Long.class, TRIP);
  }

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select p.revision::text || ':' || p.status || ':' || p.updated_at::text || ':' ||
          coalesce(string_agg(e.event_type || ':' || e.transport_type || ':' || e.updated_at::text, ',' order by e.event_type), '')
        from public.trip_plans p
        left join public.trip_transport_events e on e.trip_plan_id = p.id
        where p.id = ?
        group by p.revision,p.status,p.updated_at
        """,
        String.class,
        TRIP);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(TransportEventException.class)
        .extracting(failure -> ((TransportEventException) failure).code())
        .isEqualTo(code);
  }
}
