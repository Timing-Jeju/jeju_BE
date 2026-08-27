package com.timingjeju.api.domain.savedplaces.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class SavedPlaceEtag {
  private SavedPlaceEtag() {}

  public static String strong(UUID placeId, Instant updatedAt) {
    String canonical = placeId + "\n" + updatedAt;
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8)));
      return "\"sp-" + digest.substring(0, 32) + "\"";
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
