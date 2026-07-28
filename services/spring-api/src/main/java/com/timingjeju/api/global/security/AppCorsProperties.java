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
    requireBrowserCanonicalNumericHost(normalizedHost);
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

  private static void requireBrowserCanonicalNumericHost(String host) {
    String unwrappedHost =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (unwrappedHost.contains(":")) {
      if (!unwrappedHost.equals(canonicalIpv6(unwrappedHost))) {
        throw invalidOrigin();
      }
      return;
    }
    if (isWhatwgNumericIpv4Candidate(unwrappedHost) && !isCanonicalIpv4(unwrappedHost)) {
      throw invalidOrigin();
    }
  }

  private static boolean isWhatwgNumericIpv4Candidate(String host) {
    return Arrays.stream(host.split("\\.", -1)).allMatch(AppCorsProperties::isNumericIpv4Component);
  }

  private static boolean isNumericIpv4Component(String component) {
    if (component.isEmpty()) {
      return false;
    }
    if (component.startsWith("0x")) {
      return component.length() > 2
          && component.substring(2).chars().allMatch(AppCorsProperties::isAsciiHexDigit);
    }
    return component.chars().allMatch(AppCorsProperties::isAsciiDigit);
  }

  private static boolean isCanonicalIpv4(String host) {
    String[] octets = host.split("\\.", -1);
    if (octets.length != 4) {
      return false;
    }
    for (String octet : octets) {
      if (octet.isEmpty()
          || (octet.length() > 1 && octet.startsWith("0"))
          || octet.length() > 3
          || !octet.chars().allMatch(AppCorsProperties::isAsciiDigit)
          || Integer.parseInt(octet) > 255) {
        return false;
      }
    }
    return true;
  }

  private static String canonicalIpv6(String address) {
    if (address.contains(".")) {
      return null;
    }
    int compressionIndex = address.indexOf("::");
    if (compressionIndex != address.lastIndexOf("::")) {
      return null;
    }
    String[] leftGroups =
        ipv6Groups(compressionIndex == -1 ? address : address.substring(0, compressionIndex));
    String[] rightGroups =
        compressionIndex == -1
            ? new String[0]
            : ipv6Groups(address.substring(compressionIndex + 2));
    int explicitGroupCount = leftGroups.length + rightGroups.length;
    if ((compressionIndex == -1 && explicitGroupCount != 8)
        || (compressionIndex != -1 && explicitGroupCount >= 8)) {
      return null;
    }

    int[] groups = new int[8];
    int cursor = 0;
    for (String group : leftGroups) {
      int parsed = parseIpv6Group(group);
      if (parsed < 0) {
        return null;
      }
      groups[cursor++] = parsed;
    }
    cursor = 8 - rightGroups.length;
    for (String group : rightGroups) {
      int parsed = parseIpv6Group(group);
      if (parsed < 0) {
        return null;
      }
      groups[cursor++] = parsed;
    }
    return formatCanonicalIpv6(groups);
  }

  private static String[] ipv6Groups(String value) {
    return value.isEmpty() ? new String[0] : value.split(":", -1);
  }

  private static int parseIpv6Group(String group) {
    if (group.isEmpty()
        || group.length() > 4
        || !group.chars().allMatch(AppCorsProperties::isAsciiHexDigit)) {
      return -1;
    }
    try {
      return Integer.parseInt(group, 16);
    } catch (NumberFormatException exception) {
      return -1;
    }
  }

  private static boolean isAsciiDigit(int character) {
    return character >= '0' && character <= '9';
  }

  private static boolean isAsciiHexDigit(int character) {
    return isAsciiDigit(character) || (character >= 'a' && character <= 'f');
  }

  private static String formatCanonicalIpv6(int[] groups) {
    int bestStart = -1;
    int bestLength = 1;
    for (int index = 0; index < groups.length; ) {
      if (groups[index] != 0) {
        index++;
        continue;
      }
      int end = index;
      while (end < groups.length && groups[end] == 0) {
        end++;
      }
      if (end - index > bestLength) {
        bestStart = index;
        bestLength = end - index;
      }
      index = end;
    }
    if (bestStart == -1) {
      return joinIpv6Groups(groups, 0, groups.length);
    }
    String left = joinIpv6Groups(groups, 0, bestStart);
    String right = joinIpv6Groups(groups, bestStart + bestLength, groups.length);
    return left + "::" + right;
  }

  private static String joinIpv6Groups(int[] groups, int start, int end) {
    StringBuilder result = new StringBuilder();
    for (int index = start; index < end; index++) {
      if (!result.isEmpty()) {
        result.append(':');
      }
      result.append(Integer.toHexString(groups[index]));
    }
    return result.toString();
  }

  private static IllegalArgumentException invalidOrigin() {
    return new IllegalArgumentException(
        "CORS 허용 Origin은 http/https scheme, host와 선택적 유효 port만 포함해야 합니다.");
  }
}
