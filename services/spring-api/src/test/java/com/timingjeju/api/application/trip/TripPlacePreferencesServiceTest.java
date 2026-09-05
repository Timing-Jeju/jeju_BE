package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.service.TripPlacePreferencesService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPlacePreferencesServiceTest {
  private static final UUID OWNER = UUID.fromString("48000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("48000000-0000-0000-0000-000000000002");
  private static final UUID PLACE_A = UUID.fromString("48000000-0000-0000-0000-000000000010");
  private static final UUID PLACE_B = UUID.fromString("48000000-0000-0000-0000-000000000011");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);
  private static final Instant NOW = Instant.parse("2026-09-01T03:04:05.123456Z");
  private static final String ETAG = "\"trip-" + TRIP + "-r1\"";

  @Test
  void replace는_희망과_회피를_priority와_placeId의_canonical_순서로_전달한다() {
    CapturingStore store = new CapturingStore();
    TripPlacePreferencesService service = service(store);

    service.replace(
        USER,
        TRIP,
        ETAG,
        new UpdateTripPlacePreferencesCommand(
            List.of(
                new TripPlacePreference(PLACE_B, "avoid", null, 10),
                new TripPlacePreference(PLACE_A, "must_visit", 2, 90))));

    assertThat(store.updates).hasSize(1);
    assertThat(store.updates.getFirst().preferences())
        .containsExactly(
            new TripPlacePreference(PLACE_A, "must_visit", 2, 90),
            new TripPlacePreference(PLACE_B, "avoid", null, 10));
    assertThat(store.updates.getFirst().ownerId()).isEqualTo(OWNER);
    assertThat(store.updates.getFirst().expectedEtag()).isEqualTo(ETAG);
    assertThat(store.updates.getFirst().updatedAt()).isEqualTo(NOW);
  }

  @Test
  void replace는_high_bit_UUID도_PostgreSQL_uuid_byte_order와_같게_정렬한다() {
    CapturingStore store = new CapturingStore();
    UUID unsignedLower = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    UUID unsignedHigher = UUID.fromString("80000000-0000-0000-0000-000000000000");

    service(store)
        .replace(
            USER,
            TRIP,
            ETAG,
            new UpdateTripPlacePreferencesCommand(
                List.of(
                    new TripPlacePreference(unsignedHigher, "avoid", null, 50),
                    new TripPlacePreference(unsignedLower, "must_visit", null, 50))));

    assertThat(store.updates.getFirst().preferences())
        .extracting(TripPlacePreference::placeId)
        .containsExactly(unsignedLower, unsignedHigher);
  }

  @Test
  void replace는_빈_배열을_전체_삭제_명령으로_허용한다() {
    CapturingStore store = new CapturingStore();

    service(store).replace(USER, TRIP, ETAG, new UpdateTripPlacePreferencesCommand(List.of()));

    assertThat(store.updates.getFirst().preferences()).isEmpty();
  }

  @Test
  void replace는_같은_place의_동일하거나_상충하는_role_중복을_422로_거부한다() {
    CapturingStore store = new CapturingStore();
    TripPlacePreferencesService service = service(store);

    for (List<TripPlacePreference> items :
        List.of(
            List.of(
                new TripPlacePreference(PLACE_A, "must_visit", null, 50),
                new TripPlacePreference(PLACE_A, "must_visit", 1, 60)),
            List.of(
                new TripPlacePreference(PLACE_A, "must_visit", null, 50),
                new TripPlacePreference(PLACE_A, "avoid", null, 50)))) {
      assertCode(
          () -> service.replace(USER, TRIP, ETAG, new UpdateTripPlacePreferencesCommand(items)),
          "PLACE_PREFERENCE_CONSTRAINT_VIOLATION");
    }
    assertThat(store.updates).isEmpty();
  }

  @Test
  void replace는_enum_Day와_priority_위반을_422로_거부한다() {
    CapturingStore store = new CapturingStore();
    TripPlacePreferencesService service = service(store);
    for (List<TripPlacePreference> items :
        List.of(
            List.of(new TripPlacePreference(PLACE_A, "preferred", null, 50)),
            List.of(new TripPlacePreference(PLACE_A, "must_visit", 0, 50)),
            List.of(new TripPlacePreference(PLACE_A, "must_visit", 31, 50)),
            List.of(new TripPlacePreference(PLACE_A, "must_visit", null, -1)),
            List.of(new TripPlacePreference(PLACE_A, "must_visit", null, 101)))) {
      assertCode(
          () -> service.replace(USER, TRIP, ETAG, new UpdateTripPlacePreferencesCommand(items)),
          "PLACE_PREFERENCE_CONSTRAINT_VIOLATION");
    }
    assertThat(store.updates).isEmpty();
  }

  @Test
  void replace는_null과_비정상_IfMatch를_400으로_거부한다() {
    CapturingStore store = new CapturingStore();
    TripPlacePreferencesService service = service(store);

    assertCode(() -> service.replace(USER, TRIP, ETAG, null), "INVALID_REQUEST");
    assertCode(
        () -> service.replace(USER, TRIP, ETAG, new UpdateTripPlacePreferencesCommand(null)),
        "INVALID_REQUEST");
    List<TripPlacePreference> tooMany = new ArrayList<>();
    for (int index = 0; index < 101; index++) {
      tooMany.add(
          new TripPlacePreference(
              new UUID(0x4800000000000000L, index + 1L), "must_visit", null, 0));
    }
    assertCode(
        () -> service.replace(USER, TRIP, ETAG, new UpdateTripPlacePreferencesCommand(tooMany)),
        "INVALID_REQUEST");
    assertCode(
        () ->
            service.replace(
                USER,
                TRIP,
                ETAG,
                new UpdateTripPlacePreferencesCommand(java.util.Collections.singletonList(null))),
        "INVALID_REQUEST");
    for (String invalid : List.of("trip-current-v1", "W/\"trip-current-v1\"", "\"\"")) {
      assertCode(
          () ->
              service.replace(
                  USER, TRIP, invalid, new UpdateTripPlacePreferencesCommand(List.of())),
          "INVALID_REQUEST");
    }
    assertThat(store.updates).isEmpty();
  }

  @Test
  void replace는_다른_trip의_정상_ETag를_version_conflict로_거부한다() {
    UUID otherTrip = UUID.fromString("48000000-0000-0000-0000-000000000099");

    assertCode(
        () ->
            service(new CapturingStore())
                .replace(
                    USER,
                    TRIP,
                    "\"trip-" + otherTrip + "-r1\"",
                    new UpdateTripPlacePreferencesCommand(List.of())),
        "TRIP_VERSION_CONFLICT");
  }

  private static TripPlacePreferencesService service(TripPlacePreferencesStore store) {
    return new TripPlacePreferencesService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo(expected);
  }

  private static final class CapturingStore implements TripPlacePreferencesStore {
    private final List<TripPlacePreferencesUpdate> updates = new ArrayList<>();

    @Override
    public TripPlacePreferencesMutation replaceOwned(TripPlacePreferencesUpdate update) {
      updates.add(update);
      return new TripPlacePreferencesMutation(
          update.tripId(),
          "none",
          false,
          null,
          "draft",
          update.updatedAt(),
          2,
          "\"trip-" + update.tripId() + "-r2\"",
          update.preferences());
    }
  }
}
