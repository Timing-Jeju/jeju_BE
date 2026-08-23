package com.timingjeju.api.application.staypolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DefaultStayPolicyResolver implements StayPolicyResolver {

  private final StayPolicyLookup lookup;

  public DefaultStayPolicyResolver(StayPolicyLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public RecommendedStay resolve(UUID placeId, String category) {
    return lookup
        .findActive(Objects.requireNonNull(placeId), Objects.requireNonNull(category))
        .orElseGet(RecommendedStay::unavailable);
  }

  @Override
  public Map<UUID, RecommendedStay> resolveAll(List<StayPolicySubject> subjects) {
    return lookup.findActive(List.copyOf(subjects));
  }
}
