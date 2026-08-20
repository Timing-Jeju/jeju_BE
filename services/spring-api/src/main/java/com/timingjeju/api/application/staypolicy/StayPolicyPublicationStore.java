package com.timingjeju.api.application.staypolicy;

import java.time.Instant;

public interface StayPolicyPublicationStore {
  void publish(ValidatedStayPolicyPayload payload, Instant importedAt);
}
