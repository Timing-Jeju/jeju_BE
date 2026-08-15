package com.timingjeju.api.application.tourapi.reference;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Objects;

public record ReferenceCodeSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
  public ReferenceCodeSourceResponse {
    payload = Objects.requireNonNull(payload, "payload는 필수입니다.").clone();
    format = Objects.requireNonNull(format, "format은 필수입니다.");
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
