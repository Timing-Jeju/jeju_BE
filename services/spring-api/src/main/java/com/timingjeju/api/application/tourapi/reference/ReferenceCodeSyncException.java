package com.timingjeju.api.application.tourapi.reference;

public final class ReferenceCodeSyncException extends RuntimeException {

  private ReferenceCodeSyncException(String message) {
    super(message, null, false, false);
  }

  public static ReferenceCodeSyncException invalidResponse() {
    return new ReferenceCodeSyncException("TourAPI 기준 코드 응답이 올바르지 않습니다.");
  }

  public static ReferenceCodeSyncException invalidHierarchy() {
    return new ReferenceCodeSyncException("TourAPI 기준 코드 계층이 올바르지 않습니다.");
  }

  public static ReferenceCodeSyncException storageFailure() {
    return new ReferenceCodeSyncException("TourAPI 기준 코드를 저장할 수 없습니다.");
  }
}
