package com.timingjeju.api.application.staypolicy;

import java.time.Instant;
import java.util.List;

public record StayPolicyPayload(
    String version,
    Instant effectiveAt,
    String expectedActiveVersion,
    List<StayPolicyCandidate> policies) {

  public StayPolicyPayload {
    policies = policies == null ? null : List.copyOf(policies);
  }
}
