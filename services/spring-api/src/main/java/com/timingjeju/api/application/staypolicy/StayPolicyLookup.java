package com.timingjeju.api.application.staypolicy;

import java.util.Optional;
import java.util.UUID;

public interface StayPolicyLookup {
  Optional<RecommendedStay> findActive(UUID placeId, String category);
}
