package com.timingjeju.api.global.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class McpEndpointPolicy {
  private McpEndpointPolicy() {}

  public static void requirePrivateHttps(URI baseUrl, String allowedHost) {
    if (baseUrl == null
        || allowedHost == null
        || allowedHost.isBlank()
        || !"https".equalsIgnoreCase(baseUrl.getScheme())
        || baseUrl.getHost() == null
        || !baseUrl.getHost().equalsIgnoreCase(allowedHost)
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null
        || (baseUrl.getPath() != null
            && !baseUrl.getPath().isEmpty()
            && !"/".equals(baseUrl.getPath()))
        || baseUrl.getPort() == 0
        || baseUrl.getPort() > 65_535
        || !isPrivateHost(allowedHost)) {
      throw new IllegalArgumentException("MCP endpoint는 명시한 private HTTPS host여야 합니다.");
    }
  }

  private static boolean isPrivateHost(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.equals("localhost")
        || normalized.endsWith(".internal")
        || normalized.endsWith(".local")
        || normalized.endsWith(".svc")
        || normalized.endsWith(".cluster.local")
        || (!normalized.contains(".") && !normalized.contains(":"))) {
      return true;
    }
    if (!looksLikeIpLiteral(normalized)) return false;
    try {
      InetAddress address = InetAddress.getByName(normalized);
      return !address.isAnyLocalAddress()
          && (address.isLoopbackAddress()
              || address.isSiteLocalAddress()
              || address.isLinkLocalAddress());
    } catch (UnknownHostException exception) {
      return false;
    }
  }

  private static boolean looksLikeIpLiteral(String host) {
    return host.contains(":") || host.matches("[0-9.]+");
  }
}
