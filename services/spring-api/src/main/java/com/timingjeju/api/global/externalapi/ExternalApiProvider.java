package com.timingjeju.api.global.externalapi;

import java.net.URI;

public enum ExternalApiProvider {
  TOUR_API("TOUR_API", "tourApi", "apis.data.go.kr", "/B551011/KorService2"),
  TAGO("TAGO", "tago", "apis.data.go.kr", "/1613000"),
  TMAP("TMAP", "tmap", "apis.openapi.sk.com", ""),
  KMA("KMA", "kma", "apis.data.go.kr", "/1360000/VilageFcstInfoService_2.0");

  private final String environmentPrefix;
  private final String actuatorName;
  private final String allowedHost;
  private final String allowedBasePath;

  ExternalApiProvider(
      String environmentPrefix, String actuatorName, String allowedHost, String allowedBasePath) {
    this.environmentPrefix = environmentPrefix;
    this.actuatorName = actuatorName;
    this.allowedHost = allowedHost;
    this.allowedBasePath = allowedBasePath;
  }

  String environmentName(String suffix) {
    return environmentPrefix + "_" + suffix;
  }

  String actuatorName() {
    return actuatorName;
  }

  boolean allows(URI uri) {
    return uri != null
        && allowedHost.equalsIgnoreCase(uri.getHost())
        && allowedBasePath.equals(normalizePath(uri.getPath()))
        && uri.getPort() == -1
        && uri.getUserInfo() == null
        && uri.getQuery() == null
        && uri.getFragment() == null;
  }

  private static String normalizePath(String path) {
    if (path == null || path.isEmpty() || "/".equals(path)) {
      return "";
    }
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }
}
