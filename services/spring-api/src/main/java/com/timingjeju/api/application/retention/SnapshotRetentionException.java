package com.timingjeju.api.application.retention;

public final class SnapshotRetentionException extends RuntimeException {
  public enum Code {
    SNAPSHOT_RETENTION_UNAVAILABLE
  }

  private final Code code;

  private SnapshotRetentionException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public static SnapshotRetentionException unavailable() {
    return new SnapshotRetentionException(Code.SNAPSHOT_RETENTION_UNAVAILABLE);
  }

  public Code code() {
    return code;
  }
}
