package com.timingjeju.api.application.snapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SnapshotSaveCommand(
    UUID importRunId,
    SnapshotScope scope,
    String externalRecordId,
    String pageKey,
    Integer httpStatus,
    String providerResultCode,
    Instant fetchedAt,
    Instant sourceModifiedAt,
    Instant expiresAt,
    String parserVersion,
    SnapshotPayloadFormat payloadFormat,
    String charset,
    byte[] decompressedPayload,
    Map<String, Object> requestMetadata) {

  public SnapshotSaveCommand {
    Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    Objects.requireNonNull(payloadFormat, "payloadFormat은 필수입니다.");
    Objects.requireNonNull(decompressedPayload, "decompressedPayload는 필수입니다.");
    pageKey = pageKey == null ? "" : pageKey.strip();
    parserVersion = required(parserVersion, "parserVersion", 128);
    externalRecordId = optional(externalRecordId, "externalRecordId", 512);
    providerResultCode = optional(providerResultCode, "providerResultCode", 512);
    if (pageKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512) {
      throw new IllegalArgumentException("pageKey가 너무 깁니다.");
    }
    if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
      throw new IllegalArgumentException("httpStatus가 올바르지 않습니다.");
    }
    if (sourceModifiedAt != null && sourceModifiedAt.isAfter(fetchedAt)) {
      throw new IllegalArgumentException("sourceModifiedAt이 fetchedAt보다 늦습니다.");
    }
    if (expiresAt != null && expiresAt.isBefore(fetchedAt)) {
      throw new IllegalArgumentException("expiresAt이 fetchedAt보다 빠릅니다.");
    }
    decompressedPayload = decompressedPayload.clone();
    requestMetadata = requestMetadata == null ? Map.of() : Map.copyOf(requestMetadata);
  }

  @Override
  public byte[] decompressedPayload() {
    return decompressedPayload.clone();
  }

  @Override
  public String toString() {
    return "SnapshotSaveCommand[importRunId="
        + importRunId
        + ", scope="
        + scope
        + ", pageKey="
        + pageKey
        + ", payloadFormat="
        + payloadFormat
        + ", payloadSizeBytes="
        + decompressedPayload.length
        + "]";
  }

  private static String required(String value, String name, int maxBytes) {
    String result = optional(value, name, maxBytes);
    if (result == null) {
      throw new IllegalArgumentException(name + "은 필수입니다.");
    }
    return result;
  }

  private static String optional(String value, String name, int maxBytes) {
    if (value == null) {
      return null;
    }
    String result = value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(name + "은 비어 있을 수 없습니다.");
    }
    if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException(name + "이 너무 깁니다.");
    }
    return result;
  }
}
