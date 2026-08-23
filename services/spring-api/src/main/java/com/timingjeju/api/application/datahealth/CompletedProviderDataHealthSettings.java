package com.timingjeju.api.application.datahealth;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CompletedProviderDataHealthSettings(List<ProviderDataHealthPolicy> policies) {

  public CompletedProviderDataHealthSettings {
    Objects.requireNonNull(policies, "policies는 필수입니다.");
    List<ProviderDataHealthPolicy> ordered =
        policies.stream()
            .map(policy -> Objects.requireNonNull(policy, "policy는 필수입니다."))
            .sorted((left, right) -> left.key().compareTo(right.key()))
            .toList();
    Set<ProviderDataHealthKey> actual = new HashSet<>();
    if (ordered.size() != CompletedProviderDataHealthCatalog.keys().size()
        || ordered.stream().anyMatch(policy -> !actual.add(policy.key()))
        || !actual.equals(Set.copyOf(CompletedProviderDataHealthCatalog.keys()))) {
      throw new IllegalArgumentException("완료 공급자 설정은 canonical 8개와 정확히 일치해야 합니다.");
    }
    policies = ordered;
  }
}
