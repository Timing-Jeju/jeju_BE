package com.timingjeju.api.application.tourapi.sync;

import java.util.List;
import java.util.Objects;

public record IncrementalSyncPage(
    int pageNo, int numOfRows, int totalCount, int rawItemCount, List<PlaceSyncChange> changes) {
  public IncrementalSyncPage {
    if (pageNo < 1
        || numOfRows < 1
        || totalCount < 0
        || rawItemCount < 0
        || rawItemCount > numOfRows) {
      throw IncrementalSyncException.invalidResponse();
    }
    changes = List.copyOf(Objects.requireNonNull(changes, "changes는 필수입니다."));
    if (changes.size() != rawItemCount) {
      throw IncrementalSyncException.invalidResponse();
    }
  }
}
