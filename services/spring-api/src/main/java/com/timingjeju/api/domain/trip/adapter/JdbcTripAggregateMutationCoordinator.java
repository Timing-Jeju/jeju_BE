package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationOperation;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripRootPatch;
import com.timingjeju.api.application.trip.TripScheduleEffect;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcTripAggregateMutationCoordinator implements TripAggregateMutationCoordinator {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  public JdbcTripAggregateMutationCoordinator(
      JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
  }

  @Override
  @Transactional
  public <T> TripAggregateMutationCommit<T> execute(
      UUID ownerId,
      UUID tripId,
      long expectedRevision,
      Instant updatedAt,
      TripAggregateMutationOperation<T> operation) {
    try {
      List<TripAggregateMutationState> roots =
          namedJdbc.query(
              """
              select p.status, p.start_date, p.end_date, p.timezone,
                     p.active_schedule_version_id, p.revision,
                     exists (
                       select 1 from public.trip_schedule_versions v
                       where v.trip_plan_id = p.id
                     ) as has_schedule_version
              from public.trip_plans p
              where p.id = :tripId and p.user_id = :ownerId
              for update
              """,
              Map.of("tripId", tripId, "ownerId", ownerId),
              (rs, row) ->
                  new TripAggregateMutationState(
                      rs.getString("status"),
                      rs.getDate("start_date").toLocalDate(),
                      rs.getDate("end_date").toLocalDate(),
                      rs.getString("timezone"),
                      rs.getObject("active_schedule_version_id", UUID.class),
                      rs.getLong("revision"),
                      rs.getBoolean("has_schedule_version")));
      if (roots.isEmpty()) {
        throw TripException.notFound();
      }
      TripAggregateMutationState state = roots.getFirst();
      if (state.revision() != expectedRevision) {
        throw TripException.versionConflict();
      }
      if (List.of("completed", "cancelled", "failed").contains(state.status())) {
        throw TripException.terminalStateConflict();
      }

      TripAggregateMutationPlan<T> plan = operation.apply(state);
      if (plan.scheduleEffect() == TripScheduleEffect.NONE) {
        return new TripAggregateMutationCommit<>(
            plan.payload(),
            state.revision(),
            state.status(),
            state.activeScheduleVersionId(),
            "none",
            false,
            TripEntityTag.strong(tripId, state.revision()));
      }
      plan.beforeRootEffect().apply();
      boolean invalidated = plan.scheduleEffect() == TripScheduleEffect.INVALIDATE;
      if (invalidated && state.activeScheduleVersionId() != null) {
        jdbc.update(
            """
            update public.trip_schedule_versions set status = 'superseded'
            where id = ? and trip_plan_id = ? and status = 'active'
            """,
            state.activeScheduleVersionId(),
            tripId);
      }
      TripRootPatch root = plan.rootPatch();
      MapSqlParameterSource parameters =
          new MapSqlParameterSource()
              .addValue("tripId", tripId)
              .addValue("ownerId", ownerId)
              .addValue("expectedRevision", expectedRevision)
              .addValue("title", root.title())
              .addValue("startDate", date(root.startDate()))
              .addValue("endDate", date(root.endDate()))
              .addValue("timezone", root.timezone())
              .addValue("userPace", root.userPace())
              .addValue("invalidate", invalidated)
              .addValue("updatedAt", Timestamp.from(updatedAt));
      int updated =
          namedJdbc.update(
              """
              update public.trip_plans
              set title = coalesce(:title, title),
                  start_date = coalesce(:startDate, start_date),
                  end_date = coalesce(:endDate, end_date),
                  timezone = coalesce(:timezone, timezone),
                  user_pace = coalesce(:userPace, user_pace),
                  active_schedule_version_id = case when :invalidate then null else active_schedule_version_id end,
                  total_score = case when :invalidate then null else total_score end,
                  status = case when :invalidate then 'draft' else status end,
                  updated_at = :updatedAt,
                  revision = revision + 1
              where id = :tripId and user_id = :ownerId and revision = :expectedRevision
              """,
              parameters);
      if (updated != 1) {
        throw TripException.versionConflict();
      }
      plan.effect().apply();
      long committedRevision = expectedRevision + 1;
      return new TripAggregateMutationCommit<>(
          plan.payload(),
          committedRevision,
          invalidated ? "draft" : state.status(),
          invalidated ? null : state.activeScheduleVersionId(),
          invalidated ? "invalidated" : "maintained",
          invalidated,
          TripEntityTag.strong(tripId, committedRevision));
    } catch (DataIntegrityViolationException failure) {
      throw TripException.constraintViolation();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  private static Date date(java.time.LocalDate value) {
    return value == null ? null : Date.valueOf(value);
  }
}
