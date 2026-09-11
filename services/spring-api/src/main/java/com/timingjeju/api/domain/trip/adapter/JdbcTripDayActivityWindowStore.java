package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand;
import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripDayActivityWindowStore;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripRootPatch;
import com.timingjeju.api.application.trip.TripStore;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTripDayActivityWindowStore implements TripDayActivityWindowStore {
  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator mutations;
  private final TripStore trips;

  public JdbcTripDayActivityWindowStore(
      JdbcTemplate jdbc, TripAggregateMutationCoordinator mutations, TripStore trips) {
    this.jdbc = jdbc;
    this.mutations = mutations;
    this.trips = trips;
  }

  @Override
  @Transactional
  public TripAggregate replace(
      UUID ownerId,
      UUID tripId,
      long expectedRevision,
      ReplaceTripDayActivityWindowsCommand command,
      Instant updatedAt) {
    Objects.requireNonNull(command);
    mutations.executeMonotonic(
        ownerId,
        tripId,
        expectedRevision,
        updatedAt,
        (state, committedAt) -> {
          var current =
              trips.findOwned(ownerId, tripId, committedAt).orElseThrow(TripException::notFound);
          var supplied =
              command.days().stream()
                  .collect(Collectors.toMap(day -> day.dayId(), Function.identity()));
          var currentIds =
              current.days().stream().map(day -> day.dayId()).collect(Collectors.toSet());
          if (!currentIds.equals(supplied.keySet())) throw TripException.constraintViolation();
          boolean unchanged =
              current.days().stream()
                  .allMatch(
                      day -> {
                        var window = supplied.get(day.dayId());
                        return Objects.equals(day.activityStartTime(), window.startTime())
                            && Objects.equals(day.activityEndTime(), window.endTime());
                      });
          if (unchanged) return TripAggregateMutationPlan.noChange(null);
          var changedDays =
              current.days().stream()
                  .filter(
                      day ->
                          !Objects.equals(
                                  day.activityStartTime(), supplied.get(day.dayId()).startTime())
                              || !Objects.equals(
                                  day.activityEndTime(), supplied.get(day.dayId()).endTime()))
                  .map(day -> supplied.get(day.dayId()))
                  .toList();
          var sealedDays =
              jdbc.queryForList(
                  """
              select distinct item.trip_day_id
              from public.trip_items item
              join public.trip_schedule_versions version
                on version.id=item.schedule_version_id and version.trip_plan_id=item.trip_plan_id
              where item.trip_plan_id=? and version.status in ('candidate','active')
              """,
                  UUID.class,
                  tripId);
          if (changedDays.stream().anyMatch(day -> sealedDays.contains(day.dayId()))) {
            throw TripException.regenerationRequired();
          }
          return TripAggregateMutationPlan.maintain(
              TripRootPatch.unchanged(),
              () -> {
                for (var window : changedDays) {
                  int changed =
                      jdbc.update(
                          "update public.trip_days set start_time=?, end_time=?, updated_at=? where id=? and trip_plan_id=?",
                          Time.valueOf(window.startTime()),
                          Time.valueOf(window.endTime()),
                          Timestamp.from(committedAt),
                          window.dayId(),
                          tripId);
                  if (changed != 1) throw TripException.constraintViolation();
                }
              },
              null);
        });
    return trips.findOwned(ownerId, tripId, updatedAt).orElseThrow(TripException::dataUnavailable);
  }
}
