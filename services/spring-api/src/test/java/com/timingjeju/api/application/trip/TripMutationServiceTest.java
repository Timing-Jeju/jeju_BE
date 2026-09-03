package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.service.TripService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripMutationServiceTest {
  private static final UUID OWNER = UUID.fromString("45000000-0000-0000-0000-000000000011");
  private static final UUID TRIP = UUID.fromString("45000000-0000-0000-0000-000000000012");
  private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);

  @Test
  void update는_presence와_정규화값_revision_30개_day_id를_store에_한번_전달한다() {
    CapturingStore store = new CapturingStore();
    TripService service = service(store);
    TripExpectedRevision expected = new TripExpectedRevision(TRIP, 7);

    TripMutationResult result =
        service.update(
            USER,
            TRIP,
            expected,
            new PatchTripCommand(
                TripPatchValue.present("  \u110C\u1166\u110C\u116E 가족 여행  "),
                TripPatchValue.omitted(),
                TripPatchValue.omitted(),
                TripPatchValue.omitted(),
                TripPatchValue.present("slow"),
                TripPatchValue.omitted()));

    assertThat(result.scheduleEffect()).isEqualTo("maintained");
    assertThat(store.updated.ownerId()).isEqualTo(OWNER);
    assertThat(store.updated.tripId()).isEqualTo(TRIP);
    assertThat(store.updated.expected()).isEqualTo(expected);
    assertThat(store.updated.command().title().value()).isEqualTo("제주 가족 여행");
    assertThat(store.updated.dayIds()).hasSize(30).doesNotHaveDuplicates();
    assertThat(store.updated.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void update는_empty_blank_null_semantic과_교통_우선순위_오류를_store전에_거부한다() {
    CapturingStore store = new CapturingStore();
    TripService service = service(store);
    TripExpectedRevision expected = new TripExpectedRevision(TRIP, 1);

    assertUpdateCode(service, expected, PatchTripCommand.empty(), "INVALID_REQUEST");
    assertUpdateCode(
        service,
        expected,
        new PatchTripCommand(
            TripPatchValue.present(" "),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted()),
        "INVALID_REQUEST");
    assertUpdateCode(
        service,
        expected,
        new PatchTripCommand(
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.omitted(),
            TripPatchValue.present(
                List.of(
                    new TripTransportMode("taxi", 1, false),
                    new TripTransportMode("public_transit", 2, true)))),
        "TRIP_CONSTRAINT_VIOLATION");
    assertThat(store.updated).isNull();
  }

  @Test
  void delete는_canonical_owner와_trip만_store에_전달한다() {
    CapturingStore store = new CapturingStore();

    service(store).delete(USER, TRIP);

    assertThat(store.deletedOwner).isEqualTo(OWNER);
    assertThat(store.deletedTrip).isEqualTo(TRIP);
  }

  private static void assertUpdateCode(
      TripService service,
      TripExpectedRevision expected,
      PatchTripCommand command,
      String expectedCode) {
    assertThatThrownBy(() -> service.update(USER, TRIP, expected, command))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(expectedCode);
  }

  private static TripService service(TripStore store) {
    return new TripService(
        mock(CurrentUserProvisioningService.class),
        store,
        new SequentialIds(),
        CursorCodec.hmacSha256("test-only-trip-cursor-key-32-bytes"),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static TripAggregate aggregate(long revision) {
    return new TripAggregate(
        TRIP,
        revision,
        "제주 여행",
        "draft",
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-01"),
        "Asia/Seoul",
        "normal",
        List.of(new TripTransportMode("public_transit", 1, true)),
        List.of(
            new TripDay(
                UUID.fromString("45000000-0000-0000-0000-000000000013"),
                1,
                LocalDate.parse("2026-09-01"))),
        null,
        null,
        null,
        NOW.minusSeconds(60),
        NOW);
  }

  private static final class SequentialIds implements TripIdentityGenerator {
    private int value = 100;

    @Override
    public UUID generate() {
      return UUID.fromString("45000000-0000-0000-0001-%012d".formatted(value++));
    }
  }

  private static final class CapturingStore implements TripStore {
    private TripUpdateRecord updated;
    private UUID deletedOwner;
    private UUID deletedTrip;

    @Override
    public TripAggregate create(CreateTripRecord record) {
      return aggregate(1);
    }

    @Override
    public Optional<TripAggregate> findOwned(UUID ownerId, UUID tripId, Instant responseTime) {
      return Optional.of(aggregate(7));
    }

    @Override
    public TripListSlice listOwned(
        UUID ownerId, String status, TripListCursor after, int fetchSize, Instant responseTime) {
      return new TripListSlice(List.of());
    }

    @Override
    public TripMutationResult updateOwned(TripUpdateRecord record) {
      updated = record;
      return new TripMutationResult(aggregate(8), "maintained", false);
    }

    @Override
    public void deleteOwned(UUID ownerId, UUID tripId) {
      deletedOwner = ownerId;
      deletedTrip = tripId;
    }
  }
}
