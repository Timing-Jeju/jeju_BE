package com.timingjeju.api.application.staypolicy;

import java.util.UUID;

public record StayPolicyCandidate(
    StayPolicyScope scope, String category, UUID placeId, int minutes) {

  public static StayPolicyCandidate categoryDefault(String category, int minutes) {
    return new StayPolicyCandidate(StayPolicyScope.CATEGORY_DEFAULT, category, null, minutes);
  }

  public static StayPolicyCandidate placeOverride(UUID placeId, int minutes) {
    return new StayPolicyCandidate(StayPolicyScope.PLACE_OVERRIDE, null, placeId, minutes);
  }

  String targetKey() {
    return switch (scope) {
      case CATEGORY_DEFAULT -> "category:" + category;
      case PLACE_OVERRIDE -> "place:" + placeId;
    };
  }
}
