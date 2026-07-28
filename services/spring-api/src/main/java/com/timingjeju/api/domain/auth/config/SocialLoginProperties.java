package com.timingjeju.api.domain.auth.config;

import com.timingjeju.api.domain.auth.service.SocialLoginProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.social-login")
public record SocialLoginProperties(List<String> enabledProviderIds, List<String> redirectUrls) {

  private static final Set<String> LOCAL_HTTP_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

  public SocialLoginProperties {
    enabledProviderIds = normalizeProviderIds(enabledProviderIds);
    redirectUrls = normalizeRedirectUrls(redirectUrls);
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

  private static List<String> normalizeRedirectUrls(List<String> urls) {
    if (urls == null || urls.isEmpty()) {
      throw new IllegalArgumentException("소셜 로그인 redirect allowlist가 필요합니다.");
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String url : urls) {
      normalized.add(validateAndNormalizeRedirect(url));
    }
    if (normalized.size() != urls.size()) {
      throw new IllegalArgumentException("소셜 로그인 redirect allowlist에 중복 URL이 있습니다.");
    }
    return List.copyOf(normalized);
  }

  private static String validateAndNormalizeRedirect(String value) {
    if (value == null || value.isBlank() || !value.equals(value.trim()) || value.contains("*")) {
      throw invalidRedirect();
    }
    try {
      URI original = new URI(value);
      URI uri = original.normalize();
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null
          || host == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPath() == null
          || !uri.getPath().startsWith("/")
          || uri.getPath().contains("..")
          || !uri.equals(original)) {
        throw invalidRedirect();
      }
      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      boolean https = "https".equals(normalizedScheme);
      boolean localHttp =
          "http".equals(normalizedScheme) && LOCAL_HTTP_HOSTS.contains(normalizedHost);
      if (!https && !localHttp) {
        throw invalidRedirect();
      }
      return uri.toString();
    } catch (URISyntaxException | IllegalArgumentException exception) {
      throw invalidRedirect();
    }
  }

  private static IllegalArgumentException invalidRedirect() {
    return new IllegalArgumentException(
        "소셜 로그인 redirect URL은 wildcard, query, fragment 없는 정확한 https 또는 localhost http URL이어야 합니다.");
  }
}
