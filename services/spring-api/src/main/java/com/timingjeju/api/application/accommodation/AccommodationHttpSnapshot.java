package com.timingjeju.api.application.accommodation;

import java.util.Objects;

public record AccommodationHttpSnapshot(
    int status, String contentType, String location, String etag, byte[] body) {
  public AccommodationHttpSnapshot {
    Objects.requireNonNull(contentType);
    Objects.requireNonNull(etag);
    body = Objects.requireNonNull(body).clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
