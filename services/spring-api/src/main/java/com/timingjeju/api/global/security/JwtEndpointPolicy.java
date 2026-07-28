package com.timingjeju.api.global.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class JwtEndpointPolicy {

  private static final Set<String> LOCAL_HTTP_HOSTS =
      Set.of("127.0.0.1", "localhost", "::1", "[::1]", "host.docker.internal");

  private JwtEndpointPolicy() {}

  static void validate(
      URI uri, String environmentVariable, SecurityRuntimeEnvironment runtimeEnvironment) {
    if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
      throw new IllegalStateException(environmentVariable + "가 필요합니다.");
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if ("https".equals(scheme)) {
      return;
    }
    if (!"http".equals(scheme)) {
      throw new IllegalStateException(environmentVariable + "는 HTTP 또는 HTTPS URL이어야 합니다.");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (runtimeEnvironment == SecurityRuntimeEnvironment.LOCAL && LOCAL_HTTP_HOSTS.contains(host)) {
      return;
    }
    if (runtimeEnvironment == SecurityRuntimeEnvironment.LOCAL) {
      throw new IllegalStateException(
          environmentVariable + "의 HTTP는 loopback 또는 host.docker.internal 로컬 주소에서만 허용됩니다.");
    }
    throw new IllegalStateException(environmentVariable + "는 기본/운영 환경에서 HTTPS URL이어야 합니다.");
  }
}
