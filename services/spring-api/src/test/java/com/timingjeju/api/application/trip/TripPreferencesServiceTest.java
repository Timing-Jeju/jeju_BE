package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.service.TripPreferencesService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPreferencesServiceTest {
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000002");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);
  private static final Instant NOW = Instant.parse("2026-09-01T01:02:03Z");
  private static final String ETAG = "\"trip-current-v1\"";

  @Test
  void replace는_1개부터_3개_mode까지_연속_priority와_primary를_저장한다() {
    CapturingStore store = new CapturingStore();
    TripPreferencesService service = service(store);

    for (int count = 1; count <= 3; count++) {
      service.replace(USER, TRIP, ETAG, command(modes(count)));
    }

    assertThat(store.updates).hasSize(3);
    assertThat(store.updates)
        .extracting(update -> update.preferences().transportModes().size())
        .containsExactly(1, 2, 3);
    assertThat(store.updates.getLast().preferences().transportModes())
        .extracting(TripTransportMode::priority)
        .containsExactly(1, 2, 3);
  }

  @Test
  void replace는_region을_ASCII_trim과_NFC로_정규화해_저장한다() {
    CapturingStore store = new CapturingStore();
    TripPreferencesService service = service(store);

    service.replace(
        USER,
        TRIP,
        ETAG,
        new UpdateTripPreferencesCommand(
            List.of("tourist_attraction", "cafe"),
            " \t\u110C\u1166\u110C\u116E-\u1109\u1175 ",
            " seogwipo-si\r\n",
            List.of(" \u1109\u1165\u11BC\u1109\u1161\u11AB ", " aewol "),
            null,
            null,
            modes(2)));

    TripPreferences saved = store.updates.getFirst().preferences();
    assertThat(saved.arrivalRegionCode()).isEqualTo("제주-시");
    assertThat(saved.departureRegionCode()).isEqualTo("seogwipo-si");
    assertThat(saved.preferredRegionCodes()).containsExactly("성산", "aewol");
    assertThat(store.updates.getFirst().expectedEtag()).isEqualTo(ETAG);
    assertThat(store.updates.getFirst().updatedAt()).isEqualTo(NOW);
  }

  @Test
  void replace는_중복_enum_priority_primary_위반을_422로_거부하고_저장하지_않는다() {
    CapturingStore store = new CapturingStore();
    TripPreferencesService service = service(store);

    List<UpdateTripPreferencesCommand> invalid =
        List.of(
            command(
                List.of(
                    new TripTransportMode("public_transit", 1, true),
                    new TripTransportMode("public_transit", 2, false))),
            command(
                List.of(
                    new TripTransportMode("public_transit", 1, true),
                    new TripTransportMode("taxi", 3, false))),
            command(
                List.of(
                    new TripTransportMode("public_transit", 1, false),
                    new TripTransportMode("taxi", 2, true))),
            command(List.of(new TripTransportMode("walk", 1, true))),
            new UpdateTripPreferencesCommand(
                List.of("cafe", "cafe"),
                "jeju-si",
                "jeju-si",
                List.of("aewol"),
                null,
                null,
                modes(1)),
            new UpdateTripPreferencesCommand(
                List.of("cafe"),
                "jeju-si",
                "jeju-si",
                List.of("aewol", "aewol"),
                null,
                null,
                modes(1)));

    invalid.forEach(
        command ->
            assertThatThrownBy(() -> service.replace(USER, TRIP, ETAG, command))
                .isInstanceOf(TripException.class)
                .extracting(failure -> ((TripException) failure).code())
                .isEqualTo("PREFERENCE_CONSTRAINT_VIOLATION"));
    assertThat(store.updates).isEmpty();
  }

  @Test
  void replace는_누락_공백_길이_범위와_잘못된_IfMatch를_400으로_거부한다() {
    CapturingStore store = new CapturingStore();
    TripPreferencesService service = service(store);

    List<UpdateTripPreferencesCommand> invalid =
        List.of(
            new UpdateTripPreferencesCommand(
                null, "jeju-si", "jeju-si", List.of(), null, null, modes(1)),
            new UpdateTripPreferencesCommand(
                List.of(), " ", "jeju-si", List.of(), null, null, modes(1)),
            new UpdateTripPreferencesCommand(
                List.of(), "jeju-si", "jeju-si", null, null, null, modes(1)),
            new UpdateTripPreferencesCommand(
                List.of(), "jeju-si", "jeju-si", List.of("x".repeat(51)), null, null, modes(1)),
            command(List.of()));

    invalid.forEach(
        command ->
            assertThatThrownBy(() -> service.replace(USER, TRIP, ETAG, command))
                .isInstanceOf(TripException.class)
                .extracting(failure -> ((TripException) failure).code())
                .isEqualTo("INVALID_REQUEST"));
    for (String invalidEtag : List.of("trip-current-v1", "W/\"trip-current-v1\"", "\"\"")) {
      assertThatThrownBy(() -> service.replace(USER, TRIP, invalidEtag, command(modes(1))))
          .isInstanceOf(TripException.class)
          .extracting(failure -> ((TripException) failure).code())
          .isEqualTo("INVALID_REQUEST");
    }
    assertThat(store.updates).isEmpty();
  }

  private static TripPreferencesService service(TripPreferencesStore store) {
    return new TripPreferencesService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static UpdateTripPreferencesCommand command(List<TripTransportMode> modes) {
    return new UpdateTripPreferencesCommand(
        List.of("tourist_attraction", "cafe"),
        "jeju-si",
        "seogwipo-si",
        List.of("seongsan", "aewol"),
        null,
        null,
        modes);
  }

  private static List<TripTransportMode> modes(int count) {
    List<String> names = List.of("public_transit", "rental_car", "taxi");
    List<TripTransportMode> modes = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      modes.add(new TripTransportMode(names.get(index), index + 1, index == 0));
    }
    return List.copyOf(modes);
  }

  private static final class CapturingStore implements TripPreferencesStore {
    private final List<TripPreferencesUpdate> updates = new ArrayList<>();

    @Override
    public TripPreferencesMutation replaceOwned(TripPreferencesUpdate update) {
      updates.add(update);
      return new TripPreferencesMutation(
          update.tripId(), "none", false, null, "draft", update.updatedAt(), update.preferences());
    }
  }
}
