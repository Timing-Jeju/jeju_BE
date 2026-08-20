package com.timingjeju.api.application.staypolicy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StayPolicyResolver {
  RecommendedStay resolve(UUID placeId, String category);

  Map<UUID, RecommendedStay> resolveAll(List<StayPolicySubject> subjects);
}
