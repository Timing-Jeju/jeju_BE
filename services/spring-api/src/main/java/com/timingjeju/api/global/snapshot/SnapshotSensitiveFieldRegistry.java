package com.timingjeju.api.global.snapshot;

import java.util.Locale;
import java.util.Set;

final class SnapshotSensitiveFieldRegistry {
  private static final Set<String> EXACT_KEYS =
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
  private static final Set<String> PERSONAL_NAME_KEYS = Set.of("firstname", "lastname", "fullname");
  private static final Set<String> PERSONAL_CONTACT_KEYS =
      Set.of("useremail", "homephone", "postaladdress");
  private static final Set<String> PERSONAL_IDENTIFIER_KEYS =
      Set.of("userid", "accountid", "deviceid");
  private static final Set<String> PRECISE_LOCATION_SUFFIXES =
      Set.of("latitude", "longitude", "coordinates", "location");

  private SnapshotSensitiveFieldRegistry() {}

  static boolean isSensitive(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return EXACT_KEYS.contains(normalized)
        || PERSONAL_NAME_KEYS.contains(normalized)
        || PERSONAL_CONTACT_KEYS.contains(normalized)
        || PERSONAL_IDENTIFIER_KEYS.contains(normalized)
        || hasPreciseLocationSuffix(normalized)
        || normalized.endsWith("token")
        || normalized.endsWith("secret")
        || normalized.endsWith("password")
        || normalized.endsWith("apikey");
  }

  private static boolean hasPreciseLocationSuffix(String normalized) {
    return PRECISE_LOCATION_SUFFIXES.stream()
        .anyMatch(
            suffix ->
                normalized.endsWith(suffix)
                    && normalized.length() > suffix.length()
                    && (normalized.startsWith("home")
                        || normalized.startsWith("pickup")
                        || normalized.startsWith("user")
                        || normalized.startsWith("device")
                        || normalized.startsWith("current")
                        || normalized.startsWith("precise")));
  }
}
