package com.timingjeju.api.global.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.cors")
public record AppCorsProperties(List<String> allowedOrigins) {

  public AppCorsProperties {
    allowedOrigins =
        allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .map(AppCorsProperties::normalizeOrigin)
                .distinct()
                .toList();
    if (allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("CORS 허용 Origin을 하나 이상 설정해야 합니다.");
    }
  }

  private static String normalizeOrigin(String value) {
    if (value.contains("*")) {
      throw new IllegalArgumentException("CORS 허용 Origin에는 wildcard를 사용할 수 없습니다.");
    }
    final URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException exception) {
      throw invalidOrigin();
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    int port = uri.getPort();
    if (scheme == null
        || host == null
        || uri.isOpaque()
        || uri.getRawUserInfo() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || value.contains("%")) {
      throw invalidOrigin();
    }
    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
      throw invalidOrigin();
    }
    if (port == 0 || port > 65535) {
      throw invalidOrigin();
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    if (normalizedHost.contains(":")) {
      normalizedHost = normalizedHost.startsWith("[") ? normalizedHost : "[" + normalizedHost + "]";
    }
    String sourceAuthority = normalizedHost + (port == -1 ? "" : ":" + port);
    if (!sourceAuthority.equalsIgnoreCase(uri.getRawAuthority())) {
      throw invalidOrigin();
    }
    int canonicalPort = isDefaultPort(normalizedScheme, port) ? -1 : port;
    String canonicalAuthority = normalizedHost + (canonicalPort == -1 ? "" : ":" + canonicalPort);
    return normalizedScheme + "://" + canonicalAuthority;
  }

  private static boolean isDefaultPort(String scheme, int port) {
    return (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
  }

  private static IllegalArgumentException invalidOrigin() {
    return new IllegalArgumentException(
        "CORS 허용 Origin은 http/https scheme, host와 선택적 유효 port만 포함해야 합니다.");
  }
}
