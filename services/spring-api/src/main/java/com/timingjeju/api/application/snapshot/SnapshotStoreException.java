package com.timingjeju.api.application.snapshot;

public final class SnapshotStoreException extends RuntimeException {
  private final SnapshotStoreError code;

  private SnapshotStoreException(SnapshotStoreError code) {
    super(code.message(), null, false, false);
    this.code = code;
  }

  public static SnapshotStoreException of(SnapshotStoreError code) {
    return new SnapshotStoreException(code);
  }

  public SnapshotStoreError code() {
    return code;
  }
}
