package com.timingjeju.api.application.trip;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class TripEntityTag {
  private TripEntityTag() {}

  public static String strong(UUID tripId, Instant updatedAt) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, tripId.toString());
      update(digest, updatedAt.toString());
      String opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
      return "\"trip-" + opaque + "\"";
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
