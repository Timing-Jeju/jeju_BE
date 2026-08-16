package com.timingjeju.api.application.tourapi.detailitem;

public final class DetailItemImportException extends RuntimeException {
  private DetailItemImportException(String message) {
    super(message, null, false, false);
  }

  public static DetailItemImportException invalidResponse() {
    return new DetailItemImportException("TourAPI 반복 상세 응답이 올바르지 않습니다.");
  }

  public static DetailItemImportException storageFailure() {
    return new DetailItemImportException("TourAPI 반복 상세를 저장할 수 없습니다.");
  }
}
