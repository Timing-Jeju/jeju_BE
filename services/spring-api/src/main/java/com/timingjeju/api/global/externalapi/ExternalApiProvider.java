package com.timingjeju.api.global.externalapi;

import java.net.URI;

public enum ExternalApiProvider {
  TOUR_API(
      "TOUR_API",
      "tourApi",
      "apis.data.go.kr",
      "/B551011/KorService2",
      ExternalApiCredentialPlacement.QUERY_SERVICE_KEY),
  TAGO(
      "TAGO",
      "tago",
      "apis.data.go.kr",
      "/1613000",
      ExternalApiCredentialPlacement.QUERY_SERVICE_KEY),
  TMAP("TMAP", "tmap", "apis.openapi.sk.com", "", ExternalApiCredentialPlacement.HEADER_API_KEY),
  KMA(
      "KMA",
      "kma",
      "apis.data.go.kr",
      "/1360000/VilageFcstInfoService_2.0",
      ExternalApiCredentialPlacement.QUERY_SERVICE_KEY);

  private final String environmentPrefix;
  private final String actuatorName;
  private final String allowedHost;
  private final String allowedBasePath;
  private final ExternalApiCredentialPlacement credentialPlacement;

  ExternalApiProvider(
      String environmentPrefix,
      String actuatorName,
      String allowedHost,
      String allowedBasePath,
      ExternalApiCredentialPlacement credentialPlacement) {
    this.environmentPrefix = environmentPrefix;
    this.actuatorName = actuatorName;
    this.allowedHost = allowedHost;
    this.allowedBasePath = allowedBasePath;
    this.credentialPlacement = credentialPlacement;
  }

  String environmentName(String suffix) {
    return environmentPrefix + "_" + suffix;
  }

  String actuatorName() {
    return actuatorName;
  }

  public ExternalApiCredentialPlacement credentialPlacement() {
    return credentialPlacement;
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
