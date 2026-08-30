package com.timingjeju.api.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PushEligibilityStore {

  List<StoredEligiblePushDevice> findEligible(UUID userId, Instant evaluatedAt);
}
