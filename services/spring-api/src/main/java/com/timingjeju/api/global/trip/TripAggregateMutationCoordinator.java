package com.timingjeju.api.global.trip;

import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class TripAggregateMutationCoordinator {
  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "failed");
  private final JdbcTemplate jdbc;

  public TripAggregateMutationCoordinator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public LockedTrip lockOwned(UUID ownerId, UUID tripId) {
    List<LockedTrip> rows =
        jdbc.query(
            """
            select revision, title, status, start_date, end_date, timezone, user_pace,
                   active_schedule_version_id, total_score
            from public.trip_plans
            where id = ? and user_id = ?
            for update
            """,
            (rs, row) ->
                new LockedTrip(
                    rs.getLong("revision"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getDate("start_date").toLocalDate(),
                    rs.getDate("end_date").toLocalDate(),
                    rs.getString("timezone"),
                    rs.getString("user_pace"),
                    rs.getObject("active_schedule_version_id", UUID.class),
                    rs.getObject("total_score", Integer.class)),
            tripId,
            ownerId);
    if (rows.isEmpty()) {
      throw TripException.notFound();
    }
    return rows.getFirst();
  }

  public void validateExpected(UUID tripId, TripExpectedRevision expected, LockedTrip locked) {
    if (!expected.tripId().equals(tripId) || expected.revision() != locked.revision()) {
      throw TripException.versionConflict();
    }
  }

  public void requireMutable(LockedTrip locked) {
    if (TERMINAL_STATUSES.contains(locked.status())) {
      throw TripException.terminalStateConflict();
    }
  }

  public long advanceSchedulePointer(
      UUID ownerId, UUID tripId, LockedTrip locked, UUID scheduleVersionId, Instant updatedAt) {
    if (jdbc.update(
            """
            update public.trip_plans
            set active_schedule_version_id=?, revision=revision+1, stale=true, updated_at=?
            where id=? and user_id=? and revision=? and active_schedule_version_id=?
            """,
            scheduleVersionId,
            Timestamp.from(updatedAt),
            tripId,
            ownerId,
            locked.revision(),
            locked.activeScheduleVersionId())
        != 1) {
      throw TripException.versionConflict();
    }
    return locked.revision() + 1;
  }

  public record LockedTrip(
      long revision,
      String title,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      String timezone,
      String userPace,
      UUID activeScheduleVersionId,
      Integer totalScore) {}
}
