package com.timingjeju.api.domain.savedplaces.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class SavedPlaceIdempotencyFingerprint {
  private static final byte[] FORMAT =
      "timing-jeju:saved-place:create:v1".getBytes(StandardCharsets.US_ASCII);
  private static final byte NULL = 0;
  private static final byte UUID_VALUE = 1;
  private static final byte UTF8_NFC = 2;
  private static final byte ARRAY = 3;
  private static final byte INT32 = 4;

  private SavedPlaceIdempotencyFingerprint() {}

  public static String sha256(SavedPlaceCommand command) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(command)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] canonicalBytes(SavedPlaceCommand command) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      output.writeInt(FORMAT.length);
      output.write(FORMAT);
      frame(output, UUID_VALUE, uuid(command.placeId()));
      nullableString(output, command.memo());
      stringArray(output, command.tags());
      frame(output, INT32, int32(command.priority()));
      nullableInt(output, command.targetDay());
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void nullableString(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      frame(output, NULL, new byte[0]);
      return;
    }
    frame(output, UTF8_NFC, utf8Nfc(value));
  }

  private static void stringArray(DataOutputStream output, List<String> values) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var elements = new DataOutputStream(bytes);
    elements.writeInt(values.size());
    for (String value : values) frame(elements, UTF8_NFC, utf8Nfc(value));
    frame(output, ARRAY, bytes.toByteArray());
  }

  private static void nullableInt(DataOutputStream output, Integer value) throws IOException {
    if (value == null) {
      frame(output, NULL, new byte[0]);
      return;
    }
    frame(output, INT32, int32(value));
  }

  private static void frame(DataOutputStream output, byte type, byte[] payload) throws IOException {
    output.writeByte(type);
    output.writeInt(payload.length);
    output.write(payload);
  }

  private static byte[] uuid(UUID value) {
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  private static byte[] int32(int value) {
    return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
  }

  private static byte[] utf8Nfc(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
  }
}
