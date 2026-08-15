package com.timingjeju.api.application.tourapi.place;

public final class PlaceListImportException extends RuntimeException {

  private final PlaceListImportFailure failure;

  private PlaceListImportException(PlaceListImportFailure failure) {
    super(failure.detail());
    this.failure = failure;
  }

  public static PlaceListImportException invalidResponse() {
    return new PlaceListImportException(PlaceListImportFailure.INVALID_PROVIDER_RESPONSE);
  }

  public static PlaceListImportException storageFailure() {
    return new PlaceListImportException(PlaceListImportFailure.STORAGE_FAILURE);
  }

  public PlaceListImportFailure failure() {
    return failure;
  }
}
