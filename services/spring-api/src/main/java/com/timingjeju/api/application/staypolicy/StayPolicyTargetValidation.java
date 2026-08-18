package com.timingjeju.api.application.staypolicy;

import java.util.Set;
import java.util.UUID;

public record StayPolicyTargetValidation(Set<String> liveCategories, Set<UUID> livePlaceIds) {
  public StayPolicyTargetValidation {
    liveCategories = Set.copyOf(liveCategories);
    livePlaceIds = Set.copyOf(livePlaceIds);
  }
}
