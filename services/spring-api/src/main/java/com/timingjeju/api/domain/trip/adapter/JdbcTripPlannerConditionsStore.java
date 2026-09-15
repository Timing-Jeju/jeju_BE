package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlannerConditions;
import com.timingjeju.api.application.trip.TripPlannerConditionsStore;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTripPlannerConditionsStore implements TripPlannerConditionsStore {
  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator mutations;

  public JdbcTripPlannerConditionsStore(
      JdbcTemplate jdbc, TripAggregateMutationCoordinator mutations) {
    this.jdbc = jdbc;
    this.mutations = mutations;
  }

  @Override
  @Transactional
  public TripAggregateMutationCommit<TripPlannerConditions> replace(
      UUID ownerId, UUID tripId, long revision, TripPlannerConditions conditions, Instant now) {
    return mutations.executeMonotonic(
        ownerId,
        tripId,
        revision,
        now,
        (state, committedAt) -> {
          var days =
              jdbc.queryForList(
                  "select id from public.trip_days where trip_plan_id=?", UUID.class, tripId);
          if (conditions.dayAnchors().stream().anyMatch(anchor -> !days.contains(anchor.dayId()))) {
            throw TripException.constraintViolation();
          }
          for (UUID place :
              conditions.dayAnchors().stream()
                  .map(TripPlannerConditions.DayAnchor::lodgingPlaceId)
                  .distinct()
                  .sorted()
                  .toList()) {
            var found =
                jdbc.queryForList(
                    """
            select id from public.tour_places where id=? and stale=false
              and (stale_at is null or stale_at > now())
              and tombstoned_at is null and source_deleted_at is null for share
            """,
                    UUID.class,
                    place);
            if (found.isEmpty()) throw TripException.placeNotFound();
          }
          var current = read(jdbc, tripId);
          if (current.equals(conditions)) return TripAggregateMutationPlan.noChange(conditions);
          com.timingjeju.api.application.trip.TripAggregateMutationEffect effect =
              () -> {
                jdbc.update(
                    connection -> {
                      var statement =
                          connection.prepareStatement(
                              "update public.trip_plans set planner_style_codes=? where id=?");
                      statement.setArray(
                          1, connection.createArrayOf("text", conditions.styleCodes().toArray()));
                      statement.setObject(2, tripId);
                      return statement;
                    });
                jdbc.update(
                    "update public.trip_days set lodging_place_id=null where trip_plan_id=?",
                    tripId);
                for (var anchor : conditions.dayAnchors()) {
                  jdbc.update(
                      "update public.trip_days set lodging_place_id=? where id=? and trip_plan_id=?",
                      anchor.lodgingPlaceId(),
                      anchor.dayId(),
                      tripId);
                }
              };
          return state.activeScheduleVersionId() == null
                  || !affectsActive(tripId, state.activeScheduleVersionId(), current, conditions)
              ? TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), effect, conditions)
              : TripAggregateMutationPlan.invalidate(TripRootPatch.unchanged(), effect, conditions);
        });
  }

  private boolean affectsActive(
      UUID tripId, UUID activeId, TripPlannerConditions before, TripPlannerConditions after) {
    if (!aiStyles(before).equals(aiStyles(after))) return true;
    var previous =
        before.dayAnchors().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    TripPlannerConditions.DayAnchor::dayId,
                    TripPlannerConditions.DayAnchor::lodgingPlaceId));
    var next =
        after.dayAnchors().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    TripPlannerConditions.DayAnchor::dayId,
                    TripPlannerConditions.DayAnchor::lodgingPlaceId));
    // 해당 Day의 종료 숙소는 다음 Day의 출발 기준점이기도 하다.
    var dependencies =
        jdbc.queryForList(
            """
        select d.id from public.trip_days d where d.trip_plan_id=? and exists (
          select 1 from public.trip_items i
          join public.trip_days applied on applied.id=i.trip_day_id and applied.trip_plan_id=i.trip_plan_id
          where i.schedule_version_id=? and i.trip_plan_id=d.trip_plan_id
            and applied.day_no in (d.day_no,d.day_no+1)
        )
        """,
            UUID.class,
            tripId,
            activeId);
    return dependencies.stream()
        .anyMatch(day -> !java.util.Objects.equals(previous.get(day), next.get(day)));
  }

  private static java.util.List<String> aiStyles(TripPlannerConditions value) {
    return value.styleCodes().stream()
        .filter(code -> !code.equals("trendy") && !code.equals("local"))
        .toList();
  }

  static TripPlannerConditions read(JdbcTemplate jdbc, UUID tripId) {
    var styles =
        jdbc.queryForObject(
            "select planner_style_codes from public.trip_plans where id=?",
            (rs, row) -> Arrays.asList((String[]) rs.getArray(1).getArray()),
            tripId);
    var anchors =
        jdbc.query(
            """
        select id, lodging_place_id from public.trip_days
        where trip_plan_id=? and lodging_place_id is not null order by id
        """,
            (rs, row) ->
                new TripPlannerConditions.DayAnchor(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
            tripId);
    return new TripPlannerConditions(anchors, styles);
  }
}
