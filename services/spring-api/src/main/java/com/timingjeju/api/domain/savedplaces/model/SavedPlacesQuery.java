package com.timingjeju.api.domain.savedplaces.model;

import java.text.Normalizer;

public record SavedPlacesQuery(
    String tag, String category, String regionCode, String sort, String cursor, int size) {
  public static SavedPlacesQuery of(
      String tag, String category, String regionCode, String sort, String cursor, Integer size) {
    int actualSize = size == null ? 20 : size;
    String actualSort = sort == null ? "saved_at_desc" : sort;
    String actualTag = normalize(tag);
    String actualCategory = normalize(category);
    String actualRegion = normalize(regionCode);
    if (actualSize < 1
        || actualSize > 100
        || !java.util.Set.of("saved_at_desc", "priority_desc", "target_day_asc")
            .contains(actualSort)
        || invalidTag(actualTag)
        || actualCategory != null
            && !actualCategory.matches("(?:[A-Z]{2}|content-type:[0-9]{1,10})")
        || actualRegion != null && !actualRegion.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
        || actualRegion != null && actualRegion.length() > 50
        || cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
      throw com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException.of(
          "INVALID_QUERY_PARAMETER");
    return new SavedPlacesQuery(
        actualTag, actualCategory, actualRegion, actualSort, cursor, actualSize);
  }

  private static String normalize(String value) {
    return value == null
        ? null
        : Normalizer.normalize(SavedPlaceCommand.trimAsciiSpace(value), Normalizer.Form.NFC);
  }

  private static boolean invalidTag(String value) {
    return value != null
        && (value.isEmpty()
            || value.codePointCount(0, value.length()) > 50
            || value.codePoints().anyMatch(Character::isISOControl));
  }
}
