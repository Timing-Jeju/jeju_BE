package com.timingjeju.api.application.placestop;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlaceStopLinkRepository {
  PlaceStopLinkBatchResult recompute(PlaceStopLinkBatch batch, PlaceStopLinkPolicy policy);

  List<PlaceStopLinkCandidate> findEligible(
      UUID placeId, int radiusMeters, int maxCandidates, Instant now);
}
