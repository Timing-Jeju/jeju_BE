package com.timingjeju.api.application.snapshot;

import java.util.Map;

public interface SnapshotRedactor {
  String version();

  SnapshotRedactionResult redact(
      SnapshotPayloadFormat format, String charset, byte[] payload, Map<String, Object> metadata);
}
