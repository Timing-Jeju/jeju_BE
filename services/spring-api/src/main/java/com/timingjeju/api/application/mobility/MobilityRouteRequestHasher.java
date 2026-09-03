package com.timingjeju.api.application.mobility;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class MobilityRouteRequestHasher {
  private MobilityRouteRequestHasher() {}

  public static String hash(String sourceId, MobilityRouteRequest request) {
    String normalizedSource = requireSourceId(sourceId);
    if (request == null) throw MobilityRouteException.invalidRequest();
    MessageDigest digest = sha256();
    update(digest, "mobility-route-request-v1");
    update(digest, normalizedSource);
    update(digest, request.mode().name());
    update(digest, coordinate(request.origin().latitude()));
    update(digest, coordinate(request.origin().longitude()));
    update(digest, coordinate(request.destination().latitude()));
    update(digest, coordinate(request.destination().longitude()));
    update(digest, request.departureAt().toString());
    return HexFormat.of().formatHex(digest.digest());
  }

  static String requireSourceId(String value) {
    if (value == null || value.isBlank()) throw MobilityRouteException.invalidRequest();
    String normalized = value.strip();
    if (normalized.length() > 128 || !normalized.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
      throw MobilityRouteException.invalidRequest();
    }
    return normalized;
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String coordinate(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }
}
