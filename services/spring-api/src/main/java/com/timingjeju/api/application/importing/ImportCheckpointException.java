package com.timingjeju.api.application.importing;

import java.util.Objects;

public final class ImportCheckpointException extends RuntimeException {

  private final ImportCheckpointError code;

  private ImportCheckpointException(ImportCheckpointError code) {
    super(Objects.requireNonNull(code, "code는 필수입니다.").detail(), null, false, false);
    this.code = code;
  }

  public static ImportCheckpointException of(ImportCheckpointError code) {
    return new ImportCheckpointException(code);
  }

  public ImportCheckpointError code() {
    return code;
  }

  public boolean retryable() {
    return code.retryable();
  }
}
