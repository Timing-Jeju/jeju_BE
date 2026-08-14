package com.timingjeju.api.global.tourapi;

import com.timingjeju.api.application.tourapi.TourApiRequestFingerprinter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Sha256CanonicalTourApiRequestFingerprinter
    implements TourApiRequestFingerprinter {

  private static final Set<String> EXCLUDED_KEYS =
      Set.of(
          "servicekey",
          "apikey",
          "authorization",
          "token",
          "cookie",
          "requesturl",
          "rawquery",
          "querystring");

  @Override
  public String fingerprint(Map<String, String> parameters) {
    Objects.requireNonNull(parameters, "parameters는 필수입니다.");
    StringBuilder canonical = new StringBuilder();
    parameters.entrySet().stream()
        .filter(entry -> !excluded(entry.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              String key = requireValue(entry.getKey(), "parameter key");
              String value = requireValue(entry.getValue(), "parameter value");
              canonical
                  .append(key.length())
                  .append(':')
                  .append(key)
                  .append('=')
                  .append(value.length())
                  .append(':')
                  .append(value)
                  .append('\n');
            });
    return hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private static boolean excluded(String key) {
    return key != null
        && EXCLUDED_KEYS.contains(key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT));
  }

  private static String requireValue(String value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + "는 null일 수 없습니다.");
    }
    return value;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0xf, 16));
      result.append(Character.forDigit(value & 0xf, 16));
    }
    return result.toString();
  }
}
