package com.timingjeju.api.domain.generation.controller;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.idempotency.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.MediaType;

final class GenerationHttpBoundary {
  private GenerationHttpBoundary() {}

  static String header(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    if (values.isEmpty() && name.equals("Idempotency-Key")) throw IdempotencyException.required();
    if (values.isEmpty() && name.equals("If-Match")) throw GenerationException.ifMatchRequired();
    if (values.size() != 1 && name.equals("If-Match")) throw GenerationException.ifMatchInvalid();
    if (values.size() != 1 && name.equals("Idempotency-Key")) throw IdempotencyException.invalid();
    if (values.size() != 1) throw GenerationException.invalidRequest();
    return values.getFirst();
  }

  static byte[] body(HttpServletRequest request) {
    if (request.getQueryString() != null
        || !request.getParameterMap().isEmpty()
        || request.getHeader("Transfer-Encoding") != null)
      throw GenerationException.invalidRequest();
    try {
      if (!MediaType.APPLICATION_JSON.isCompatibleWith(
          MediaType.parseMediaType(request.getContentType())))
        throw GenerationException.invalidRequest();
    } catch (IllegalArgumentException failure) {
      throw GenerationException.invalidRequest();
    }
    long length = request.getContentLengthLong();
    if (length > 16384 || length < -1) throw GenerationException.invalidRequest();
    var declared = Collections.list(request.getHeaders("Content-Length"));
    if (!declared.isEmpty()
        && (declared.size() != 1
            || !declared.getFirst().matches("(?:0|[1-9][0-9]*)")
            || !declared.getFirst().equals(Long.toString(length))))
      throw GenerationException.invalidRequest();
    try {
      byte[] bytes = request.getInputStream().readNBytes(16385);
      if (bytes.length == 0 || bytes.length > 16384 || (length >= 0 && length != bytes.length))
        throw GenerationException.invalidRequest();
      return bytes;
    } catch (IOException failure) {
      throw GenerationException.invalidRequest();
    }
  }

  static IdempotencyRequest receipt(UUID owner, String path, String key, byte[] body) {
    if (key == null || key.isEmpty()) throw IdempotencyException.required();
    if (key.length() > 128 || key.chars().anyMatch(c -> c < 32 || c > 126))
      throw IdempotencyException.invalid();
    try {
      var hash =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII));
      var bytes = ByteBuffer.wrap(hash);
      return IdempotencyRequest.createInNamespace(
          owner,
          "POST",
          path,
          "generation-key-" + HexFormat.of().formatHex(hash),
          new UUID(bytes.getLong(), bytes.getLong()).toString(),
          body);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
