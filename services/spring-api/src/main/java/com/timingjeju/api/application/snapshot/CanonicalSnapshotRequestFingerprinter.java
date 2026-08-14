package com.timingjeju.api.application.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CanonicalSnapshotRequestFingerprinter {

  public static final String SCHEMA_VERSION = "snapshot-request-v1";

  private CanonicalSnapshotRequestFingerprinter() {}

  public static String fingerprint(
      SnapshotScope scope, String pageKey, String canonicalFingerprintMetadataJson) {
    Objects.requireNonNull(scope, "scope는 필수입니다.");
    StringBuilder canonical = new StringBuilder();
    append(canonical, SCHEMA_VERSION);
    append(canonical, scope.provider());
    append(canonical, scope.service());
    append(canonical, scope.operation());
    append(canonical, scope.scopeKey());
    append(canonical, Objects.requireNonNull(pageKey, "pageKey는 필수입니다."));
    append(
        canonical,
        Objects.requireNonNull(
            canonicalFingerprintMetadataJson, "canonicalFingerprintMetadataJson은 필수입니다."));
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder canonical, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    canonical.append(bytes.length).append(':').append(value);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
    }
  }
}
