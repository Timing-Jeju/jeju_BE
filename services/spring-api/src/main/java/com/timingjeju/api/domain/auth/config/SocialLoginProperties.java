package com.timingjeju.api.domain.auth.config;

import com.timingjeju.api.domain.auth.service.SocialLoginProvider;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.social-login")
public record SocialLoginProperties(List<String> providerIds) {

  public SocialLoginProperties {
    providerIds = normalizeProviderIds(providerIds);
  }

  private static List<String> normalizeProviderIds(List<String> providerIds) {
    if (providerIds == null || providerIds.isEmpty()) {
      throw new IllegalArgumentException("최소 한 개의 소셜 로그인 공급자가 필요합니다.");
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String providerId : providerIds) {
      if (providerId == null || !providerId.equals(providerId.trim())) {
        throw new IllegalArgumentException("소셜 로그인 공급자 식별자가 올바르지 않습니다.");
      }
      if (SocialLoginProvider.fromId(providerId).isEmpty() || !normalized.add(providerId)) {
        throw new IllegalArgumentException("소셜 로그인 공급자 식별자가 중복되었거나 허용되지 않았습니다.");
      }
    }
    return List.copyOf(normalized);
  }
}
