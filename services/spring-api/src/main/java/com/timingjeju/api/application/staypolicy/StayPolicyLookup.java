package com.timingjeju.api.application.staypolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StayPolicyLookup {
  Optional<RecommendedStay> findActive(UUID placeId, String category);

  default Map<UUID, RecommendedStay> findActive(List<StayPolicySubject> subjects) {
    Map<UUID, RecommendedStay> resolved = new LinkedHashMap<>();
    subjects.forEach(
        subject ->
            resolved.put(
                subject.placeId(),
                findActive(subject.placeId(), subject.category())
                    .orElseGet(RecommendedStay::unavailable)));
    return Map.copyOf(resolved);
  }
}
