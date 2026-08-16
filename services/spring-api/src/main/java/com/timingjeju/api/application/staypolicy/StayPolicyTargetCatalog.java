package com.timingjeju.api.application.staypolicy;

import java.util.Set;
import java.util.UUID;

public interface StayPolicyTargetCatalog {
  StayPolicyTargetValidation validateTargets(Set<String> categories, Set<UUID> placeIds);
}
