package com.timingjeju.api.domain.places.dto.request;

import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import java.util.regex.Pattern;

public record PlacesListQuery(
    String query, String category, String regionCode, String cursor, int size, boolean savedOnly) {

  private static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,49}$");
  private static final int DEFAULT_SIZE = 20;

  public static PlacesListQuery of(
      String query,
      String category,
      String regionCode,
      String cursor,
      Integer size,
      Boolean savedOnly) {
    String normalizedQuery = query == null ? null : query.trim();
    int normalizedSize = size == null ? DEFAULT_SIZE : size;
    boolean normalizedSavedOnly = Boolean.TRUE.equals(savedOnly);
    validateQuery(normalizedQuery, category, regionCode, cursor, normalizedSize);
    return new PlacesListQuery(
        normalizedQuery, category, regionCode, cursor, normalizedSize, normalizedSavedOnly);
  }

  private static void validateQuery(
      String query, String category, String regionCode, String cursor, int size) {
    if ((query != null && (query.isEmpty() || query.length() > 100))
        || (category != null && !CanonicalPlaceCategory.isValid(category))
        || (regionCode != null && !REGION.matcher(regionCode).matches())
        || (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
        || size < 1
        || size > 100) {
      throw new PlaceQueryValidationException("INVALID_QUERY_PARAMETER");
    }
  }
}
