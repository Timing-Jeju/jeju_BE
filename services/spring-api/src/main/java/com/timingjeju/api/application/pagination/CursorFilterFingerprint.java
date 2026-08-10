package com.timingjeju.api.application.pagination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class CursorFilterFingerprint {

  private CursorFilterFingerprint() {}

  public static String sha256(Map<String, ?> filters) {
    return sha256Hex(canonicalJson(normalizeMap(filters)));
  }

  private static Map<String, Object> normalizeMap(Map<String, ?> filters) {
    TreeMap<String, Object> normalized = new TreeMap<>();
    if (filters == null) {
      return normalized;
    }
    filters.forEach(
        (key, value) -> {
          Object normalizedValue = normalize(value);
          if (key != null && normalizedValue != null) {
            normalized.put(Normalizer.normalize(key.trim(), Normalizer.Form.NFC), normalizedValue);
          }
        });
    return normalized;
  }

  private static Object normalize(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof CharSequence chars) {
      String normalized = Normalizer.normalize(chars.toString().trim(), Normalizer.Form.NFC);
      return normalized.isEmpty() ? null : normalized;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> normalized =
          collection.stream()
              .map(CursorFilterFingerprint::normalize)
              .filter(element -> element != null)
              .sorted(Comparator.comparing(CursorFilterFingerprint::canonicalJson))
              .collect(Collectors.toCollection(ArrayList::new));
      return normalized.isEmpty() ? null : normalized;
    }
    if (value instanceof Map<?, ?> map) {
      TreeMap<String, Object> nested = new TreeMap<>();
      map.forEach(
          (key, nestedValue) -> {
            Object normalizedValue = normalize(nestedValue);
            if (key != null && normalizedValue != null) {
              nested.put(
                  Normalizer.normalize(String.valueOf(key).trim(), Normalizer.Form.NFC),
                  normalizedValue);
            }
          });
      return nested.isEmpty() ? null : nested;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value;
    }
    return Normalizer.normalize(String.valueOf(value).trim(), Normalizer.Form.NFC);
  }

  private static String canonicalJson(Object value) {
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(
              entry ->
                  quote(String.valueOf(entry.getKey())) + ":" + canonicalJson(entry.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream()
          .map(CursorFilterFingerprint::canonicalJson)
          .collect(Collectors.joining(",", "[", "]"));
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    return quote((String) value);
  }

  private static String quote(String value) {
    StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (character < 0x20) {
            builder.append("\\u%04x".formatted((int) character));
          } else {
            builder.append(character);
          }
        }
      }
    }
    return builder.append('"').toString();
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append("%02x".formatted(b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
