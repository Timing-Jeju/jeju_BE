package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Objects;

public record TagoStopSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
  public TagoStopSourceResponse {
    payload = Objects.requireNonNull(payload, "payload는 필수입니다.").clone();
    format = Objects.requireNonNull(format, "format은 필수입니다.");
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
