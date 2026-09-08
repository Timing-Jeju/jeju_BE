package com.timingjeju.api.application.trip.service;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlacePreference;
import com.timingjeju.api.application.trip.TripPlacePreferencesMutation;
import com.timingjeju.api.application.trip.TripPlacePreferencesStore;
import com.timingjeju.api.application.trip.TripPlacePreferencesUpdate;
import com.timingjeju.api.application.trip.UpdateTripPlacePreferencesCommand;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TripPlacePreferencesService {
  private static final Set<String> TYPES = Set.of("must_visit", "avoid");
  private static final Comparator<TripPlacePreference> CANONICAL_ORDER =
      Comparator.comparingInt(TripPlacePreference::priority)
          .reversed()
          .thenComparing(
              TripPlacePreference::placeId, TripPlacePreferencesService::compareUuidBytes);

  private final TripPlacePreferencesStore store;
  private final Clock clock;

  public TripPlacePreferencesService(TripPlacePreferencesStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public TripPlacePreferencesMutation replace(
      CurrentUser user,
      UUID tripId,
      String expectedEtag,
      UpdateTripPlacePreferencesCommand command) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    com.timingjeju.api.application.trip.TripExpectedRevision expected;
    try {
      expected = com.timingjeju.api.application.trip.TripEntityTag.parse(expectedEtag);
    } catch (TripException failure) {
      throw TripException.invalidRequest();
    }
    if (!tripId.equals(expected.tripId())) {
      throw TripException.versionConflict();
    }
    List<TripPlacePreference> preferences = canonicalizeAndValidate(command);
    return store.replaceOwned(
        new TripPlacePreferencesUpdate(
            user.userId(),
            tripId,
            expectedEtag,
            preferences,
            clock.instant().truncatedTo(ChronoUnit.MICROS)));
  }

  private static int compareUuidBytes(UUID left, UUID right) {
    int mostSignificant =
        Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return mostSignificant != 0
        ? mostSignificant
        : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }

  private static List<TripPlacePreference> canonicalizeAndValidate(
      UpdateTripPlacePreferencesCommand command) {
    if (command == null
        || command.items() == null
        || command.items().size() > 100
        || command.items().stream().anyMatch(Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    Set<UUID> places = new HashSet<>();
    for (TripPlacePreference item : command.items()) {
      if (item.placeId() == null || item.type() == null) {
        throw TripException.invalidRequest();
      }
      if (!TYPES.contains(item.type())
          || !places.add(item.placeId())
          || item.priority() < 0
          || item.priority() > 100
          || (item.targetDayNo() != null && (item.targetDayNo() < 1 || item.targetDayNo() > 30))) {
        throw TripException.placePreferenceConstraintViolation();
      }
    }
    return command.items().stream().sorted(CANONICAL_ORDER).toList();
  }
}
