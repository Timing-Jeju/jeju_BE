package com.timingjeju.api.application.timetable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical, length-delimited digests used to decide timetable import replay. */
public final class TimetableImportFingerprint {
  private TimetableImportFingerprint() {}

  public static String mapping(OperatorTimetableMapping mapping) {
    Objects.requireNonNull(mapping);
    MessageDigest digest = sha256();
    field(digest, "version", mapping.version());
    field(digest, "datasetId", mapping.datasetId());
    field(digest, "scheduleId", mapping.scheduleId());
    field(digest, "routeNo", mapping.routeNo());
    field(digest, "effectiveDate", mapping.effectiveDate().toString());
    field(digest, "routeId", mapping.routeId().toString());
    field(digest, "routeSourceProvider", mapping.routeSourceProvider());
    field(digest, "routeCityCode", mapping.routeCityCode());
    List<Map.Entry<String, OperatorTimetableMapping.Direction>> directions =
        mapping.directions().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    count(digest, "directions", directions.size());
    for (Map.Entry<String, OperatorTimetableMapping.Direction> item : directions) {
      field(digest, "sheet", item.getKey());
      OperatorTimetableMapping.Direction direction = item.getValue();
      field(digest, "directionKey", direction.directionKey());
      field(digest, "metadataDirection", direction.metadataDirection());
      field(digest, "operationsSha256", direction.operationsSha256());
      count(digest, "headers", direction.headers().size());
      for (OperatorTimetableMapping.Header header : direction.headers()) {
        field(digest, "exactLabel", header.exactLabel());
        field(digest, "stopId", header.stopId() == null ? null : header.stopId().toString());
        field(digest, "unresolved", Boolean.toString(header.unresolved()));
        List<Map.Entry<String, java.util.UUID>> overrides =
            header.annotationOverrides().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        count(digest, "annotationOverrides", overrides.size());
        for (Map.Entry<String, java.util.UUID> override : overrides) {
          field(digest, "annotation", override.getKey());
          field(digest, "annotationStopId", override.getValue().toString());
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String compute(
      String rawSha256,
      String mappingFingerprint,
      String canonicalManifest,
      List<String> recordKeys,
      List<String> omissions) {
    requireDigest("rawSha256", rawSha256);
    requireDigest("mappingFingerprint", mappingFingerprint);
    MessageDigest digest = sha256();
    field(digest, "rawSha256", rawSha256);
    field(digest, "mappingFingerprint", mappingFingerprint);
    field(digest, "canonicalManifest", Objects.requireNonNull(canonicalManifest));
    sequence(digest, "recordKeys", recordKeys);
    sequence(digest, "omissions", omissions);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void sequence(MessageDigest digest, String name, List<String> values) {
    List<String> immutable = List.copyOf(values);
    count(digest, name, immutable.size());
    for (String value : immutable) field(digest, name + "Item", value);
  }

  private static void count(MessageDigest digest, String name, int value) {
    field(digest, name + "Count", Integer.toString(value));
  }

  private static void field(MessageDigest digest, String name, String value) {
    bytes(digest, Normalizer.normalize(name, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
    if (value == null) {
      digest.update((byte) 0);
      return;
    }
    digest.update((byte) 1);
    bytes(
        digest, Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
  }

  private static void bytes(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static void requireDigest(String name, String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be lowercase SHA-256");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
