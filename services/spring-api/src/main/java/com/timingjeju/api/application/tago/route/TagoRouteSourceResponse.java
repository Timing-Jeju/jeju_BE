package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.Objects;

public record TagoRouteSourceResponse(byte[] payload, SnapshotPayloadFormat format) {
  public TagoRouteSourceResponse {
    payload = Objects.requireNonNull(payload).clone();
    format = Objects.requireNonNull(format);
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
