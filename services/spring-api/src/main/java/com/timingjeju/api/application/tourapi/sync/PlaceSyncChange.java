package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.tourapi.place.TourPlace;
import java.time.Instant;
import java.util.Objects;

public record PlaceSyncChange(
    String contentId,
    String contentTypeId,
    Instant sourceModifiedAt,
    PlaceSyncAction action,
    TourPlace place) {
  public PlaceSyncChange {
    contentId = requireText(contentId, "contentId");
    contentTypeId = requireText(contentTypeId, "contentTypeId");
    sourceModifiedAt = Objects.requireNonNull(sourceModifiedAt, "sourceModifiedAt은 필수입니다.");
    action = Objects.requireNonNull(action, "action은 필수입니다.");
    if ((action == PlaceSyncAction.UPSERT) != (place != null)) {
      throw IncrementalSyncException.invalidResponse();
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "는 필수입니다.");
    }
    return value.strip();
  }
}
