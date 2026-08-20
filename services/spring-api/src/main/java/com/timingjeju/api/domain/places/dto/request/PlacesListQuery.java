package com.timingjeju.api.domain.places.dto.request;

import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import java.util.regex.Pattern;

public record PlacesListQuery(
    String query,
    String category,
    String regionCode,
    Double lat,
    Double lng,
    Integer radiusMeters,
    String cursor,
    int size,
    boolean savedOnly) {

  private static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,49}$");
  private static final int DEFAULT_SIZE = 20;
  private static final int DEFAULT_RADIUS_METERS = 10_000;

  public static PlacesListQuery of(
      String query,
      String category,
      String regionCode,
      Double lat,
      Double lng,
      Integer radiusMeters,
      String cursor,
      Integer size,
      Boolean savedOnly) {
    String normalizedQuery = query == null ? null : query.trim();
    int normalizedSize = size == null ? DEFAULT_SIZE : size;
    boolean normalizedSavedOnly = Boolean.TRUE.equals(savedOnly);
    validateQuery(normalizedQuery, category, regionCode, cursor, normalizedSize);
    Integer normalizedRadius = validateGeo(lat, lng, radiusMeters);
    return new PlacesListQuery(
        normalizedQuery,
        category,
        regionCode,
        lat,
        lng,
        normalizedRadius,
        cursor,
        normalizedSize,
        normalizedSavedOnly);
  }

  public boolean nearby() {
    return lat != null;
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

  private static Integer validateGeo(Double lat, Double lng, Integer radiusMeters) {
    boolean anyCoordinate = lat != null || lng != null;
    if (anyCoordinate && (lat == null || lng == null)) {
      throw invalidGeo();
    }
    if (!anyCoordinate) {
      if (radiusMeters != null) {
        throw invalidGeo();
      }
      return null;
    }
    if (!Double.isFinite(lat)
        || !Double.isFinite(lng)
        || lat < 33.0
        || lat > 34.0
        || lng < 126.0
        || lng > 127.0) {
      throw invalidGeo();
    }
    int normalizedRadius = radiusMeters == null ? DEFAULT_RADIUS_METERS : radiusMeters;
    if (normalizedRadius < 100 || normalizedRadius > 50_000) {
      throw invalidGeo();
    }
    return normalizedRadius;
  }

  private static PlaceQueryValidationException invalidGeo() {
    return new PlaceQueryValidationException("INVALID_GEO_FILTER");
  }
}
