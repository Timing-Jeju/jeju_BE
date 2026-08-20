package com.timingjeju.api.domain.places.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlaceSearchRow(
    UUID placeId,
    String contentId,
    String name,
    String normalizedName,
    String category,
    String regionCode,
    String regionLabel,
    String address,
    double lat,
    double lng,
    String thumbnailUrl,
    String operationsSummary,
    Long distanceMeters,
    String provider,
    Instant observedAt,
    Instant expiresAt,
    boolean stale,
    boolean saved,
    String memo,
    List<String> tags) {

  public PlaceSearchRow {
    tags = List.copyOf(tags);
  }
}
