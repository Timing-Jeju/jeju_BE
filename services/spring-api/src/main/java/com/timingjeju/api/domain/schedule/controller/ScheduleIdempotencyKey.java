package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Pattern;

final class ScheduleIdempotencyKey {
  static final int MAX_LENGTH = 128;
  private static final byte[] DOMAIN =
      "timing-jeju:schedule-idempotency-key:v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private ScheduleIdempotencyKey() {}

  static String toRegistryKey(String value) {
    if (value == null || value.isEmpty()) {
      throw IdempotencyException.required();
    }
    if (value.length() > MAX_LENGTH
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw IdempotencyException.invalid();
    }
    if (CANONICAL_UUID.matcher(value).matches()) {
      try {
        UUID parsed = UUID.fromString(value);
        if (parsed.toString().equals(value)) {
          return value;
        }
      } catch (IllegalArgumentException ignored) {
        // Printable non-UUID keys use the deterministic registry mapping below.
      }
    }
    byte[] digest = digest(value);
    digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
    digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
    ByteBuffer bytes = ByteBuffer.wrap(digest);
    return new UUID(bytes.getLong(), bytes.getLong()).toString();
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
