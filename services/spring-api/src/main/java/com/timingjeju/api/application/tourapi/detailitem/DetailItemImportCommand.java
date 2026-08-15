package com.timingjeju.api.application.tourapi.detailitem;

import java.util.Objects;

public record DetailItemImportCommand(
    String contentId, String contentTypeId, DetailItemLineage lineage) {
  public DetailItemImportCommand {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()) {
      throw new IllegalArgumentException("contentId와 contentTypeId는 필수입니다.");
    }
    contentId = contentId.strip();
    contentTypeId = contentTypeId.strip();
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
