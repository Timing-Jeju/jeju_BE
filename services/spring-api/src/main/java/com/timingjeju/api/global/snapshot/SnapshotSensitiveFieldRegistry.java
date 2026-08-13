package com.timingjeju.api.global.snapshot;

import java.util.Locale;
import java.util.Set;

final class SnapshotSensitiveFieldRegistry {
  private static final Set<String> KEYS =
      Set.of(
          "servicekey",
          "apikey",
          "authorization",
          "cookie",
          "setcookie",
          "token",
          "accesstoken",
          "refreshtoken",
          "secret",
          "clientsecret",
          "password",
          "passwd",
          "email",
          "phone",
          "phonenumber",
          "mobile",
          "name",
          "username",
          "nickname",
          "userid",
          "useridentifier",
          "address",
          "roadaddress",
          "jibunaddress",
          "latitude",
          "longitude",
          "lat",
          "lng",
          "location",
          "coordinates",
          "coordx",
          "coordy",
          "mapx",
          "mapy",
          "gpsx",
          "gpsy",
          "requesturl",
          "url",
          "uri");

  private SnapshotSensitiveFieldRegistry() {}

  static boolean isSensitive(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return KEYS.contains(normalized)
        || normalized.endsWith("token")
        || normalized.endsWith("secret")
        || normalized.endsWith("password")
        || normalized.endsWith("apikey");
  }
}
