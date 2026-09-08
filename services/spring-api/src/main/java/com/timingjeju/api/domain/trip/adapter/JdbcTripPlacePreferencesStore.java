package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationEffect;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.TripPlacePreferencesStore;
import com.timingjeju.api.application.trip.TripPlacePreferencesUpdate;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTripPlacePreferencesStore implements TripPlacePreferencesStore {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;
  private final TripAggregateMutationCoordinator mutations;

  public JdbcTripPlacePreferencesStore(
      JdbcTemplate jdbc,
      NamedParameterJdbcTemplate namedJdbc,
      TripAggregateMutationCoordinator mutations) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
    this.mutations = mutations;
  }

  @Override
  public TripPlacePreferencesMutation replaceOwned(TripPlacePreferencesUpdate update) {
    Objects.requireNonNull(update);
    var expected = TripEntityTag.parse(update.expectedEtag());
    if (!update.tripId().equals(expected.tripId())) {
      throw TripException.versionConflict();
    }
    try {
      TripAggregateMutationCommit<PreferenceCommitPayload> commit =
          mutations.executeMonotonic(
              update.ownerId(),
              update.tripId(),
              expected.revision(),
              update.updatedAt(),
              (state, committedAt) -> {
                validateTargetDays(state.startDate(), state.endDate(), update.preferences());
                lockOwnedSavedPlaces(update.ownerId(), update.preferences());
                List<TripPlacePreference> current = loadPreferences(update.tripId());
                if (current.equals(update.preferences())) {
                  return TripAggregateMutationPlan.noChange(
                      new PreferenceCommitPayload(
                          current,
                          "maintained",
                          loadRootUpdatedAt(update.ownerId(), update.tripId())));
                }

                var payload =
                    new PreferenceCommitPayload(
                        update.preferences(),
                        state.activeScheduleVersionId() == null ? "none" : "invalidated",
                        committedAt);
                TripAggregateMutationEffect effect =
                    () -> replacePreferences(update.tripId(), update.preferences(), committedAt);
                return state.activeScheduleVersionId() == null
                    ? TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), effect, payload)
                    : TripAggregateMutationPlan.invalidate(
                        TripRootPatch.unchanged(), effect, payload);
              });
      PreferenceCommitPayload payload = commit.payload();
      return new TripPlacePreferencesMutation(
          update.tripId(),
          payload.scheduleEffect(),
          "invalidated".equals(payload.scheduleEffect()),
          commit.activeScheduleVersionId(),
          commit.status(),
          payload.updatedAt(),
          commit.revision(),
          commit.etag(),
          payload.preferences());
    } catch (TripException failure) {
      if ("TRIP_CONSTRAINT_VIOLATION".equals(failure.code())) {
        throw TripException.placePreferenceConstraintViolation();
      }
      throw failure;
    }
  }

  private static void validateTargetDays(
      LocalDate startDate, LocalDate endDate, List<TripPlacePreference> preferences) {
    long tripDayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1;
    if (preferences.stream()
        .map(TripPlacePreference::targetDayNo)
        .filter(Objects::nonNull)
        .anyMatch(day -> day > tripDayCount)) {
      throw TripException.placePreferenceConstraintViolation();
    }
  }

  private void lockOwnedSavedPlaces(UUID ownerId, List<TripPlacePreference> preferences) {
    Set<UUID> requested = new LinkedHashSet<>();
    preferences.forEach(item -> requested.add(item.placeId()));
    if (requested.isEmpty()) {
      return;
    }
    List<UUID> found =
        namedJdbc.queryForList(
            """
            select s.place_id
            from public.saved_places s
            join public.tour_places p on p.id=s.place_id
            where s.user_id=:ownerId and s.place_id in (:placeIds)
              and p.stale=false and (p.stale_at is null or p.stale_at > now())
              and p.tombstoned_at is null and p.source_deleted_at is null
            for share of s,p
            """,
            new MapSqlParameterSource()
                .addValue("ownerId", ownerId, Types.OTHER)
                .addValue("placeIds", requested),
            UUID.class);
    if (found.size() != requested.size()) {
      throw TripException.placeNotFound();
    }
  }

  private List<TripPlacePreference> loadPreferences(UUID tripId) {
    return jdbc.query(
        """
        select place_id,preference_type,target_day_no,priority
        from public.trip_place_preferences
        where trip_plan_id=?
        order by priority desc,place_id
        """,
        (rs, row) ->
            new TripPlacePreference(
                rs.getObject("place_id", UUID.class),
                rs.getString("preference_type"),
                rs.getObject("target_day_no", Integer.class),
                rs.getInt("priority")),
        tripId);
  }

  private Instant loadRootUpdatedAt(UUID ownerId, UUID tripId) {
    return namedJdbc.queryForObject(
        """
        select updated_at from public.trip_plans
        where id=:tripId and user_id=:ownerId
        """,
        java.util.Map.of("tripId", tripId, "ownerId", ownerId),
        (rs, row) -> rs.getTimestamp("updated_at").toInstant());
  }

  private void replacePreferences(
      UUID tripId, List<TripPlacePreference> preferences, Instant effectiveAt) {
    jdbc.update("delete from public.trip_place_preferences where trip_plan_id=?", tripId);
    if (preferences.isEmpty()) {
      return;
    }
    SqlParameterSource[] rows =
        preferences.stream()
            .map(
                item ->
                    new MapSqlParameterSource()
                        .addValue("tripId", tripId, Types.OTHER)
                        .addValue("placeId", item.placeId(), Types.OTHER)
                        .addValue("type", item.type())
                        .addValue("targetDayNo", item.targetDayNo())
                        .addValue("priority", item.priority())
                        .addValue("createdAt", Timestamp.from(effectiveAt)))
            .toArray(SqlParameterSource[]::new);
    namedJdbc.batchUpdate(
        """
        insert into public.trip_place_preferences (
          trip_plan_id,place_id,preference_type,target_day_no,priority,source,created_at
        ) values (
          :tripId,:placeId,:type,:targetDayNo,:priority,'saved_place',:createdAt
        )
        """,
        rows);
  }

  private record PreferenceCommitPayload(
      List<TripPlacePreference> preferences, String scheduleEffect, Instant updatedAt) {}
}
