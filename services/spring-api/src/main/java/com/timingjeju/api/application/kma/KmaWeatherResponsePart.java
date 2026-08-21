package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Objects;

/** One exact, decompressed provider HTTP response in an import attempt. */
public record KmaWeatherResponsePart(
    String providerOperation, Integer pageNumber, byte[] payload, SnapshotPayloadFormat format) {

  public KmaWeatherResponsePart {
    providerOperation = Objects.requireNonNull(providerOperation, "providerOperation은 필수입니다.");
    payload = Objects.requireNonNull(payload, "payload는 필수입니다.").clone();
    format = Objects.requireNonNull(format, "format은 필수입니다.");
    if (pageNumber != null && pageNumber < 1) {
      throw new IllegalArgumentException("pageNumber는 1 이상이어야 합니다.");
    }
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
