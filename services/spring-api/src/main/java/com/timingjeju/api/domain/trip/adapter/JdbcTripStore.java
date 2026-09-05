package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.CreateTripRecord;
import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripListCursor;
import com.timingjeju.api.application.trip.TripListSlice;
import com.timingjeju.api.application.trip.TripMutationResult;
import com.timingjeju.api.application.trip.TripScore;
import com.timingjeju.api.application.trip.TripStore;
import com.timingjeju.api.application.trip.TripSummary;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.TripUpdateRecord;
import com.timingjeju.api.global.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.global.trip.TripAggregateMutationCoordinator.LockedTrip;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTripStore implements TripStore {
  private static final String SUMMARY_COLUMNS =
      """
      p.id, p.revision, p.title, p.status, p.start_date, p.end_date, p.timezone, p.user_pace,
      p.active_schedule_version_id, p.created_at, p.updated_at,
      jsonb_typeof(score_run.result_summary -> 'score') as score_type,
      score_run.result_summary ->> 'score' as score_value,
      score_run.id as score_run_id,
      score_run.schedule_version_id as score_schedule_version_id,
      score_run.completed_at as score_calculated_at,
      jsonb_exists(score_run.result_summary, 'observedAt') as score_observed_present,
      jsonb_typeof(score_run.result_summary -> 'observedAt') as score_observed_type,
      score_run.result_summary ->> 'observedAt' as score_observed_value,
      score_run.facts_snapshot_at as score_facts_observed_at,
      jsonb_typeof(score_run.result_summary -> 'expiresAt') as score_expires_type,
      score_run.result_summary ->> 'expiresAt' as score_expires_value
      """;

  private static final String SCORE_JOIN =
      """
      left join lateral (
        select r.id, r.schedule_version_id, r.completed_at, r.facts_snapshot_at, r.result_summary
        from public.compute_runs r
        where r.trip_plan_id = p.id
          and r.schedule_version_id = p.active_schedule_version_id
          and r.run_type = 'feasibility'
          and r.status = 'succeeded'
        order by r.completed_at desc nulls last, r.id desc
        limit 1
      ) score_run on true
      """;

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate namedJdbc;
  private final TripAggregateMutationCoordinator tripMutations;

  @Autowired
  public JdbcTripStore(
      JdbcTemplate jdbc,
      NamedParameterJdbcTemplate namedJdbc,
      TripAggregateMutationCoordinator tripMutations) {
    this.jdbc = jdbc;
    this.namedJdbc = namedJdbc;
    this.tripMutations = tripMutations;
  }

  JdbcTripStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
    this(jdbc, namedJdbc, new TripAggregateMutationCoordinator(jdbc));
  }

  @Override
  @Transactional
  public TripAggregate create(CreateTripRecord record) {
    try {
      jdbc.update(
          """
          insert into public.trip_plans (
            id, user_id, public_token, title, status, start_date, end_date,
            timezone, user_pace, source_mode, data_version, created_at, updated_at
          ) values (?, ?, ?, ?, 'draft', ?, ?, ?, ?, 'live', 'trip-input-v1', ?, ?)
          """,
          record.tripId(),
          record.ownerId(),
          record.publicToken(),
          record.command().title(),
          Date.valueOf(record.command().startDate()),
          Date.valueOf(record.command().endDate()),
          record.command().timezone(),
          record.command().userPace(),
          Timestamp.from(record.createdAt()),
          Timestamp.from(record.createdAt()));

      List<Object[]> modes =
          record.command().transportModes().stream()
              .map(
                  mode ->
                      new Object[] {
                        record.tripId(),
                        mode.mode(),
                        mode.priority(),
                        mode.primary(),
                        Timestamp.from(record.createdAt())
                      })
              .toList();
      jdbc.batchUpdate(
          """
          insert into public.trip_transport_modes (
            trip_plan_id, transport_mode, priority, is_primary, created_at
          ) values (?, ?, ?, ?, ?)
          """,
          modes);

      List<TripDay> days = new ArrayList<>(record.dayIds().size());
      List<Object[]> dayRows = new ArrayList<>(record.dayIds().size());
      for (int index = 0; index < record.dayIds().size(); index++) {
        TripDay day =
            new TripDay(
                record.dayIds().get(index),
                index + 1,
                record.command().startDate().plusDays(index));
        days.add(day);
        dayRows.add(
            new Object[] {
              day.dayId(),
              record.tripId(),
              day.dayNo(),
              Date.valueOf(day.date()),
              Timestamp.from(record.createdAt()),
              Timestamp.from(record.createdAt())
            });
      }
      jdbc.batchUpdate(
          """
          insert into public.trip_days (
            id, trip_plan_id, day_no, trip_date, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?)
          """,
          dayRows);

      return new TripAggregate(
          record.tripId(),
          1,
          record.command().title(),
          "draft",
          record.command().startDate(),
          record.command().endDate(),
          record.command().timezone(),
          record.command().userPace(),
          record.command().transportModes(),
          days,
          null,
          null,
          null,
          record.createdAt(),
          record.createdAt());
    } catch (DataIntegrityViolationException failure) {
      throw TripException.constraintViolation();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TripAggregate> findOwned(UUID ownerId, UUID tripId, Instant responseTime) {
    try {
      return loadOwned(ownerId, tripId, responseTime, false);
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  Optional<TripAggregate> findOwnedDiagnosticForTest(
      UUID ownerId, UUID tripId, Instant responseTime) {
    return loadOwned(ownerId, tripId, responseTime, true);
  }

  private Optional<TripAggregate> loadOwned(
      UUID ownerId, UUID tripId, Instant responseTime, boolean diagnostic) {
    List<TripRow> roots =
        runTripJdbcStage(
            diagnostic,
            TripJdbcStage.ROOT_QUERY,
            () ->
                namedJdbc.query(
                    "select "
                        + SUMMARY_COLUMNS
                        + " from public.trip_plans p "
                        + SCORE_JOIN
                        + " where p.id = :tripId and p.user_id = :ownerId",
                    Map.of("tripId", tripId, "ownerId", ownerId),
                    TRIP_ROW_MAPPER));
    if (roots.isEmpty()) {
      return Optional.empty();
    }
    TripRow root = roots.getFirst();
    List<TripTransportMode> modes =
        runTripJdbcStage(
            diagnostic,
            TripJdbcStage.TRANSPORT_MODES_QUERY,
            () ->
                jdbc.query(
                    """
                    select transport_mode, priority, is_primary
                    from public.trip_transport_modes
                    where trip_plan_id = ?
                    order by priority
                    """,
                    (rs, row) ->
                        new TripTransportMode(
                            rs.getString("transport_mode"),
                            rs.getInt("priority"),
                            rs.getBoolean("is_primary")),
                    tripId));
    List<TripDay> days =
        runTripJdbcStage(
            diagnostic,
            TripJdbcStage.DAYS_QUERY,
            () ->
                jdbc.query(
                    """
                    select id, day_no, trip_date
                    from public.trip_days
                    where trip_plan_id = ?
                    order by day_no
                    """,
                    (rs, row) ->
                        new TripDay(
                            rs.getObject("id", UUID.class),
                            rs.getInt("day_no"),
                            rs.getDate("trip_date").toLocalDate()),
                    tripId));
    return Optional.of(
        runTripJdbcStage(
            diagnostic,
            TripJdbcStage.SCORE_RESOLUTION,
            () -> root.aggregate(modes, days, responseTime)));
  }

  private static <T> T runTripJdbcStage(
      boolean diagnostic, TripJdbcStage stage, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      if (!diagnostic) {
        throw failure;
      }
      String sqlState = "NONE";
      Throwable current = failure;
      while (current != null) {
        if (current instanceof SQLException sqlFailure) {
          sqlState = sqlFailure.getSQLState() == null ? "NONE" : sqlFailure.getSQLState();
          break;
        }
        current = current.getCause();
      }
      throw new AssertionError(
          "TRIP_JDBC_STAGE:%s:%s:%s"
              .formatted(stage, failure.getClass().getSimpleName(), sqlState));
    }
  }

  private enum TripJdbcStage {
    ROOT_QUERY,
    TRANSPORT_MODES_QUERY,
    DAYS_QUERY,
    SCORE_RESOLUTION
  }

  @Override
  @Transactional(readOnly = true)
  public TripListSlice listOwned(
      UUID ownerId, String status, TripListCursor after, int fetchSize, Instant responseTime) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("ownerId", ownerId)
            .addValue("status", status)
            .addValue("fetchSize", fetchSize);
    StringBuilder conditions = new StringBuilder(" where p.user_id = :ownerId");
    if (status != null) {
      conditions.append(" and p.status = :status");
    }
    if (after != null) {
      conditions.append(
          " and (p.updated_at < :afterUpdatedAt or (p.updated_at = :afterUpdatedAt and p.id < :afterTripId))");
      parameters
          .addValue("afterUpdatedAt", Timestamp.from(after.updatedAt()))
          .addValue("afterTripId", after.tripId());
    }
    try {
      List<TripSummary> rows =
          namedJdbc.query(
              "select "
                  + SUMMARY_COLUMNS
                  + " from public.trip_plans p "
                  + SCORE_JOIN
                  + conditions
                  + " order by p.updated_at desc, p.id desc limit :fetchSize",
              parameters,
              (rs, row) -> TRIP_ROW_MAPPER.mapRow(rs, row).summary(responseTime));
      return new TripListSlice(rows);
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public TripMutationResult updateOwned(TripUpdateRecord record) {
    try {
      LockedTrip root = tripMutations.lockOwned(record.ownerId(), record.tripId());
      tripMutations.validateExpected(record.tripId(), record.expected(), root);
      tripMutations.requireMutable(root);

      var command = record.command();
      String title = command.title().present() ? command.title().value() : root.title();
      LocalDate startDate =
          command.startDate().present() ? command.startDate().value() : root.startDate();
      LocalDate endDate = command.endDate().present() ? command.endDate().value() : root.endDate();
      String timezone = command.timezone().present() ? command.timezone().value() : root.timezone();
      String pace = command.userPace().present() ? command.userPace().value() : root.userPace();
      long dayCount = ChronoUnit.DAYS.between(startDate, endDate) + 1;
      if (dayCount < 1 || dayCount > 30 || record.dayIds().size() < dayCount) {
        throw TripException.constraintViolation();
      }

      List<TripTransportMode> existingModes = loadModes(record.tripId());
      List<TripTransportMode> modes =
          command.transportModes().present() ? command.transportModes().value() : existingModes;
      boolean temporalChanged =
          !startDate.equals(root.startDate())
              || !endDate.equals(root.endDate())
              || !timezone.equals(root.timezone());
      boolean plannerPreferenceChanged =
          !pace.equals(root.userPace()) || !modes.equals(existingModes);

      if (temporalChanged && hasScheduleVersion(record.tripId())) {
        throw TripException.regenerationRequired();
      }
      if (temporalChanged && hasCalendarChildOutside(record.tripId(), startDate, endDate)) {
        throw TripException.constraintViolation();
      }

      boolean invalidate = plannerPreferenceChanged;
      if (invalidate && root.activeScheduleVersionId() != null) {
        jdbc.update(
            """
            update public.trip_schedule_versions
            set status = 'superseded'
            where id = ? and trip_plan_id = ? and status = 'active'
            """,
            root.activeScheduleVersionId(),
            record.tripId());
      }

      jdbc.update(
          """
          update public.trip_plans
          set title = ?, start_date = ?, end_date = ?, timezone = ?, user_pace = ?,
              status = ?, active_schedule_version_id = ?, total_score = ?,
              revision = revision + 1, updated_at = ?
          where id = ? and user_id = ?
          """,
          title,
          Date.valueOf(startDate),
          Date.valueOf(endDate),
          timezone,
          pace,
          invalidate ? "draft" : root.status(),
          invalidate ? null : root.activeScheduleVersionId(),
          invalidate ? null : root.totalScore(),
          Timestamp.from(record.updatedAt()),
          record.tripId(),
          record.ownerId());

      if (command.transportModes().present() && !modes.equals(existingModes)) {
        replaceModes(record.tripId(), modes, record.updatedAt());
      }
      if (temporalChanged) {
        rebuildDays(record, startDate, (int) dayCount);
      }

      TripAggregate updated =
          loadOwned(record.ownerId(), record.tripId(), record.updatedAt(), false)
              .orElseThrow(TripException::dataUnavailable);
      String effect = invalidate ? "invalidated" : temporalChanged ? "none" : "maintained";
      return new TripMutationResult(updated, effect, invalidate);
    } catch (DataIntegrityViolationException failure) {
      throw TripException.constraintViolation();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public void deleteOwned(UUID ownerId, UUID tripId) {
    try {
      LockedTrip root = tripMutations.lockOwned(ownerId, tripId);
      if ("live".equals(root.status()) || hasNonTerminalRun(tripId)) {
        throw TripException.deleteConflict();
      }
      if (jdbc.update("delete from public.trip_plans where id = ? and user_id = ?", tripId, ownerId)
          != 1) {
        throw TripException.notFound();
      }
    } catch (DataIntegrityViolationException failure) {
      throw TripException.deleteConflict();
    } catch (DataAccessException failure) {
      throw TripException.dataUnavailable();
    }
  }

  private List<TripTransportMode> loadModes(UUID tripId) {
    return jdbc.query(
        """
        select transport_mode, priority, is_primary
        from public.trip_transport_modes
        where trip_plan_id = ?
        order by priority
        """,
        (rs, row) ->
            new TripTransportMode(
                rs.getString("transport_mode"), rs.getInt("priority"), rs.getBoolean("is_primary")),
        tripId);
  }

  private boolean hasScheduleVersion(UUID tripId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from public.trip_schedule_versions where trip_plan_id = ?)",
            Boolean.class,
            tripId));
  }

  private boolean hasCalendarChildOutside(UUID tripId, LocalDate startDate, LocalDate endDate) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            select
              exists(
                select 1 from public.trip_transport_events event
                where event.trip_plan_id = ?
                  and timezone('Asia/Seoul', event.scheduled_at)::date not between ? and ?
              )
              or exists(
                select 1 from public.trip_accommodations accommodation
                where accommodation.trip_plan_id = ?
                  and (accommodation.check_in_date < ? or accommodation.check_out_date > ?)
              )
            """,
            Boolean.class,
            tripId,
            Date.valueOf(startDate),
            Date.valueOf(endDate),
            tripId,
            Date.valueOf(startDate),
            Date.valueOf(endDate)));
  }

  private void replaceModes(UUID tripId, List<TripTransportMode> modes, Instant updatedAt) {
    jdbc.update("delete from public.trip_transport_modes where trip_plan_id = ?", tripId);
    jdbc.batchUpdate(
        """
        insert into public.trip_transport_modes (
          trip_plan_id, transport_mode, priority, is_primary, created_at
        ) values (?, ?, ?, ?, ?)
        """,
        modes.stream()
            .map(
                mode ->
                    new Object[] {
                      tripId,
                      mode.mode(),
                      mode.priority(),
                      mode.primary(),
                      Timestamp.from(updatedAt)
                    })
            .toList());
  }

  private void rebuildDays(TripUpdateRecord record, LocalDate startDate, int dayCount) {
    jdbc.update("delete from public.trip_days where trip_plan_id = ?", record.tripId());
    List<Object[]> rows = new ArrayList<>(dayCount);
    for (int index = 0; index < dayCount; index++) {
      rows.add(
          new Object[] {
            record.dayIds().get(index),
            record.tripId(),
            index + 1,
            Date.valueOf(startDate.plusDays(index)),
            Timestamp.from(record.updatedAt()),
            Timestamp.from(record.updatedAt())
          });
    }
    jdbc.batchUpdate(
        """
        insert into public.trip_days (
          id, trip_plan_id, day_no, trip_date, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?)
        """,
        rows);
  }

  private boolean hasNonTerminalRun(UUID tripId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            select
              exists(
                select 1 from public.itinerary_generation_runs
                where trip_plan_id = ? and status in ('queued', 'running')
              )
              or exists(
                select 1 from public.compute_runs
                where trip_plan_id = ? and status in ('queued', 'running')
              )
              or exists(
                select 1 from public.schedule_revision_runs
                where trip_plan_id = ? and status in ('queued', 'running')
              )
            """,
            Boolean.class,
            tripId,
            tripId,
            tripId));
  }

  private static final RowMapper<TripRow> TRIP_ROW_MAPPER =
      (rs, row) ->
          new TripRow(
              rs.getObject("id", UUID.class),
              rs.getLong("revision"),
              rs.getString("title"),
              rs.getString("status"),
              rs.getDate("start_date").toLocalDate(),
              rs.getDate("end_date").toLocalDate(),
              rs.getString("timezone"),
              rs.getString("user_pace"),
              rs.getObject("active_schedule_version_id", UUID.class),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant(),
              rs.getObject("score_run_id", UUID.class),
              rs.getObject("score_schedule_version_id", UUID.class),
              instant(rs, "score_calculated_at"),
              rs.getString("score_type"),
              rs.getString("score_value"),
              rs.getBoolean("score_observed_present"),
              rs.getString("score_observed_type"),
              rs.getString("score_observed_value"),
              rs.getString("score_expires_type"),
              rs.getString("score_expires_value"),
              instant(rs, "score_facts_observed_at"));

  static TripScore resolveScore(
      UUID activeScheduleVersionId,
      UUID scoreRunId,
      UUID scoreScheduleVersionId,
      Instant scoreCalculatedAt,
      Instant responseTime,
      String scoreType,
      String scoreValue,
      boolean scoreObservedPresent,
      String scoreObservedType,
      String scoreObservedValue,
      String scoreExpiresType,
      String scoreExpiresValue,
      Instant scoreFactsObservedAt) {
    Integer totalScore = strictJsonInteger(scoreType, scoreValue);
    Instant observedAt =
        scoreObservedPresent
            ? strictJsonInstant(scoreObservedType, scoreObservedValue)
            : scoreFactsObservedAt;
    Instant expiresAt = strictJsonInstant(scoreExpiresType, scoreExpiresValue);
    return TripScore.resolve(
        activeScheduleVersionId,
        totalScore,
        scoreRunId,
        scoreScheduleVersionId,
        scoreCalculatedAt,
        observedAt,
        expiresAt,
        responseTime);
  }

  private static Integer strictJsonInteger(String type, String value) {
    if (!"number".equals(type) || value == null) {
      return null;
    }
    try {
      return new BigDecimal(value).intValueExact();
    } catch (NumberFormatException | ArithmeticException ignored) {
      return null;
    }
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

  private record TripRow(
      UUID tripId,
      long revision,
      String title,
      String status,
      java.time.LocalDate startDate,
      java.time.LocalDate endDate,
      String timezone,
      String userPace,
      UUID activeScheduleVersionId,
      Instant createdAt,
      Instant updatedAt,
      UUID scoreRunId,
      UUID scoreScheduleVersionId,
      Instant scoreCalculatedAt,
      String scoreType,
      String scoreValue,
      boolean scoreObservedPresent,
      String scoreObservedType,
      String scoreObservedValue,
      String scoreExpiresType,
      String scoreExpiresValue,
      Instant scoreFactsObservedAt) {
    TripScore score(Instant responseTime) {
      return resolveScore(
          activeScheduleVersionId,
          scoreRunId,
          scoreScheduleVersionId,
          scoreCalculatedAt,
          responseTime,
          scoreType,
          scoreValue,
          scoreObservedPresent,
          scoreObservedType,
          scoreObservedValue,
          scoreExpiresType,
          scoreExpiresValue,
          scoreFactsObservedAt);
    }

    TripSummary summary(Instant responseTime) {
      TripScore score = score(responseTime);
      return new TripSummary(
          tripId,
          title,
          status,
          startDate,
          endDate,
          timezone,
          activeScheduleVersionId,
          score.totalScore(),
          score.provenance(),
          createdAt,
          updatedAt);
    }

    TripAggregate aggregate(
        List<TripTransportMode> modes, List<TripDay> days, Instant responseTime) {
      TripScore score = score(responseTime);
      return new TripAggregate(
          tripId,
          revision,
          title,
          status,
          startDate,
          endDate,
          timezone,
          userPace,
          modes,
          days,
          activeScheduleVersionId,
          score.totalScore(),
          score.provenance(),
          createdAt,
          updatedAt);
    }
  }
}
