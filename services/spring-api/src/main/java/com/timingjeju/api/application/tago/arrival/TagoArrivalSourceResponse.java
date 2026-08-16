package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Objects;

public record TagoArrivalSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
  public TagoArrivalSourceResponse {
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    Objects.requireNonNull(format, "format은 필수입니다.");
  }
}
