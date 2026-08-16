package com.timingjeju.api.application.tourapi.image;

public final class PlaceImageImportException extends RuntimeException {
  private PlaceImageImportException(String message) {
    super(message);
  }

  public static PlaceImageImportException invalidResponse() {
    return new PlaceImageImportException("TourAPI 이미지 응답이 올바르지 않습니다.");
  }

  public static PlaceImageImportException storageFailure() {
    return new PlaceImageImportException("TourAPI 이미지 저장에 실패했습니다.");
  }
}
