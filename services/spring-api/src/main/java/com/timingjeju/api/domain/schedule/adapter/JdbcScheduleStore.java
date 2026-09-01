package com.timingjeju.api.domain.schedule.adapter;

import com.timingjeju.api.application.schedule.ItemProgressSnapshot;
import com.timingjeju.api.application.schedule.ScheduleDaySnapshot;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.ScheduleItemSnapshot;
import com.timingjeju.api.application.schedule.ScheduleLegSnapshot;
import com.timingjeju.api.application.schedule.ScheduleLookup;
import com.timingjeju.api.application.schedule.ScheduleSnapshot;
import com.timingjeju.api.application.schedule.ScheduleStore;
import com.timingjeju.api.application.schedule.ScheduleVersionSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcScheduleStore implements ScheduleStore {
  private static final ZoneId JEJU = ZoneId.of("Asia/Seoul");
  private static final Set<String> VERSION_STATUSES =
      Set.of("draft", "candidate", "active", "superseded", "rejected");
  private static final Set<String> SOURCE_TYPES =
      Set.of("initial", "user_edit", "ai_generation", "recovery", "live_recalculation");
  private static final Set<String> ITEM_TYPES =
      Set.of("place_visit", "meal", "accommodation", "arrival", "departure", "free_time", "custom");
  private static final Set<String> PROGRESS_STATUSES =
      Set.of("planned", "active", "arrived", "completed", "skipped", "missed");
  private static final Set<String> TRANSPORT_MODES =
      Set.of("walk", "public_transit", "rental_car", "taxi");

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  public JdbcScheduleStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ScheduleLookup readOwned(UUID ownerId, UUID tripId, UUID versionId, Instant responseTime) {
    try {
      RootRow root = readRoot(ownerId, tripId, versionId);
      if (root == null) {
        return ScheduleLookup.tripNotFound();
      }
      if (root.versionId() == null) {
        return ScheduleLookup.versionNotFound();
      }
      validateRoot(root);

      List<DayRow> days = readDays(tripId);
      List<ItemRow> items = readItems(tripId, root.versionId());
      List<LegRow> legs = readLegs(tripId, root.versionId());
      return ScheduleLookup.found(assemble(root, days, items, legs, responseTime));
    } catch (DataAccessException | ScheduleDataIntegrityException failure) {
      throw ScheduleException.dataUnavailable();
    }
  }

  private RootRow readRoot(UUID ownerId, UUID tripId, UUID versionId) {
    String selector =
        versionId == null ? "v.id = p.active_schedule_version_id" : "v.id = :versionId";
    String sql =
        """
        select p.id as trip_id,
               v.id as version_id, v.version_no, v.status as version_status,
               v.source_type, v.base_schedule_version_id, v.resulting_score,
               fresh.completed_at as freshness_calculated_at,
               fresh.facts_snapshot_at as freshness_facts_observed_at,
               jsonb_exists(fresh.result_summary, 'observedAt') as freshness_observed_present,
               jsonb_typeof(fresh.result_summary -> 'observedAt') as freshness_observed_type,
               fresh.result_summary ->> 'observedAt' as freshness_observed_value,
               jsonb_typeof(fresh.result_summary -> 'expiresAt') as freshness_expires_type,
               fresh.result_summary ->> 'expiresAt' as freshness_expires_value
        from public.trip_plans p
        left join public.trip_schedule_versions v
          on v.trip_plan_id = p.id and %s
        left join lateral (
          select r.completed_at, r.facts_snapshot_at, r.result_summary
          from public.compute_runs r
          where r.trip_plan_id = p.id
            and r.schedule_version_id = v.id
            and r.run_type = 'feasibility'
            and r.status = 'succeeded'
          order by r.completed_at desc nulls last, r.id desc
          limit 1
        ) fresh on true
        where p.id = :tripId and p.user_id = :ownerId
        """
            .formatted(selector);
    MapSqlParameterSource parameters =
        new MapSqlParameterSource().addValue("tripId", tripId).addValue("ownerId", ownerId);
    if (versionId != null) {
      parameters.addValue("versionId", versionId);
    }
    List<RootRow> rows = namedJdbc.query(sql, parameters, (rs, rowNum) -> mapRoot(rs));
    if (rows.size() > 1) {
      throw invalidData();
    }
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<DayRow> readDays(UUID tripId) {
    return jdbc.query(
        """
        select id, day_no, trip_date
        from public.trip_days
        where trip_plan_id = ?
        order by day_no asc, id asc
        """,
        (rs, rowNum) ->
            new DayRow(
                rs.getObject("id", UUID.class),
                requiredInteger(rs, "day_no"),
                requiredDate(rs, "trip_date")),
        tripId);
  }

  private List<ItemRow> readItems(UUID tripId, UUID versionId) {
    return jdbc.query(
        """
        select i.id, i.trip_day_id, i.sequence_no, i.item_type, i.place_id,
               coalesce(i.title, p.name) as projection_title,
               i.planned_start_at, i.planned_end_at, i.stay_minutes,
               i.buffer_after_minutes, i.required, i.memo,
               progress.status as progress_status,
               progress.actual_started_at, progress.actual_arrived_at,
               progress.actual_completed_at, progress.updated_at as progress_updated_at
        from public.trip_items i
        join public.trip_days d
          on d.id = i.trip_day_id and d.trip_plan_id = i.trip_plan_id
        left join public.tour_places p on p.id = i.place_id
        left join public.trip_item_progress progress
          on progress.trip_plan_id = i.trip_plan_id
         and progress.schedule_version_id = i.schedule_version_id
         and progress.trip_item_id = i.id
        where i.trip_plan_id = ? and i.schedule_version_id = ?
        order by d.day_no asc, i.sequence_no asc, i.id asc
        """,
        (rs, rowNum) -> mapItem(rs),
        tripId,
        versionId);
  }

  private List<LegRow> readLegs(UUID tripId, UUID versionId) {
    return jdbc.query(
        """
        select l.id, l.trip_day_id, l.sequence_no, l.from_item_id, l.to_item_id,
               l.transport_mode, l.planned_departure_at, l.planned_arrival_at,
               l.walk_minutes, l.wait_minutes, l.ride_minutes, l.transfer_minutes,
               l.duration_minutes, l.buffer_minutes, l.distance_meters,
               l.estimated_fare, l.risk_score
        from public.trip_legs l
        join public.trip_days d
          on d.id = l.trip_day_id and d.trip_plan_id = l.trip_plan_id
        where l.trip_plan_id = ? and l.schedule_version_id = ?
        order by d.day_no asc, l.sequence_no asc, l.id asc
        """,
        (rs, rowNum) -> mapLeg(rs),
        tripId,
        versionId);
  }

  private ScheduleSnapshot assemble(
      RootRow root,
      List<DayRow> dayRows,
      List<ItemRow> itemRows,
      List<LegRow> legRows,
      Instant responseTime) {
    LinkedHashMap<UUID, DayAssembly> byDay = new LinkedHashMap<>();
    int expectedDayNo = 1;
    for (DayRow day : dayRows) {
      if (day.id() == null
          || day.dayNo() != expectedDayNo++
          || day.date() == null
          || byDay.put(day.id(), new DayAssembly(day)) != null) {
        throw invalidData();
      }
    }
    for (ItemRow item : itemRows) {
      DayAssembly day = byDay.get(item.dayId());
      if (day == null) {
        throw invalidData();
      }
      day.items.add(validateItem(item, day.day.date()));
    }
    for (LegRow leg : legRows) {
      DayAssembly day = byDay.get(leg.dayId());
      if (day == null) {
        throw invalidData();
      }
      day.legs.add(validateLeg(leg, day.day.date()));
    }

    List<ScheduleDaySnapshot> days = new ArrayList<>(byDay.size());
    for (DayAssembly day : byDay.values()) {
      validateAdjacency(day.items, day.legs);
      days.add(
          new ScheduleDaySnapshot(
              day.day.id(), day.day.dayNo(), day.day.date(), day.items, day.legs));
    }
    boolean stale = !fresh(root, responseTime);
    return new ScheduleSnapshot(
        root.tripId(),
        new ScheduleVersionSnapshot(
            root.versionId(),
            root.versionNo(),
            root.status(),
            root.sourceType(),
            root.baseVersionId(),
            root.score(),
            stale),
        days);
  }

  private static ScheduleItemSnapshot validateItem(ItemRow row, LocalDate day) {
    if (row.id() == null
        || row.sequenceNo() < 1
        || !ITEM_TYPES.contains(row.itemType())
        || row.title() == null
        || row.title().isBlank()
        || row.title().length() > 200
        || row.plannedStartAt() == null
        || row.plannedEndAt() == null
        || row.plannedEndAt().isBefore(row.plannedStartAt())
        || !day.equals(row.plannedStartAt().atZone(JEJU).toLocalDate())
        || !day.equals(row.plannedEndAt().atZone(JEJU).toLocalDate())
        || row.stayMinutes() == null
        || row.stayMinutes() < 1
        || row.stayMinutes() > 1440
        || row.bufferAfterMinutes() == null
        || row.bufferAfterMinutes() < 0
        || row.bufferAfterMinutes() > 1440
        || (row.memo() != null && row.memo().length() > 500)) {
      throw invalidData();
    }
    ItemProgressSnapshot progress = null;
    if (row.progressStatus() != null) {
      if (!PROGRESS_STATUSES.contains(row.progressStatus()) || row.progressUpdatedAt() == null) {
        throw invalidData();
      }
      progress =
          new ItemProgressSnapshot(
              row.progressStatus(),
              row.actualStartedAt(),
              row.actualArrivedAt(),
              row.actualCompletedAt(),
              row.progressUpdatedAt());
    }
    return new ScheduleItemSnapshot(
        row.id(),
        row.sequenceNo(),
        row.itemType(),
        row.placeId(),
        row.title(),
        row.plannedStartAt(),
        row.plannedEndAt(),
        row.stayMinutes(),
        row.bufferAfterMinutes(),
        row.required(),
        row.memo(),
        progress);
  }

  private static ScheduleLegSnapshot validateLeg(LegRow row, LocalDate day) {
    if (row.id() == null
        || row.sequenceNo() < 1
        || row.fromItemId() == null
        || row.toItemId() == null
        || !TRANSPORT_MODES.contains(row.transportMode())
        || row.plannedDepartureAt() == null
        || row.plannedArrivalAt() == null
        || row.plannedArrivalAt().isBefore(row.plannedDepartureAt())
        || !day.equals(row.plannedDepartureAt().atZone(JEJU).toLocalDate())
        || !day.equals(row.plannedArrivalAt().atZone(JEJU).toLocalDate())
        || invalidNonNegative(row.walkMinutes())
        || invalidNonNegative(row.waitMinutes())
        || invalidNonNegative(row.rideMinutes())
        || invalidNonNegative(row.transferMinutes())
        || row.durationMinutes() == null
        || row.durationMinutes() < 1
        || invalidNonNegative(row.bufferMinutes())
        || invalidNullableNonNegative(row.distanceMeters())
        || invalidNullableNonNegative(row.estimatedFare())
        || (row.riskScore() != null && (row.riskScore() < 0 || row.riskScore() > 100))) {
      throw invalidData();
    }
    return new ScheduleLegSnapshot(
        row.id(),
        row.sequenceNo(),
        row.fromItemId(),
        row.toItemId(),
        row.transportMode(),
        row.plannedDepartureAt(),
        row.plannedArrivalAt(),
        row.walkMinutes(),
        row.waitMinutes(),
        row.rideMinutes(),
        row.transferMinutes(),
        row.durationMinutes(),
        row.bufferMinutes(),
        row.distanceMeters(),
        row.estimatedFare(),
        row.riskScore());
  }

  private static void validateAdjacency(
      List<ScheduleItemSnapshot> items, List<ScheduleLegSnapshot> legs) {
    if (legs.size() != Math.max(items.size() - 1, 0)) {
      throw invalidData();
    }
    for (int index = 0; index < items.size(); index++) {
      if (items.get(index).sequenceNo() != index + 1) {
        throw invalidData();
      }
    }
    for (int index = 0; index < legs.size(); index++) {
      ScheduleLegSnapshot leg = legs.get(index);
      if (leg.sequenceNo() != index + 1
          || !leg.fromItemId().equals(items.get(index).itemId())
          || !leg.toItemId().equals(items.get(index + 1).itemId())) {
        throw invalidData();
      }
    }
  }

  private static boolean fresh(RootRow root, Instant responseTime) {
    Instant observedAt =
        root.observedPresent()
            ? strictJsonInstant(root.observedType(), root.observedValue())
            : root.factsObservedAt();
    Instant expiresAt = strictJsonInstant(root.expiresType(), root.expiresValue());
    return root.calculatedAt() != null
        && observedAt != null
        && expiresAt != null
        && responseTime != null
        && !observedAt.isAfter(root.calculatedAt())
        && !root.calculatedAt().isAfter(expiresAt)
        && expiresAt.isAfter(responseTime);
  }

  private static void validateRoot(RootRow root) {
    if (root.tripId() == null
        || root.versionId() == null
        || root.versionNo() < 1
        || !VERSION_STATUSES.contains(root.status())
        || !SOURCE_TYPES.contains(root.sourceType())
        || (root.score() != null && (root.score() < 0 || root.score() > 100))) {
      throw invalidData();
    }
  }

  private static RootRow mapRoot(ResultSet rs) throws SQLException {
    return new RootRow(
        rs.getObject("trip_id", UUID.class),
        rs.getObject("version_id", UUID.class),
        nullableInteger(rs, "version_no"),
        rs.getString("version_status"),
        rs.getString("source_type"),
        rs.getObject("base_schedule_version_id", UUID.class),
        nullableInteger(rs, "resulting_score"),
        instant(rs, "freshness_calculated_at"),
        instant(rs, "freshness_facts_observed_at"),
        rs.getBoolean("freshness_observed_present"),
        rs.getString("freshness_observed_type"),
        rs.getString("freshness_observed_value"),
        rs.getString("freshness_expires_type"),
        rs.getString("freshness_expires_value"));
  }

  private static ItemRow mapItem(ResultSet rs) throws SQLException {
    return new ItemRow(
        rs.getObject("id", UUID.class),
        rs.getObject("trip_day_id", UUID.class),
        requiredInteger(rs, "sequence_no"),
        rs.getString("item_type"),
        rs.getObject("place_id", UUID.class),
        rs.getString("projection_title"),
        instant(rs, "planned_start_at"),
        instant(rs, "planned_end_at"),
        nullableInteger(rs, "stay_minutes"),
        nullableInteger(rs, "buffer_after_minutes"),
        rs.getBoolean("required"),
        rs.getString("memo"),
        rs.getString("progress_status"),
        instant(rs, "actual_started_at"),
        instant(rs, "actual_arrived_at"),
        instant(rs, "actual_completed_at"),
        instant(rs, "progress_updated_at"));
  }

  private static LegRow mapLeg(ResultSet rs) throws SQLException {
    return new LegRow(
        rs.getObject("id", UUID.class),
        rs.getObject("trip_day_id", UUID.class),
        requiredInteger(rs, "sequence_no"),
        rs.getObject("from_item_id", UUID.class),
        rs.getObject("to_item_id", UUID.class),
        rs.getString("transport_mode"),
        instant(rs, "planned_departure_at"),
        instant(rs, "planned_arrival_at"),
        nullableInteger(rs, "walk_minutes"),
        nullableInteger(rs, "wait_minutes"),
        nullableInteger(rs, "ride_minutes"),
        nullableInteger(rs, "transfer_minutes"),
        nullableInteger(rs, "duration_minutes"),
        nullableInteger(rs, "buffer_minutes"),
        nullableInteger(rs, "distance_meters"),
        nullableInteger(rs, "estimated_fare"),
        nullableInteger(rs, "risk_score"));
  }

  private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
    return (Integer) rs.getObject(column);
  }

  private static int requiredInteger(ResultSet rs, String column) throws SQLException {
    Integer value = nullableInteger(rs, column);
    if (value == null) {
      throw invalidData();
    }
    return value;
  }

  private static LocalDate requiredDate(ResultSet rs, String column) throws SQLException {
    java.sql.Date value = rs.getDate(column);
    if (value == null) {
      throw invalidData();
    }
    return value.toLocalDate();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Instant strictJsonInstant(String type, String value) {
    if (!"string".equals(type) || value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static boolean invalidNonNegative(Integer value) {
    return value == null || value < 0;
  }

  private static boolean invalidNullableNonNegative(Integer value) {
    return value != null && value < 0;
  }

  private static ScheduleDataIntegrityException invalidData() {
    return new ScheduleDataIntegrityException();
  }

  private record RootRow(
      UUID tripId,
      UUID versionId,
      Integer versionNoValue,
      String status,
      String sourceType,
      UUID baseVersionId,
      Integer score,
      Instant calculatedAt,
      Instant factsObservedAt,
      boolean observedPresent,
      String observedType,
      String observedValue,
      String expiresType,
      String expiresValue) {
    int versionNo() {
      return versionNoValue == null ? 0 : versionNoValue;
    }
  }

  private record DayRow(UUID id, int dayNo, LocalDate date) {}

  private record ItemRow(
      UUID id,
      UUID dayId,
      int sequenceNo,
      String itemType,
      UUID placeId,
      String title,
      Instant plannedStartAt,
      Instant plannedEndAt,
      Integer stayMinutes,
      Integer bufferAfterMinutes,
      boolean required,
      String memo,
      String progressStatus,
      Instant actualStartedAt,
      Instant actualArrivedAt,
      Instant actualCompletedAt,
      Instant progressUpdatedAt) {}

  private record LegRow(
      UUID id,
      UUID dayId,
      int sequenceNo,
      UUID fromItemId,
      UUID toItemId,
      String transportMode,
      Instant plannedDepartureAt,
      Instant plannedArrivalAt,
      Integer walkMinutes,
      Integer waitMinutes,
      Integer rideMinutes,
      Integer transferMinutes,
      Integer durationMinutes,
      Integer bufferMinutes,
      Integer distanceMeters,
      Integer estimatedFare,
      Integer riskScore) {}

  private static final class DayAssembly {
    private final DayRow day;
    private final List<ScheduleItemSnapshot> items = new ArrayList<>();
    private final List<ScheduleLegSnapshot> legs = new ArrayList<>();

    private DayAssembly(DayRow day) {
      this.day = day;
    }
  }

  private static final class ScheduleDataIntegrityException extends RuntimeException {
    private ScheduleDataIntegrityException() {
      super(null, null, false, false);
    }
  }
}
