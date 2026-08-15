package com.timingjeju.api.application.tourapi.detail;

import java.util.Objects;

public record PlaceDetailImportCommand(
    String contentId,
    String contentTypeId,
    DetailLineage commonLineage,
    DetailLineage introLineage) {
  public PlaceDetailImportCommand {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()) {
      throw new IllegalArgumentException("contentId와 contentTypeId는 필수입니다.");
    }
    commonLineage = Objects.requireNonNull(commonLineage, "commonLineage는 필수입니다.");
    introLineage = Objects.requireNonNull(introLineage, "introLineage는 필수입니다.");
  }
}
