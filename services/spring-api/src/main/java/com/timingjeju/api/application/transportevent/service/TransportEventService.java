package com.timingjeju.api.application.transportevent.service;

import com.timingjeju.api.application.transportevent.PutTransportEventCommand;
import com.timingjeju.api.application.transportevent.TransportEventDeleteRecord;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.application.transportevent.TransportEventMutationPayload;
import com.timingjeju.api.application.transportevent.TransportEventStore;
import com.timingjeju.api.application.transportevent.TransportEventUpsertRecord;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.text.Normalizer;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TransportEventService {
  private static final Set<String> EVENT_TYPES = Set.of("arrival", "departure");
  private static final Set<String> TRANSPORT_TYPES = Set.of("flight", "ferry");
  private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

  private final TransportEventStore store;
  private final Clock clock;

  public TransportEventService(TransportEventStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public TransportEventMutationPayload put(
      UUID ownerId, UUID tripId, TripExpectedRevision expected, PutTransportEventCommand command) {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expected);
    PutTransportEventCommand canonical = canonicalize(Objects.requireNonNull(command));
    validate(canonical);
    return TransportEventMutationPayload.from(
        store.upsert(
            new TransportEventUpsertRecord(ownerId, tripId, expected, canonical, clock.instant())));
  }

  public TransportEventMutationPayload delete(
      UUID ownerId, UUID tripId, String eventType, TripExpectedRevision expected) {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expected);
    if (!EVENT_TYPES.contains(eventType)) {
      throw TransportEventException.invalidRequest();
    }
    return TransportEventMutationPayload.from(
        store.delete(
            new TransportEventDeleteRecord(ownerId, tripId, eventType, expected, clock.instant())));
  }

  private static PutTransportEventCommand canonicalize(PutTransportEventCommand command) {
    return new PutTransportEventCommand(
        command.eventType(),
        command.transportType(),
        command.terminalPlaceId(),
        canonicalText(command.customTerminalName()),
        command.scheduledAt() == null
            ? null
            : command.scheduledAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        canonicalText(command.transportNumber()),
        canonicalText(command.note()));
  }

  private static String canonicalText(String value) {
    if (value == null) return null;
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) <= 0x20) start++;
    while (end > start && value.charAt(end - 1) <= 0x20) end--;
    return Normalizer.normalize(value.substring(start, end), Normalizer.Form.NFC);
  }

  private static void validate(PutTransportEventCommand command) {
    if (!EVENT_TYPES.contains(command.eventType())
        || !TRANSPORT_TYPES.contains(command.transportType())
        || (command.terminalPlaceId() == null) == (command.customTerminalName() == null)
        || command.scheduledAt() == null
        || !command.scheduledAt().getOffset().equals(KST_OFFSET)
        || !validText(command.customTerminalName(), 100)
        || !validText(command.transportNumber(), 30)
        || !validText(command.note(), 500)) {
      throw TransportEventException.of("TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    }
  }

  private static boolean validText(String value, int maxCodePoints) {
    return value == null
        || (!value.isEmpty()
            && value.codePointCount(0, value.length()) <= maxCodePoints
            && Normalizer.isNormalized(value, Normalizer.Form.NFC)
            && value.chars().noneMatch(Character::isISOControl));
  }
}
