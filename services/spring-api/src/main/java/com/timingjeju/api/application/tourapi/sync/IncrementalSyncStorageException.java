package com.timingjeju.api.application.tourapi.sync;

public final class IncrementalSyncStorageException extends RuntimeException {
  private IncrementalSyncStorageException(String message) {
    super(message, null, false, false);
  }

  public static IncrementalSyncStorageException storageFailure() {
    return new IncrementalSyncStorageException("증분 동기화 저장에 실패했습니다.");
  }

  public static IncrementalSyncStorageException conflictingSourceVersion() {
    return new IncrementalSyncStorageException("같은 원본 변경 시각에 서로 다른 변경이 존재합니다.");
  }
}
