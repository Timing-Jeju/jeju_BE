package com.timingjeju.api.domain.transportevent.adapter;

import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEvent;
import com.timingjeju.api.application.transportevent.TransportEventDeleteRecord;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.TransportEventMutation;
import com.timingjeju.api.application.transportevent.TransportEventStore;
import com.timingjeju.api.application.transportevent.TransportEventUpsertRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTransportEventStore implements TransportEventStore {
  private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
  private static final java.util.Set<String> TERMINAL_STATES =
      java.util.Set.of("completed", "cancelled", "failed");

  private final JdbcTemplate jdbc;

  public JdbcTransportEventStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public TransportEventMutation upsert(TransportEventUpsertRecord record) {
    try {
      MutationRoot root = lockOwned(record.ownerId(), record.tripId());
      validateExpected(
          record.tripId(), record.expected().tripId(), record.expected().revision(), root);
      validateMutable(root);
      validateDate(record.command(), root);
      validatePlace(record.command().terminalPlaceId());
      StoredEvent existing = load(record.tripId(), record.command().eventType());
      if (existing != null && sameCanonical(existing.event(), record.command())) {
        return mutation(record.tripId(), root, existing.event(), false, "maintained");
      }

      if (existing == null) {
        insert(record);
      } else {
        update(record);
      }
      MutationRoot advanced = advanceRoot(record.ownerId(), record.tripId(), root, record.now());
      StoredEvent saved = load(record.tripId(), record.command().eventType());
      if (saved == null) {
        throw TransportEventException.of("TRANSPORT_EVENT_DATA_UNAVAILABLE");
      }
      return mutation(
          record.tripId(),
          advanced,
          saved.event(),
          false,
          advanced.invalidated() ? "invalidated" : "none");
    } catch (TransportEventException failure) {
      throw failure;
    } catch (DataIntegrityViolationException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    } catch (DataAccessException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_DATA_UNAVAILABLE");
    }
  }

  @Override
  @Transactional
  public TransportEventMutation delete(TransportEventDeleteRecord record) {
    try {
      MutationRoot root = lockOwned(record.ownerId(), record.tripId());
      validateExpected(
          record.tripId(), record.expected().tripId(), record.expected().revision(), root);
      validateMutable(root);
      StoredEvent existing = load(record.tripId(), record.eventType());
      if (existing == null) {
        throw TransportEventException.of("TRANSPORT_EVENT_NOT_FOUND");
      }
      if (jdbc.update(
              "delete from public.trip_transport_events where trip_plan_id = ? and event_type = ?",
              record.tripId(),
              record.eventType())
          != 1) {
        throw TransportEventException.of("TRIP_VERSION_CONFLICT");
      }
      MutationRoot advanced = advanceRoot(record.ownerId(), record.tripId(), root, record.now());
      return new TransportEventMutation(
          record.tripId(),
          record.eventType(),
          true,
          null,
          advanced.invalidated() ? "invalidated" : "none",
          advanced.invalidated(),
          advanced.activeScheduleVersionId(),
          advanced.status(),
          advanced.revision(),
          advanced.updatedAt());
    } catch (TransportEventException failure) {
      throw failure;
    } catch (DataIntegrityViolationException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    } catch (DataAccessException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_DATA_UNAVAILABLE");
    }
  }

  private MutationRoot lockOwned(UUID ownerId, UUID tripId) {
    List<MutationRoot> rows =
        jdbc.query(
            """
            select revision,status,start_date,end_date,active_schedule_version_id,updated_at
            from public.trip_plans
            where id = ? and user_id = ?
            for update
            """,
            (rs, row) ->
                new MutationRoot(
                    rs.getLong("revision"),
                    rs.getString("status"),
                    rs.getDate("start_date").toLocalDate(),
                    rs.getDate("end_date").toLocalDate(),
                    rs.getObject("active_schedule_version_id", UUID.class),
                    rs.getTimestamp("updated_at").toInstant(),
                    false),
            tripId,
            ownerId);
    if (rows.isEmpty()) throw TransportEventException.of("TRIP_NOT_FOUND");
    return rows.getFirst();
  }

  private static void validateExpected(
      UUID tripId, UUID expectedTripId, long expectedRevision, MutationRoot root) {
    if (!tripId.equals(expectedTripId) || root.revision() != expectedRevision) {
      throw TransportEventException.of("TRIP_VERSION_CONFLICT");
    }
  }

  private static void validateMutable(MutationRoot root) {
    if (TERMINAL_STATES.contains(root.status())) {
      throw TransportEventException.of("TRIP_TERMINAL_STATE_CONFLICT");
    }
  }

  private static void validateDate(PutTransportEventCommand command, MutationRoot root) {
    LocalDate expected = "arrival".equals(command.eventType()) ? root.startDate() : root.endDate();
    if (!command.scheduledAt().toLocalDate().equals(expected)) {
      throw TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    }
  }

  private void validatePlace(UUID placeId) {
    if (placeId == null) return;
    Boolean available =
        jdbc
            .query(
                """
                select true
                from public.tour_places
                where id = ? and tombstoned_at is null and source_deleted_at is null
                  and stale = false and (stale_at is null or stale_at > now())
                """,
                (rs, row) -> true,
                placeId)
            .stream()
            .findFirst()
            .orElse(false);
    if (!available) throw TransportEventException.of("PLACE_NOT_FOUND");
  }

  private StoredEvent load(UUID tripId, String eventType) {
    return jdbc
        .query(
            """
            select id,event_type,transport_type,terminal_place_id,terminal_name,scheduled_at,
                   transport_number,note,created_at,updated_at
            from public.trip_transport_events
            where trip_plan_id = ? and event_type = ?
            """,
            (rs, row) ->
                new StoredEvent(
                    rs.getObject("id", UUID.class),
                    new TransportEvent(
                        rs.getString("event_type"),
                        rs.getString("transport_type"),
                        rs.getObject("terminal_place_id", UUID.class),
                        rs.getString("terminal_name"),
                        rs.getTimestamp("scheduled_at").toInstant().atOffset(KST_OFFSET),
                        rs.getString("transport_number"),
                        rs.getString("note")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()),
            tripId,
            eventType)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void insert(TransportEventUpsertRecord record) {
    PutTransportEventCommand command = record.command();
    jdbc.update(
        """
        insert into public.trip_transport_events (
          trip_plan_id,event_type,transport_type,terminal_place_id,terminal_name,scheduled_at,
          transport_number,source,note,created_at,updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, 'user_input', ?, ?, ?)
        """,
        record.tripId(),
        command.eventType(),
        command.transportType(),
        command.terminalPlaceId(),
        command.customTerminalName(),
        Timestamp.from(command.scheduledAt().toInstant()),
        command.transportNumber(),
        command.note(),
        Timestamp.from(record.now()),
        Timestamp.from(record.now()));
  }

  private void update(TransportEventUpsertRecord record) {
    PutTransportEventCommand command = record.command();
    int updated =
        jdbc.update(
            """
            update public.trip_transport_events
            set transport_type = ?, terminal_place_id = ?, terminal_name = ?, scheduled_at = ?,
                transport_number = ?, note = ?, source = 'user_input', updated_at = ?
            where trip_plan_id = ? and event_type = ?
            """,
            command.transportType(),
            command.terminalPlaceId(),
            command.customTerminalName(),
            Timestamp.from(command.scheduledAt().toInstant()),
            command.transportNumber(),
            command.note(),
            Timestamp.from(record.now()),
            record.tripId(),
            command.eventType());
    if (updated != 1) throw TransportEventException.of("TRIP_VERSION_CONFLICT");
  }

  private MutationRoot advanceRoot(UUID ownerId, UUID tripId, MutationRoot root, Instant now) {
    boolean invalidate = root.activeScheduleVersionId() != null;
    if (invalidate) {
      if (jdbc.update(
              """
              update public.trip_schedule_versions
              set status = 'superseded'
              where id = ? and trip_plan_id = ? and status = 'active'
              """,
              root.activeScheduleVersionId(),
              tripId)
          != 1) {
        throw TransportEventException.of("TRIP_VERSION_CONFLICT");
      }
    }
    int updated =
        jdbc.update(
            """
            update public.trip_plans
            set revision = revision + 1, updated_at = ?,
                status = case when ? then 'draft' else status end,
                active_schedule_version_id = case when ? then null else active_schedule_version_id end,
                total_score = case when ? then null else total_score end
            where id = ? and user_id = ? and revision = ?
            """,
            Timestamp.from(now),
            invalidate,
            invalidate,
            invalidate,
            tripId,
            ownerId,
            root.revision());
    if (updated != 1) throw TransportEventException.of("TRIP_VERSION_CONFLICT");
    return new MutationRoot(
        root.revision() + 1,
        invalidate ? "draft" : root.status(),
        root.startDate(),
        root.endDate(),
        invalidate ? null : root.activeScheduleVersionId(),
        now,
        invalidate);
  }

  private static TransportEventMutation mutation(
      UUID tripId, MutationRoot root, TransportEvent event, boolean deleted, String effect) {
    return new TransportEventMutation(
        tripId,
        event.eventType(),
        deleted,
        event,
        effect,
        root.invalidated(),
        root.activeScheduleVersionId(),
        root.status(),
        root.revision(),
        root.updatedAt());
  }

  private static boolean sameCanonical(TransportEvent event, PutTransportEventCommand command) {
    return event.eventType().equals(command.eventType())
        && event.transportType().equals(command.transportType())
        && Objects.equals(event.terminalPlaceId(), command.terminalPlaceId())
        && Objects.equals(event.customTerminalName(), command.customTerminalName())
        && event.scheduledAt().toInstant().equals(command.scheduledAt().toInstant())
        && Objects.equals(event.transportNumber(), command.transportNumber())
        && Objects.equals(event.note(), command.note());
  }

  private record MutationRoot(
      long revision,
      String status,
      LocalDate startDate,
      LocalDate endDate,
      UUID activeScheduleVersionId,
      Instant updatedAt,
      boolean invalidated) {}

  private record StoredEvent(UUID id, TransportEvent event, Instant createdAt, Instant updatedAt) {}
}
