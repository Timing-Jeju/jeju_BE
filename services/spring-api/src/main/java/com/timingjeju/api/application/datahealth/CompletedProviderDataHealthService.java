package com.timingjeju.api.application.datahealth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CompletedProviderDataHealthService {
  private final ProviderDataHealthReader reader;
  private final Clock clock;
  private final List<ProviderDataHealthPolicy> policies;

  public CompletedProviderDataHealthService(
      ProviderDataHealthReader reader, Clock clock, CompletedProviderDataHealthSettings settings) {
    this.reader = Objects.requireNonNull(reader, "reader는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.policies = Objects.requireNonNull(settings, "settings는 필수입니다.").policies();
  }

  public List<ProviderDataHealthItem> collect() {
    Instant evaluatedAt = clock.instant();
    List<ProviderDataHealthKey> enabledKeys =
        policies.stream()
            .filter(ProviderDataHealthPolicy::enabled)
            .map(ProviderDataHealthPolicy::key)
            .toList();
    Map<ProviderDataHealthKey, ProviderDataHealthHistory> histories =
        index(reader.read(enabledKeys), enabledKeys, evaluatedAt);
    return policies.stream()
        .map(
            policy ->
                ProviderDataHealthEvaluator.evaluate(
                    policy, histories.get(policy.key()), evaluatedAt))
        .toList();
  }

  private static Map<ProviderDataHealthKey, ProviderDataHealthHistory> index(
      List<ProviderDataHealthHistory> rows,
      List<ProviderDataHealthKey> enabledKeys,
      Instant evaluatedAt) {
    Objects.requireNonNull(rows, "reader 결과는 필수입니다.");
    Set<ProviderDataHealthKey> allowed = Set.copyOf(enabledKeys);
    Map<ProviderDataHealthKey, ProviderDataHealthHistory> indexed = new HashMap<>();
    for (ProviderDataHealthHistory row : rows) {
      if (row == null
          || !allowed.contains(row.key())
          || row.lastAttemptAt().isAfter(evaluatedAt)
          || (row.lastSuccessAt() != null && row.lastSuccessAt().isAfter(evaluatedAt))
          || (row.factsAsOf() != null && row.factsAsOf().isAfter(evaluatedAt))
          || indexed.putIfAbsent(row.key(), row) != null) {
        throw ProviderDataHealthException.unavailable();
      }
    }
    return indexed;
  }
}
