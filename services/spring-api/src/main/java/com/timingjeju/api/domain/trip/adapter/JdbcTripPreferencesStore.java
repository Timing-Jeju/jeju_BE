package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferences;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripPreferencesStore;
import com.timingjeju.api.application.trip.TripPreferencesUpdate;
import com.timingjeju.api.application.trip.TripTransportMode;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
public class JdbcTripPreferencesStore implements TripPreferencesStore {
  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "failed");

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;

  public JdbcTripPreferencesStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
  }

  @Override
  @Transactional
  public TripPreferencesMutation replaceOwned(TripPreferencesUpdate update) {
    Objects.requireNonNull(update);
    try {
      TripRoot root = lockOwnedRoot(update.ownerId(), update.tripId());
      if (!TripEntityTag.strong(update.tripId(), root.updatedAt()).equals(update.expectedEtag())) {
        throw TripException.versionConflict();
      }
      if (TERMINAL_STATUSES.contains(root.status())) {
        throw TripException.terminalStateConflict();
      }

      TripPreferences current = loadPreferences(update.tripId());
      if (update.preferences().equals(current)) {
        return new TripPreferencesMutation(
            update.tripId(),
            "maintained",
            false,
            root.activeScheduleVersionId(),
            root.status(),
            root.updatedAt(),
            update.preferences());
      }

      lockPlaces(update.preferences());
      Instant effectiveAt = monotonicUpdateAt(root.updatedAt(), update.updatedAt());
      replacePreferences(update.tripId(), update.preferences(), effectiveAt);
      replaceModes(update.tripId(), update.preferences().transportModes(), effectiveAt);

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
              set status=?, active_schedule_version_id=?, updated_at=?
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
      return new TripPreferencesMutation(
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
      throw TripException.preferenceConstraintViolation();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  private TripRoot lockOwnedRoot(UUID ownerId, UUID tripId) {
    List<TripRoot> roots =
        namedJdbc.query(
            """
            select status,active_schedule_version_id,updated_at
            from public.trip_plans
            where id=:tripId and user_id=:ownerId
            for update
            """,
            Map.of("tripId", tripId, "ownerId", ownerId),
            (rs, row) ->
                new TripRoot(
                    rs.getString("status"),
                    rs.getObject("active_schedule_version_id", UUID.class),
                    rs.getTimestamp("updated_at").toInstant()));
    if (roots.isEmpty()) {
      throw TripException.notFound();
    }
    return roots.getFirst();
  }

  private TripPreferences loadPreferences(UUID tripId) {
    List<PreferenceRow> rows =
        jdbc.query(
            """
            select preferred_categories,arrival_region_code,departure_region_code,
                   preferred_region_codes,start_place_id,end_place_id
            from public.trip_preferences
            where trip_plan_id=?
            """,
            (rs, row) ->
                new PreferenceRow(
                    strings(rs.getArray("preferred_categories")),
                    rs.getString("arrival_region_code"),
                    rs.getString("departure_region_code"),
                    strings(rs.getArray("preferred_region_codes")),
                    rs.getObject("start_place_id", UUID.class),
                    rs.getObject("end_place_id", UUID.class)),
            tripId);
    if (rows.isEmpty()) {
      return null;
    }
    List<TripTransportMode> modes =
        jdbc.query(
            """
            select transport_mode,priority,is_primary
            from public.trip_transport_modes
            where trip_plan_id=?
            order by priority
            """,
            (rs, row) ->
                new TripTransportMode(
                    rs.getString("transport_mode"),
                    rs.getInt("priority"),
                    rs.getBoolean("is_primary")),
            tripId);
    return rows.getFirst().toPreferences(modes);
  }

  private void lockPlaces(TripPreferences preferences) {
    Set<UUID> requested = new LinkedHashSet<>();
    if (preferences.startPlaceId() != null) {
      requested.add(preferences.startPlaceId());
    }
    if (preferences.endPlaceId() != null) {
      requested.add(preferences.endPlaceId());
    }
    if (requested.isEmpty()) {
      return;
    }
    List<UUID> found =
        namedJdbc.queryForList(
            "select id from public.tour_places where id in (:ids) for key share",
            new MapSqlParameterSource().addValue("ids", requested),
            UUID.class);
    if (found.size() != requested.size()) {
      throw TripException.placeNotFound();
    }
  }

  private void replacePreferences(UUID tripId, TripPreferences preferences, Instant effectiveAt) {
    MapSqlParameterSource values =
        new MapSqlParameterSource()
            .addValue("tripId", tripId, Types.OTHER)
            .addValue(
                "categories", preferences.preferredCategories().toArray(String[]::new), Types.ARRAY)
            .addValue("arrival", preferences.arrivalRegionCode())
            .addValue("departure", preferences.departureRegionCode())
            .addValue(
                "regions", preferences.preferredRegionCodes().toArray(String[]::new), Types.ARRAY)
            .addValue("startPlaceId", preferences.startPlaceId(), Types.OTHER)
            .addValue("endPlaceId", preferences.endPlaceId(), Types.OTHER)
            .addValue("updatedAt", Timestamp.from(effectiveAt));
    namedJdbc.update(
        """
        insert into public.trip_preferences (
          trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,
          preferred_region_codes,start_place_id,end_place_id,raw_answers,created_at,updated_at
        ) values (
          :tripId,cast(:categories as text[]),:arrival,:departure,cast(:regions as text[]),
          :startPlaceId,:endPlaceId,'{}'::jsonb,:updatedAt,:updatedAt
        )
        on conflict (trip_plan_id) do update set
          preferred_categories=excluded.preferred_categories,
          arrival_region_code=excluded.arrival_region_code,
          departure_region_code=excluded.departure_region_code,
          preferred_region_codes=excluded.preferred_region_codes,
          start_place_id=excluded.start_place_id,
          end_place_id=excluded.end_place_id,
          raw_answers='{}'::jsonb,
          updated_at=excluded.updated_at
        """,
        values);
  }

  private void replaceModes(UUID tripId, List<TripTransportMode> modes, Instant effectiveAt) {
    jdbc.update("delete from public.trip_transport_modes where trip_plan_id=?", tripId);
    SqlParameterSource[] rows =
        modes.stream()
            .map(
                mode ->
                    new MapSqlParameterSource()
                        .addValue("tripId", tripId, Types.OTHER)
                        .addValue("mode", mode.mode())
                        .addValue("priority", mode.priority())
                        .addValue("primary", mode.primary())
                        .addValue("createdAt", Timestamp.from(effectiveAt)))
            .toArray(SqlParameterSource[]::new);
    namedJdbc.batchUpdate(
        """
        insert into public.trip_transport_modes (
          trip_plan_id,transport_mode,priority,is_primary,created_at
        ) values (:tripId,:mode,:priority,:primary,:createdAt)
        """,
        rows);
  }

  private static Instant monotonicUpdateAt(Instant current, Instant requested) {
    Instant canonicalRequested = requested.truncatedTo(ChronoUnit.MICROS);
    return canonicalRequested.isAfter(current)
        ? canonicalRequested
        : current.plus(1, ChronoUnit.MICROS);
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] values) {
      return List.of(values);
    }
    Object[] values = (Object[]) raw;
    List<String> result = new ArrayList<>(values.length);
    for (Object value : values) {
      result.add((String) value);
    }
    return List.copyOf(result);
  }

  private record TripRoot(String status, UUID activeScheduleVersionId, Instant updatedAt) {}

  private record PreferenceRow(
      List<String> preferredCategories,
      String arrivalRegionCode,
      String departureRegionCode,
      List<String> preferredRegionCodes,
      UUID startPlaceId,
      UUID endPlaceId) {
    TripPreferences toPreferences(List<TripTransportMode> modes) {
      return new TripPreferences(
          preferredCategories,
          arrivalRegionCode,
          departureRegionCode,
          preferredRegionCodes,
          startPlaceId,
          endPlaceId,
          modes);
    }
  }
}
