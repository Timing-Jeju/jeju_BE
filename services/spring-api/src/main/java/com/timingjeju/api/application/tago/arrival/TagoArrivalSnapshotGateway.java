package com.timingjeju.api.application.tago.arrival;

import java.time.Instant;
import java.util.UUID;

public interface TagoArrivalSnapshotGateway {
  SavedTagoArrivalSnapshot capture(
      UUID runId,
      TagoArrivalCacheKey key,
      TagoArrivalSourceResponse response,
      Instant observedAt,
      Instant expiresAt);

  void reject(SavedTagoArrivalSnapshot snapshot, TagoArrivalException.Code code);
}
