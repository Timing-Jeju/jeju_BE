package com.timingjeju.api.application.pagination;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CursorCodec {

  private static final int VERSION = 1;
  private static final int MAX_ENCODED_LENGTH = 2048;
  private static final int MAX_DECODED_JSON_BYTES = 1024;
  private static final Set<String> SIGNED_PAYLOAD_KEYS =
      Set.of("endpoint", "filter", "sort", "sortValue", "tieBreaker", "v");

  private final byte[] signingKey;

  private CursorCodec(String signingKey) {
    if (signingKey == null || signingKey.length() < 32) {
      throw new IllegalArgumentException("signingKey must be at least 32 characters");
    }
    this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
  }

  public static CursorCodec hmacSha256(String signingKey) {
    return new CursorCodec(signingKey);
  }

  public String encode(CursorContext context, CursorPosition position) {
    Map<String, String> unsignedPayload = unsignedPayload(context, position);
    String signature = hmacSha256Hex(canonicalJson(unsignedPayload));
    LinkedHashMap<String, String> signedPayload = new LinkedHashMap<>(unsignedPayload);
    signedPayload.put("sig", signature);
    byte[] signedJsonBytes = canonicalJson(signedPayload).getBytes(StandardCharsets.UTF_8);
    if (signedJsonBytes.length > MAX_DECODED_JSON_BYTES) {
      throw new IllegalArgumentException("decoded cursor payload is too long");
    }
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signedJsonBytes);
    if (encoded.length() > MAX_ENCODED_LENGTH) {
      throw new IllegalArgumentException("encoded cursor is too long");
    }
    return encoded;
  }

  public CursorPosition decode(String cursor, CursorContext expectedContext) {
    if (cursor == null || cursor.isBlank() || cursor.length() > MAX_ENCODED_LENGTH) {
      throw new CursorInvalidException();
    }
    byte[] decodedBytes;
    try {
      decodedBytes = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException exception) {
      throw new CursorInvalidException();
    }
    if (decodedBytes.length > MAX_DECODED_JSON_BYTES) {
      throw new CursorInvalidException();
    }
    Map<String, String> payload = parseFlatJson(new String(decodedBytes, StandardCharsets.UTF_8));
    String suppliedSignature = payload.remove("sig");
    if (!payload.keySet().equals(SIGNED_PAYLOAD_KEYS)) {
      throw new CursorInvalidException();
    }
    if (suppliedSignature == null
        || !suppliedSignature.equals(hmacSha256Hex(canonicalJson(payload)))) {
      throw new CursorInvalidException();
    }
    if (!String.valueOf(VERSION).equals(payload.get("v"))) {
      throw new CursorInvalidException();
    }
    if (!expectedContext.endpoint().equals(payload.get("endpoint"))
        || !expectedContext.sort().token().equals(payload.get("sort"))
        || !expectedContext.normalizedFilterFingerprint().equals(payload.get("filter"))) {
      throw new CursorContextMismatchException();
    }
    String sortValue = payload.get("sortValue");
    String tieBreaker = payload.get("tieBreaker");
    if (sortValue == null || tieBreaker == null) {
      throw new CursorInvalidException();
    }
    return new CursorPosition(sortValue, tieBreaker);
  }

  private static Map<String, String> unsignedPayload(
      CursorContext context, CursorPosition position) {
    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    payload.put("endpoint", context.endpoint());
    payload.put("filter", context.normalizedFilterFingerprint());
    payload.put("sort", context.sort().token());
    payload.put("sortValue", position.sortValue());
    payload.put("tieBreaker", position.tieBreaker());
    payload.put("v", String.valueOf(VERSION));
    return payload;
  }

  private static String canonicalJson(Map<String, String> payload) {
    return payload.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                quote(entry.getKey())
                    + ":"
                    + ("v".equals(entry.getKey()) ? entry.getValue() : quote(entry.getValue())))
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private static Map<String, String> parseFlatJson(String json) {
    try {
      FlatJsonParser parser = new FlatJsonParser(json);
      return parser.parse();
    } catch (IllegalArgumentException exception) {
      throw new CursorInvalidException();
    }
  }

  private String hmacSha256Hex(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append("%02x".formatted(b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
      throw new IllegalStateException("HmacSHA256 is not available", exception);
    }
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

  private static final class FlatJsonParser {

    private final String json;
    private int index;

    private FlatJsonParser(String json) {
      this.json = json;
    }

    private Map<String, String> parse() {
      LinkedHashMap<String, String> values = new LinkedHashMap<>();
      expect('{');
      if (peek('}')) {
        throw new IllegalArgumentException("empty object");
      }
      do {
        String key = string();
        expect(':');
        String value = "v".equals(key) ? number() : string();
        values.put(key, value);
      } while (consume(','));
      expect('}');
      if (index != json.length()) {
        throw new IllegalArgumentException("trailing content");
      }
      return values;
    }

    private String string() {
      expect('"');
      StringBuilder builder = new StringBuilder();
      while (index < json.length()) {
        char character = json.charAt(index++);
        if (character == '"') {
          return builder.toString();
        }
        if (character == '\\') {
          if (index >= json.length()) {
            throw new IllegalArgumentException("invalid escape");
          }
          char escaped = json.charAt(index++);
          switch (escaped) {
            case '"', '\\', '/' -> builder.append(escaped);
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'u' -> builder.append(unicode());
            default -> throw new IllegalArgumentException("invalid escape");
          }
        } else {
          builder.append(character);
        }
      }
      throw new IllegalArgumentException("unterminated string");
    }

    private char unicode() {
      if (index + 4 > json.length()) {
        throw new IllegalArgumentException("invalid unicode");
      }
      String hex = json.substring(index, index + 4);
      index += 4;
      return (char) Integer.parseInt(hex, 16);
    }

    private String number() {
      int start = index;
      while (index < json.length() && Character.isDigit(json.charAt(index))) {
        index++;
      }
      if (start == index) {
        throw new IllegalArgumentException("number expected");
      }
      return json.substring(start, index);
    }

    private boolean consume(char expected) {
      if (peek(expected)) {
        index++;
        return true;
      }
      return false;
    }

    private boolean peek(char expected) {
      return index < json.length() && json.charAt(index) == expected;
    }

    private void expect(char expected) {
      if (!consume(expected)) {
        throw new IllegalArgumentException("expected " + expected);
      }
    }
  }
}
