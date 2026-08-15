package com.timingjeju.api.application.tourapi.place;

public enum PlaceListImportFailure {
  INVALID_PROVIDER_RESPONSE("TOUR_PLACE_INVALID_RESPONSE", "장소 원천 응답 계약이 올바르지 않습니다."),
  STORAGE_FAILURE("TOUR_PLACE_STORAGE_FAILURE", "장소 적재를 완료하지 못했습니다.");

  private final String code;
  private final String detail;

  PlaceListImportFailure(String code, String detail) {
    this.code = code;
    this.detail = detail;
  }

  public String code() {
    return code;
  }

  public String detail() {
    return detail;
  }
}
