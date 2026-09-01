package com.timingjeju.api.application.trip.service;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferences;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripPreferencesStore;
import com.timingjeju.api.application.trip.TripPreferencesUpdate;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.UpdateTripPreferencesCommand;
import java.text.Normalizer;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TripPreferencesService {
  private static final Pattern STRONG_ETAG = Pattern.compile("^\"[A-Za-z0-9._:-]{1,128}\"$");
  private static final Set<String> CATEGORIES =
      Set.of(
          "tourist_attraction",
          "cultural_facility",
          "festival",
          "travel_course",
          "leisure",
          "restaurant",
          "cafe",
          "shopping");
  private static final Set<String> MODES = Set.of("public_transit", "rental_car", "taxi");

  private final TripPreferencesStore store;
  private final Clock clock;

  public TripPreferencesService(TripPreferencesStore store, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
  }

  public TripPreferencesMutation replace(
      CurrentUser user, UUID tripId, String expectedEtag, UpdateTripPreferencesCommand command) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    if (expectedEtag == null || !STRONG_ETAG.matcher(expectedEtag).matches()) {
      throw TripException.invalidRequest();
    }
    TripPreferences preferences = canonicalizeAndValidate(command);
    return store.replaceOwned(
        new TripPreferencesUpdate(
            user.userId(),
            tripId,
            expectedEtag,
            preferences,
            clock.instant().truncatedTo(ChronoUnit.MICROS)));
  }

  private static TripPreferences canonicalizeAndValidate(UpdateTripPreferencesCommand command) {
    if (command == null
        || command.preferredCategories() == null
        || command.preferredRegionCodes() == null
        || command.transportModes() == null
        || command.arrivalRegionCode() == null
        || command.departureRegionCode() == null) {
      throw TripException.invalidRequest();
    }
    if (command.preferredCategories().size() > 8
        || command.preferredRegionCodes().size() > 20
        || command.transportModes().isEmpty()
        || command.transportModes().size() > 3
        || command.preferredCategories().stream().anyMatch(Objects::isNull)
        || command.preferredRegionCodes().stream().anyMatch(Objects::isNull)
        || command.transportModes().stream().anyMatch(Objects::isNull)) {
      throw TripException.invalidRequest();
    }

    List<String> categories = List.copyOf(command.preferredCategories());
    String arrival = canonicalRegion(command.arrivalRegionCode());
    String departure = canonicalRegion(command.departureRegionCode());
    List<String> regions =
        command.preferredRegionCodes().stream()
            .map(TripPreferencesService::canonicalRegion)
            .toList();
    List<TripTransportMode> modes = new ArrayList<>(command.transportModes());
    modes.sort(java.util.Comparator.comparingInt(TripTransportMode::priority));

    if (categories.stream().anyMatch(category -> !CATEGORIES.contains(category))
        || new HashSet<>(categories).size() != categories.size()
        || new HashSet<>(regions).size() != regions.size()) {
      throw TripException.preferenceConstraintViolation();
    }
    validateModes(modes);
    return new TripPreferences(
        categories,
        arrival,
        departure,
        regions,
        command.startPlaceId(),
        command.endPlaceId(),
        modes);
  }

  private static void validateModes(List<TripTransportMode> modes) {
    Set<String> names = new HashSet<>();
    Set<Integer> priorities = new HashSet<>();
    int primaryCount = 0;
    for (int index = 0; index < modes.size(); index++) {
      TripTransportMode mode = modes.get(index);
      if (mode.mode() == null) {
        throw TripException.invalidRequest();
      }
      if (!MODES.contains(mode.mode())
          || mode.priority() != index + 1
          || !names.add(mode.mode())
          || !priorities.add(mode.priority())) {
        throw TripException.preferenceConstraintViolation();
      }
      if (mode.primary()) {
        primaryCount++;
        if (mode.priority() != 1) {
          throw TripException.preferenceConstraintViolation();
        }
      }
    }
    if (primaryCount != 1) {
      throw TripException.preferenceConstraintViolation();
    }
  }

  private static String canonicalRegion(String raw) {
    String value = Normalizer.normalize(asciiTrim(raw), Normalizer.Form.NFC);
    if (value.isEmpty() || value.length() > 50) {
      throw TripException.invalidRequest();
    }
    return value;
  }

  private static String asciiTrim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) <= 0x20) {
      start++;
    }
    while (end > start && value.charAt(end - 1) <= 0x20) {
      end--;
    }
    return value.substring(start, end);
  }
}
