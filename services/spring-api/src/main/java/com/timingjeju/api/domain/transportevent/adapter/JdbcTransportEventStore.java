package com.timingjeju.api.domain.transportevent.adapter;

import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEvent;
import com.timingjeju.api.application.transportevent.TransportEventDeleteRecord;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.TransportEventMutation;
import com.timingjeju.api.application.transportevent.TransportEventStore;
import com.timingjeju.api.application.transportevent.TransportEventUpsertRecord;
import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationCoordinator;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransportEventStore implements TransportEventStore {
  private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
  private final JdbcTemplate jdbc;
  private final TripAggregateMutationCoordinator mutations;

  public JdbcTransportEventStore(JdbcTemplate jdbc, TripAggregateMutationCoordinator mutations) {
    this.jdbc = jdbc;
    this.mutations = mutations;
  }

  @Override
  public TransportEventMutation upsert(TransportEventUpsertRecord record) {
    try {
      validateExpectedTrip(record.tripId(), record.expected().tripId());
      TripAggregateMutationCommit<MutationPayload> commit =
          mutations.execute(
              record.ownerId(),
              record.tripId(),
              record.expected().revision(),
              record.now(),
              state -> upsertPlan(record, state));
      return mutation(record.tripId(), commit);
    } catch (TransportEventException failure) {
      throw failure;
    } catch (TripException failure) {
      throw translate(failure);
    }
  }

  @Override
  public TransportEventMutation delete(TransportEventDeleteRecord record) {
    try {
      validateExpectedTrip(record.tripId(), record.expected().tripId());
      TripAggregateMutationCommit<MutationPayload> commit =
          mutations.execute(
              record.ownerId(),
              record.tripId(),
              record.expected().revision(),
              record.now(),
              state -> deletePlan(record, state));
      return mutation(record.tripId(), commit);
    } catch (TransportEventException failure) {
      throw failure;
    } catch (TripException failure) {
      throw translate(failure);
    }
  }

  private TripAggregateMutationPlan<MutationPayload> upsertPlan(
      TransportEventUpsertRecord record, TripAggregateMutationState state) {
    return transportBoundary(
        () -> {
          validateDate(record.command(), state);
          validatePlace(record.command().terminalPlaceId());
          StoredEvent existing = load(record.tripId(), record.command().eventType());
          if (existing != null && sameCanonical(existing.event(), record.command())) {
            return TripAggregateMutationPlan.noChange(
                new MutationPayload(
                    record.command().eventType(), existing.event(), false, existing.updatedAt()));
          }
          Runnable write = existing == null ? () -> insert(record) : () -> update(record);
          MutationPayload payload =
              new MutationPayload(
                  record.command().eventType(), event(record.command()), false, record.now());
          return plan(state, () -> transportBoundary(write), payload);
        });
  }

  private TripAggregateMutationPlan<MutationPayload> deletePlan(
      TransportEventDeleteRecord record, TripAggregateMutationState state) {
    return transportBoundary(
        () -> {
          if (load(record.tripId(), record.eventType()) == null) {
            throw TransportEventException.of("TRANSPORT_EVENT_NOT_FOUND");
          }
          Runnable write =
              () -> {
                if (jdbc.update(
                        "delete from public.trip_transport_events where trip_plan_id = ? and event_type = ?",
                        record.tripId(),
                        record.eventType())
                    != 1) throw TransportEventException.of("TRIP_VERSION_CONFLICT");
              };
          return plan(
              state, write, new MutationPayload(record.eventType(), null, true, record.now()));
        });
  }

  private static TripAggregateMutationPlan<MutationPayload> plan(
      TripAggregateMutationState state, Runnable write, MutationPayload payload) {
    var effect = (com.timingjeju.api.application.trip.TripAggregateMutationEffect) write::run;
    return state.activeScheduleVersionId() == null
        ? TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), effect, payload)
        : TripAggregateMutationPlan.invalidate(TripRootPatch.unchanged(), effect, payload);
  }

  private static void validateDate(
      PutTransportEventCommand command, TripAggregateMutationState root) {
    var expected = "arrival".equals(command.eventType()) ? root.startDate() : root.endDate();
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

  private static TransportEvent event(PutTransportEventCommand command) {
    return new TransportEvent(
        command.eventType(),
        command.transportType(),
        command.terminalPlaceId(),
        command.customTerminalName(),
        command.scheduledAt(),
        command.transportNumber(),
        command.note());
  }

  private static TransportEventMutation mutation(
      UUID tripId, TripAggregateMutationCommit<MutationPayload> commit) {
    MutationPayload payload = commit.payload();
    return new TransportEventMutation(
        tripId,
        payload.eventType(),
        payload.deleted(),
        payload.event(),
        "none".equals(commit.scheduleEffect()) ? "maintained" : commit.scheduleEffect(),
        commit.regenerationRequired(),
        commit.activeScheduleVersionId(),
        commit.status(),
        commit.revision(),
        payload.updatedAt());
  }

  private static void validateExpectedTrip(UUID tripId, UUID expectedTripId) {
    if (!tripId.equals(expectedTripId)) {
      throw TransportEventException.of("TRIP_VERSION_CONFLICT");
    }
  }

  private static TransportEventException translate(TripException failure) {
    return switch (failure.code()) {
      case "TRIP_NOT_FOUND", "TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT" ->
          TransportEventException.of(failure.code());
      case "TRIP_CONSTRAINT_VIOLATION" ->
          TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
      default -> TransportEventException.of("TRANSPORT_EVENT_DATA_UNAVAILABLE");
    };
  }

  private static <T> T transportBoundary(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (TransportEventException failure) {
      throw failure;
    } catch (DataIntegrityViolationException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    } catch (DataAccessException failure) {
      throw TransportEventException.of("TRANSPORT_EVENT_DATA_UNAVAILABLE");
    }
  }

  private static void transportBoundary(Runnable operation) {
    transportBoundary(
        () -> {
          operation.run();
          return null;
        });
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

  private record MutationPayload(
      String eventType, TransportEvent event, boolean deleted, Instant updatedAt) {}

  private record StoredEvent(UUID id, TransportEvent event, Instant createdAt, Instant updatedAt) {}
}
