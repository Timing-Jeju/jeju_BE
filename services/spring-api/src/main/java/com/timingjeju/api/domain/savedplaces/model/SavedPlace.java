package com.timingjeju.api.domain.savedplaces.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SavedPlace(
    UUID placeId,
    String name,
    String category,
    String regionLabel,
    String thumbnailUrl,
    Integer recommendedStayMinutes,
    String memo,
    List<String> tags,
    int priority,
    Integer targetDay,
    Instant savedAt,
    Instant updatedAt) {

  public SavedPlace {
    tags = List.copyOf(tags);
  }
}
