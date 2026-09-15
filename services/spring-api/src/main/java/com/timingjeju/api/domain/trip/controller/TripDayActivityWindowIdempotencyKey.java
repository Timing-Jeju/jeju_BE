package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

final class TripDayActivityWindowIdempotencyKey {
  static final int MAX_LENGTH = 128;
  private static final String PRINTABLE_SCOPE_NAMESPACE = "trip-day-window-printable-v1";
  private static final byte[] DOMAIN =
      "timing-jeju:trip-day-window-idempotency-key:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private TripDayActivityWindowIdempotencyKey() {}

  static IdempotencyRequest createRequest(
      UUID ownerSub, String method, String path, String value, byte[] body) {
    validate(value);
    if (isCanonicalUuid(value)) {
      return IdempotencyRequest.create(ownerSub, method, path, value, body);
    }
    byte[] fullDigest = digest(value);
    String scopeNamespace = PRINTABLE_SCOPE_NAMESPACE + "-" + HexFormat.of().formatHex(fullDigest);
    return IdempotencyRequest.createInNamespace(
        ownerSub, method, path, scopeNamespace, toRegistryKey(fullDigest), body);
  }

  static String toRegistryKey(String value) {
    validate(value);
    if (isCanonicalUuid(value)) {
      return value;
    }
    return toRegistryKey(digest(value));
  }

  private static String toRegistryKey(byte[] fullDigest) {
    byte[] registryBytes = fullDigest.clone();
    registryBytes[6] = (byte) ((registryBytes[6] & 0x0f) | 0x80);
    registryBytes[8] = (byte) ((registryBytes[8] & 0x3f) | 0x80);
    ByteBuffer bytes = ByteBuffer.wrap(registryBytes);
    return new UUID(bytes.getLong(), bytes.getLong()).toString();
  }

  private static void validate(String value) {
    if (value == null || value.isEmpty()) {
      throw IdempotencyException.required();
    }
    if (value.length() > MAX_LENGTH
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw IdempotencyException.invalid();
    }
  }

  private static boolean isCanonicalUuid(String value) {
    if (CANONICAL_UUID.matcher(value).matches()) {
      try {
        UUID parsed = UUID.fromString(value);
        if (parsed.toString().equals(value)) {
          return true;
        }
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    }
    return false;
  }

  private static byte[] digest(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(DOMAIN);
      return digest.digest(value.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
    }
  }
}
