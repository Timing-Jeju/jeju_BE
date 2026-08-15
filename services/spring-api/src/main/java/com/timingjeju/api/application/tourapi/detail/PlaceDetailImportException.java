package com.timingjeju.api.application.tourapi.detail;

public final class PlaceDetailImportException extends RuntimeException {

  private PlaceDetailImportException(String message) {
    super(message, null, false, false);
  }

  public static PlaceDetailImportException invalidResponse() {
    return new PlaceDetailImportException("TourAPI 장소 상세 응답이 올바르지 않습니다.");
  }

  public static PlaceDetailImportException storageFailure() {
    return new PlaceDetailImportException("TourAPI 장소 상세를 저장할 수 없습니다.");
  }
}
