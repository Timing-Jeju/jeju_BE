package com.timingjeju.api.application.tourapi.detailitem;

import java.util.Objects;
import java.util.UUID;

public record DetailItemImportCommand(String contentId, String contentTypeId, UUID importRunId) {
  public DetailItemImportCommand {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()) {
      throw new IllegalArgumentException("contentId와 contentTypeId는 필수입니다.");
    }
    contentId = contentId.strip();
    contentTypeId = contentTypeId.strip();
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
