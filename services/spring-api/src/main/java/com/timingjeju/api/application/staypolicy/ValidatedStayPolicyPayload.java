package com.timingjeju.api.application.staypolicy;

import java.time.Instant;
import java.util.List;

public record ValidatedStayPolicyPayload(
    String version,
    Instant effectiveAt,
    String expectedActiveVersion,
    String payloadHash,
    List<StayPolicyCandidate> policies) {

  public ValidatedStayPolicyPayload {
    policies = List.copyOf(policies);
  }
}
