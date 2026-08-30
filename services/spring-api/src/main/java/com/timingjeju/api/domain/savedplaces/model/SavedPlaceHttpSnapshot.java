package com.timingjeju.api.domain.savedplaces.model;

import java.util.Objects;

public final class SavedPlaceHttpSnapshot {
  private final int status;
  private final String contentType;
  private final String location;
  private final String etag;
  private final byte[] body;

  public SavedPlaceHttpSnapshot(
      int status, String contentType, String location, String etag, byte[] body) {
    if (status != 200 && status != 201) throw new IllegalArgumentException("invalid status");
    this.status = status;
    this.contentType = Objects.requireNonNull(contentType);
    this.location = Objects.requireNonNull(location);
    this.etag = Objects.requireNonNull(etag);
    this.body = Objects.requireNonNull(body).clone();
  }

  public int status() {
    return status;
  }

  public String contentType() {
    return contentType;
  }

  public String location() {
    return location;
  }

  public String etag() {
    return etag;
  }

  public byte[] body() {
    return body.clone();
  }
}
