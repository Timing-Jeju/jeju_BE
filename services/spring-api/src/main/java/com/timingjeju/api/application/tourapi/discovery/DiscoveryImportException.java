package com.timingjeju.api.application.tourapi.discovery;

public final class DiscoveryImportException extends RuntimeException {

  private final String code;

  private DiscoveryImportException(String code, String detail) {
    super(detail);
    this.code = code;
  }

  public static DiscoveryImportException quotaExceeded() {
    return new DiscoveryImportException(
        "TOUR_DISCOVERY_QUOTA_EXCEEDED", "TourAPI 후보 보강 쿼터를 초과했습니다.");
  }

  public static DiscoveryImportException invalidResponse() {
    return new DiscoveryImportException(
        "TOUR_DISCOVERY_INVALID_RESPONSE", "TourAPI 후보 보강 응답 계약이 올바르지 않습니다.");
  }

  public static DiscoveryImportException storageFailure() {
    return new DiscoveryImportException(
        "TOUR_DISCOVERY_STORAGE_FAILURE", "TourAPI 후보 보강 적재를 완료하지 못했습니다.");
  }

  public String code() {
    return code;
  }
}
