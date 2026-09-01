package com.timingjeju.api.application.transportevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.transportevent.service.TransportEventService;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransportEventServiceTest {
  private static final UUID OWNER = UUID.fromString("47000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("47000000-0000-0000-0000-000000000002");
  private static final UUID PLACE = UUID.fromString("47000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  void PUT은_사용자문자열을_ASCII_trim_NFC하고_expected_revision을_전달한다() {
    CapturingStore store = new CapturingStore();

    TransportEventMutationPayload result =
        service(store)
            .put(
                OWNER,
                TRIP,
                new TripExpectedRevision(TRIP, 7),
                new PutTransportEventCommand(
                    "arrival",
                    "flight",
                    null,
                    "  \u110C\u1166\u110C\u116E항  ",
                    OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
                    "  KE1001  ",
                    "  메모  "));

    assertThat(store.upsert.command().customTerminalName()).isEqualTo("제주항");
    assertThat(store.upsert.command().transportNumber()).isEqualTo("KE1001");
    assertThat(store.upsert.command().note()).isEqualTo("메모");
    assertThat(store.upsert.expected().revision()).isEqualTo(7);
    assertThat(result.etag()).isEqualTo("\"trip-47000000-0000-0000-0000-000000000002-r8\"");
    assertThat(result.deleted()).isFalse();
  }

  @Test
  void PUT은_XOR_enum_offset과_문자열경계를_constraint오류로_거부한다() {
    TransportEventService service = service(new CapturingStore());
    TripExpectedRevision expected = new TripExpectedRevision(TRIP, 1);

    assertCode(
        () -> service.put(OWNER, TRIP, expected, command(PLACE, "제주항")),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () -> service.put(OWNER, TRIP, expected, command((UUID) null, null)),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () -> service.put(OWNER, TRIP, expected, command("invalid", "flight")),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () -> service.put(OWNER, TRIP, expected, command("arrival", "bus")),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () ->
            service.put(
                OWNER,
                TRIP,
                expected,
                new PutTransportEventCommand(
                    "arrival",
                    "flight",
                    PLACE,
                    null,
                    OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                    null,
                    null)),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
    assertCode(
        () -> service.put(OWNER, TRIP, expected, command((UUID) null, "가".repeat(101))),
        "TRANSPORT_EVENT_CONSTRAINT_VIOLATION");
  }

  @Test
  void PUT은_최대길이와_explicit_null을_허용한다() {
    CapturingStore store = new CapturingStore();

    service(store)
        .put(
            OWNER,
            TRIP,
            new TripExpectedRevision(TRIP, 1),
            new PutTransportEventCommand(
                "departure",
                "ferry",
                null,
                "가".repeat(100),
                OffsetDateTime.parse("2026-09-05T19:00:00+09:00"),
                "나".repeat(30),
                "다".repeat(500)));

    assertThat(store.upsert.command().customTerminalName()).hasSize(100);
    assertThat(store.upsert.command().transportNumber()).hasSize(30);
    assertThat(store.upsert.command().note()).hasSize(500);
  }

  @Test
  void DELETE는_owner_trip_eventType_expected를_store에_그대로_전달한다() {
    CapturingStore store = new CapturingStore();

    TransportEventMutationPayload result =
        service(store).delete(OWNER, TRIP, "departure", new TripExpectedRevision(TRIP, 4));

    assertThat(store.delete.ownerId()).isEqualTo(OWNER);
    assertThat(store.delete.tripId()).isEqualTo(TRIP);
    assertThat(store.delete.eventType()).isEqualTo("departure");
    assertThat(store.delete.expected().revision()).isEqualTo(4);
    assertThat(result.deleted()).isTrue();
    assertThat(result.event()).isNull();
  }

  private static TransportEventService service(TransportEventStore store) {
    return new TransportEventService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PutTransportEventCommand command(UUID placeId, String customName) {
    return new PutTransportEventCommand(
        "arrival",
        "flight",
        placeId,
        customName,
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        null,
        null);
  }

  private static PutTransportEventCommand command(String eventType, String transportType) {
    return new PutTransportEventCommand(
        eventType,
        transportType,
        PLACE,
        null,
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        null,
        null);
  }

  private static TransportEventMutation mutation(boolean deleted) {
    TransportEvent event =
        deleted
            ? null
            : new TransportEvent(
                "arrival",
                "flight",
                null,
                "제주항",
                OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
                "KE1001",
                "메모");
    return new TransportEventMutation(
        TRIP, "arrival", deleted, event, "none", false, null, "draft", 8, NOW);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(TransportEventException.class)
        .extracting(failure -> ((TransportEventException) failure).code())
        .isEqualTo(code);
  }

  private static final class CapturingStore implements TransportEventStore {
    private TransportEventUpsertRecord upsert;
    private TransportEventDeleteRecord delete;

    @Override
    public TransportEventMutation upsert(TransportEventUpsertRecord record) {
      upsert = record;
      return mutation(false);
    }

    @Override
    public TransportEventMutation delete(TransportEventDeleteRecord record) {
      delete = record;
      return mutation(true);
    }
  }
}
