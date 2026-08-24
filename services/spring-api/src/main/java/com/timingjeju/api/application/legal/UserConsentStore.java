package com.timingjeju.api.application.legal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface UserConsentStore {

  ConsentUpdateResult updateRequiredConsents(
      UUID userId, String locale, List<ConsentDecision> decisions, Instant evaluatedAt);
}
