package com.timingjeju.api.application.tourapi.detailitem;

import java.time.Instant;
import java.util.Objects;

public record DetailItemSyncCommand(
    String contentId,
    String contentTypeId,
    DetailItemBatch batch,
    DetailItemLineage lineage,
    Instant observedAt) {
  public DetailItemSyncCommand {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()
        || batch == null
        || !contentId.equals(batch.contentId())
        || !contentTypeId.equals(batch.contentTypeId())) {
      throw new IllegalArgumentException("반복 상세 command 식별자가 일치하지 않습니다.");
    }
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
  }
}
