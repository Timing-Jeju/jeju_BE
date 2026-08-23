package com.timingjeju.api.application.datahealth;

import java.time.Instant;
import java.util.Objects;

public record ProviderDataHealthItem(
    ProviderDataHealthKey key,
    ProviderDataHealthStatus status,
    Instant lastAttemptAt,
    Instant lastSuccessAt,
    Instant factsAsOf,
    boolean stale,
    ProviderDataHealthReason reasonCode) {

  public ProviderDataHealthItem {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(status, "status는 필수입니다.");
    Objects.requireNonNull(reasonCode, "reasonCode는 필수입니다.");
    validate(status, lastAttemptAt, lastSuccessAt, factsAsOf, stale, reasonCode);
  }

  private static void validate(
      ProviderDataHealthStatus status,
      Instant lastAttemptAt,
      Instant lastSuccessAt,
      Instant factsAsOf,
      boolean stale,
      ProviderDataHealthReason reason) {
    switch (status) {
      case DISABLED ->
          requireEmpty(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              stale,
              reason,
              ProviderDataHealthReason.PROVIDER_DISABLED);
      case NEVER_SYNCED ->
          requireNeverSynced(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              stale,
              reason,
              ProviderDataHealthReason.NO_SUCCESSFUL_IMPORT);
      case NO_RECENT_VALID_FACTS ->
          requireNoRecentFacts(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              stale,
              reason,
              ProviderDataHealthReason.VALID_FACTS_WINDOW_EXHAUSTED);
      case FRESH ->
          requireFacts(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              stale,
              reason,
              ProviderDataHealthReason.HEALTHY);
      case STALE ->
          requireFacts(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              !stale,
              reason,
              ProviderDataHealthReason.TTL_EXPIRED);
      case LAST_ATTEMPT_FAILED ->
          requireFacts(
              lastAttemptAt,
              lastSuccessAt,
              factsAsOf,
              false,
              reason,
              ProviderDataHealthReason.LATEST_RUN_FAILED);
    }
  }

  private static void requireEmpty(
      Instant attempt,
      Instant success,
      Instant facts,
      boolean stale,
      ProviderDataHealthReason actual,
      ProviderDataHealthReason expected) {
    if (attempt != null || success != null || facts != null || stale || actual != expected) {
      throw new IllegalArgumentException("상태와 시각 조합이 올바르지 않습니다.");
    }
  }

  private static void requireFacts(
      Instant attempt,
      Instant success,
      Instant facts,
      boolean invalidStale,
      ProviderDataHealthReason actual,
      ProviderDataHealthReason expected) {
    if (attempt == null
        || success == null
        || facts == null
        || success.isAfter(attempt)
        || facts.isAfter(success)
        || invalidStale
        || actual != expected) {
      throw new IllegalArgumentException("상태와 시각 조합이 올바르지 않습니다.");
    }
  }

  private static void requireNeverSynced(
      Instant attempt,
      Instant success,
      Instant facts,
      boolean stale,
      ProviderDataHealthReason actual,
      ProviderDataHealthReason expected) {
    if (attempt != null || success != null || facts != null || stale || actual != expected) {
      throw new IllegalArgumentException("상태와 시각 조합이 올바르지 않습니다.");
    }
  }

  private static void requireNoRecentFacts(
      Instant attempt,
      Instant success,
      Instant facts,
      boolean stale,
      ProviderDataHealthReason actual,
      ProviderDataHealthReason expected) {
    if (attempt == null || success != null || facts != null || stale || actual != expected) {
      throw new IllegalArgumentException("상태와 시각 조합이 올바르지 않습니다.");
    }
  }
}
