package com.timingjeju.api.application.datahealth;

import java.time.Instant;
import java.util.Objects;

public final class ProviderDataHealthEvaluator {
  private ProviderDataHealthEvaluator() {}

  public static ProviderDataHealthItem evaluate(
      ProviderDataHealthPolicy policy, ProviderDataHealthHistory history, Instant evaluatedAt) {
    Objects.requireNonNull(policy, "policy는 필수입니다.");
    Objects.requireNonNull(evaluatedAt, "evaluatedAt은 필수입니다.");
    if (!policy.enabled()) {
      return new ProviderDataHealthItem(
          policy.key(),
          ProviderDataHealthStatus.DISABLED,
          null,
          null,
          null,
          false,
          ProviderDataHealthReason.PROVIDER_DISABLED);
    }
    if (history == null) {
      return new ProviderDataHealthItem(
          policy.key(),
          ProviderDataHealthStatus.NEVER_SYNCED,
          null,
          null,
          null,
          false,
          ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT);
    }
    if (history.lastSuccessAt() == null) {
      return new ProviderDataHealthItem(
          policy.key(),
          ProviderDataHealthStatus.NO_RECENT_VALID_FACTS,
          history.lastAttemptAt(),
          null,
          null,
          false,
          ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED);
    }
    boolean stale = !history.factsAsOf().plus(policy.ttl()).isAfter(evaluatedAt);
    if (history.latestStatus() != ProviderDataHealthAttemptStatus.SUCCEEDED) {
      return new ProviderDataHealthItem(
          policy.key(),
          ProviderDataHealthStatus.LAST_ATTEMPT_FAILED,
          history.lastAttemptAt(),
          history.lastSuccessAt(),
          history.factsAsOf(),
          stale,
          ProviderDataHealthReason.LATEST_RUN_FAILED);
    }
    return new ProviderDataHealthItem(
        policy.key(),
        stale ? ProviderDataHealthStatus.STALE : ProviderDataHealthStatus.FRESH,
        history.lastAttemptAt(),
        history.lastSuccessAt(),
        history.factsAsOf(),
        stale,
        stale ? ProviderDataHealthReason.TTL_EXPIRED : ProviderDataHealthReason.HEALTHY);
  }
}
