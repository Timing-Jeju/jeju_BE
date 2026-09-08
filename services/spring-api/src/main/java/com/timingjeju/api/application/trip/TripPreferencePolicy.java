package com.timingjeju.api.application.trip;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TripPreferencePolicy {
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

  private TripPreferencePolicy() {}

  public static ReplaceTripPreferencesCommand canonicalizeAndValidate(
      ReplaceTripPreferencesCommand source) {
    if (source == null
        || source.preferredCategories() == null
        || source.arrivalRegionCode() == null
        || source.departureRegionCode() == null
        || source.preferredRegionCodes() == null
        || source.transportModes() == null) {
      throw TripException.invalidRequest();
    }
    List<String> categories = structuralStrings(source.preferredCategories(), 8, false);
    if (!CATEGORIES.containsAll(categories) || hasDuplicates(categories)) {
      throw TripException.preferenceConstraintViolation();
    }
    String arrival = canonicalRegion(source.arrivalRegionCode());
    String departure = canonicalRegion(source.departureRegionCode());
    List<String> regions = structuralStrings(source.preferredRegionCodes(), 20, true);
    if (hasDuplicates(regions)) {
      throw TripException.preferenceConstraintViolation();
    }
    List<TripTransportMode> modes = validateModes(source.transportModes());
    return new ReplaceTripPreferencesCommand(
        categories, arrival, departure, regions, source.startPlaceId(), source.endPlaceId(), modes);
  }

  private static List<String> structuralStrings(
      List<String> values, int maxItems, boolean normalize) {
    if (values.size() > maxItems || values.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    List<String> result = new ArrayList<>(values.size());
    for (String value : values) {
      String canonical = normalize ? canonicalRegion(value) : value;
      int length = canonical.codePointCount(0, canonical.length());
      if (length < 1 || length > 50) {
        throw TripException.invalidRequest();
      }
      result.add(canonical);
    }
    return List.copyOf(result);
  }

  private static String canonicalRegion(String value) {
    if (value.indexOf('\0') >= 0) {
      throw TripException.invalidRequest();
    }
    String canonical = Normalizer.normalize(asciiTrim(value), Normalizer.Form.NFC);
    int length = canonical.codePointCount(0, canonical.length());
    if (length < 1 || length > 50) {
      throw TripException.invalidRequest();
    }
    return canonical;
  }

  private static List<TripTransportMode> validateModes(List<TripTransportMode> values) {
    if (values.isEmpty()
        || values.size() > 3
        || values.stream().anyMatch(java.util.Objects::isNull)) {
      throw TripException.invalidRequest();
    }
    Set<String> names = new HashSet<>();
    int primaryCount = 0;
    for (int index = 0; index < values.size(); index++) {
      TripTransportMode mode = values.get(index);
      if (mode.mode() == null) {
        throw TripException.invalidRequest();
      }
      if (!MODES.contains(mode.mode()) || !names.add(mode.mode()) || mode.priority() != index + 1) {
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
    return List.copyOf(values);
  }

  private static boolean hasDuplicates(List<String> values) {
    return new HashSet<>(values).size() != values.size();
  }

  private static String asciiTrim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isAsciiTrimCharacter(value.charAt(start))) start++;
    while (end > start && isAsciiTrimCharacter(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean isAsciiTrimCharacter(char value) {
    return switch (value) {
      case ' ', '\t', '\n', '\r', '\f', '\u000B' -> true;
      default -> false;
    };
  }
}
