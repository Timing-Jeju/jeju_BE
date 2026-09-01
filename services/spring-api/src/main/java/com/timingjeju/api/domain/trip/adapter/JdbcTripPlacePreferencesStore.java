package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.TripPlacePreferencesStore;
import com.timingjeju.api.application.trip.TripPlacePreferencesUpdate;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTripPlacePreferencesStore implements TripPlacePreferencesStore {
  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "failed");

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  public JdbcTripPlacePreferencesStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
  }

  @Override
  @Transactional
  public TripPlacePreferencesMutation replaceOwned(TripPlacePreferencesUpdate update) {
    Objects.requireNonNull(update);
    try {
      TripRoot root = lockOwnedRoot(update.ownerId(), update.tripId());
      if (!TripEntityTag.strong(update.tripId(), root.updatedAt()).equals(update.expectedEtag())) {
        throw TripException.versionConflict();
      }
      if (TERMINAL_STATUSES.contains(root.status())) {
        throw TripException.terminalStateConflict();
      }
      validateTargetDays(root, update.preferences());
      lockOwnedSavedPlaces(update.ownerId(), update.preferences());

      List<TripPlacePreference> current = loadPreferences(update.tripId());
      if (current.equals(update.preferences())) {
        return result(root, update.tripId(), "maintained", false, current);
      }

      Instant effectiveAt = monotonicUpdateAt(root.updatedAt(), update.updatedAt());
      replacePreferences(update.tripId(), update.preferences(), effectiveAt);

      boolean invalidated = root.activeScheduleVersionId() != null;
      String status = root.status();
      UUID activeVersion = root.activeScheduleVersionId();
      if (invalidated) {
        int superseded =
            jdbc.update(
                """
                update public.trip_schedule_versions
                set status='superseded'
                where id=? and trip_plan_id=? and status='active'
                """,
                activeVersion,
                update.tripId());
        if (superseded != 1) {
          throw TripException.dataUnavailable();
        }
        status = "draft";
        activeVersion = null;
      }
      int updated =
          jdbc.update(
              """
              update public.trip_plans
              set status=?,active_schedule_version_id=?,updated_at=?
              where id=? and user_id=?
              """,
              status,
              activeVersion,
              Timestamp.from(effectiveAt),
              update.tripId(),
              update.ownerId());
      if (updated != 1) {
        throw TripException.versionConflict();
      }
      return new TripPlacePreferencesMutation(
          update.tripId(),
          invalidated ? "invalidated" : "none",
          invalidated,
          activeVersion,
          status,
          effectiveAt,
          update.preferences());
    } catch (TripException failure) {
      throw failure;
    } catch (DataIntegrityViolationException failure) {
      throw TripException.placePreferenceConstraintViolation();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  private TripRoot lockOwnedRoot(UUID ownerId, UUID tripId) {
    List<TripRoot> roots =
        namedJdbc.query(
            """
            select status,active_schedule_version_id,start_date,end_date,updated_at
            from public.trip_plans
            where id=:tripId and user_id=:ownerId
            for update
            """,
            Map.of("tripId", tripId, "ownerId", ownerId),
            (rs, row) ->
                new TripRoot(
                    rs.getString("status"),
                    rs.getObject("active_schedule_version_id", UUID.class),
                    rs.getObject("start_date", LocalDate.class),
                    rs.getObject("end_date", LocalDate.class),
                    rs.getTimestamp("updated_at").toInstant()));
    if (roots.isEmpty()) {
      throw TripException.notFound();
    }
    return roots.getFirst();
  }

  private static void validateTargetDays(TripRoot root, List<TripPlacePreference> preferences) {
    long tripDayCount = ChronoUnit.DAYS.between(root.startDate(), root.endDate()) + 1;
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
              and p.stale=false and p.stale_at is null
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

  private static TripPlacePreferencesMutation result(
      TripRoot root,
      UUID tripId,
      String scheduleEffect,
      boolean regenerationRequired,
      List<TripPlacePreference> preferences) {
    return new TripPlacePreferencesMutation(
        tripId,
        scheduleEffect,
        regenerationRequired,
        root.activeScheduleVersionId(),
        root.status(),
        root.updatedAt(),
        preferences);
  }

  private static Instant monotonicUpdateAt(Instant current, Instant requested) {
    Instant canonicalRequested = requested.truncatedTo(ChronoUnit.MICROS);
    return canonicalRequested.isAfter(current)
        ? canonicalRequested
        : current.plus(1, ChronoUnit.MICROS);
  }

  private record TripRoot(
      String status,
      UUID activeScheduleVersionId,
      LocalDate startDate,
      LocalDate endDate,
      Instant updatedAt) {}
}
