package com.timingjeju.api.application.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Arrays;
import java.util.Objects;

public record DetailSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
  public DetailSourceResponse {
    payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload는 필수입니다."), payload.length);
    format = Objects.requireNonNull(format, "format은 필수입니다.");
  }

  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
