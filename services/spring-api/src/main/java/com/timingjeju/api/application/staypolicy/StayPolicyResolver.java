package com.timingjeju.api.application.staypolicy;

import java.util.UUID;

public interface StayPolicyResolver {
  RecommendedStay resolve(UUID placeId, String category);
}
