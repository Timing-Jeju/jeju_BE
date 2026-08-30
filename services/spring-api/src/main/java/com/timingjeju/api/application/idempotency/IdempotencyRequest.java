package com.timingjeju.api.application.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class IdempotencyRequest {

  public static final int MAX_BODY_BYTES = 1_048_576;

  private static final Pattern REPEATED_SLASH = Pattern.compile("/{2,}");
  private static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");

  private final IdempotencyScope scope;
  private final byte[] body;
  private final String requestHash;

  private IdempotencyRequest(IdempotencyScope scope, byte[] body, String requestHash) {
    this.scope = scope;
    this.body = body;
    this.requestHash = requestHash;
  }

  public static IdempotencyRequest create(
      UUID ownerSub, String method, String path, String idempotencyKey, byte[] body) {
    UUID parsedKey = parseKey(idempotencyKey);
    String canonicalMethod = normalizeMethod(method);
    String normalizedPath = normalizePath(path);
    byte[] copiedBody = Objects.requireNonNull(body, "request body must not be null").clone();
    if (copiedBody.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("request body는 1 MiB 이하여야 합니다.");
    }
    IdempotencyScope scope =
        new IdempotencyScope(ownerSub, canonicalMethod, normalizedPath, parsedKey);
    return new IdempotencyRequest(
        scope, copiedBody, canonicalHash(canonicalMethod, normalizedPath, copiedBody));
  }

  public UUID ownerSub() {
    return scope.ownerSub();
  }

  public String method() {
    return scope.method();
  }

  public String normalizedPath() {
    return scope.normalizedPath();
  }

  public UUID idempotencyKey() {
    return scope.idempotencyKey();
  }

  public IdempotencyScope scope() {
    return scope;
  }

  public byte[] body() {
    return body.clone();
  }

  public String requestHash() {
    return requestHash;
  }

  private static UUID parseKey(String value) {
    if (value == null || value.isBlank()) {
      throw IdempotencyException.required();
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw IdempotencyException.invalid();
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw IdempotencyException.invalid();
    }
  }

  private static String normalizeMethod(String value) {
    String method =
        Objects.requireNonNull(value, "method must not be null").toUpperCase(Locale.ROOT);
    if (!METHOD.matcher(method).matches()) {
      throw new IllegalArgumentException("HTTP method가 올바르지 않습니다.");
    }
    return method;
  }

  private static String normalizePath(String value) {
    String path = Objects.requireNonNull(value, "path must not be null");
    if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
      throw new IllegalArgumentException("path는 query와 fragment가 없는 절대 경로여야 합니다.");
    }
    String normalized = REPEATED_SLASH.matcher(path).replaceAll("/");
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.length() > 1024 || normalized.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("normalized path가 올바르지 않습니다.");
    }
    return normalized;
  }

  private static String canonicalHash(String method, String path, byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateLengthPrefixed(digest, method.getBytes(StandardCharsets.US_ASCII));
      updateLengthPrefixed(digest, path.getBytes(StandardCharsets.UTF_8));
      updateLengthPrefixed(digest, body);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
    }
  }

  private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }
}
