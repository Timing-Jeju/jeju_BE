package com.timingjeju.api.application.tourapi.image;

import java.time.Instant;
import java.util.Objects;

public record PlaceImageSyncCommand(
    String contentId,
    String contentTypeId,
    PlaceImageBatch batch,
    PlaceImageSweep sweep,
    Instant observedAt) {
  public PlaceImageSyncCommand {
    if (contentId == null
        || contentTypeId == null
        || batch == null
        || !contentId.equals(batch.contentId())
        || !contentTypeId.equals(batch.contentTypeId())) {
      throw new IllegalArgumentException("image sync identity가 일치하지 않습니다.");
    }
    sweep = Objects.requireNonNull(sweep, "sweep은 필수입니다.");
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
  }
}
