package com.timingjeju.api.application.kma;

public enum KmaWeatherOperation {
  ULTRA_CURRENT("getUltraSrtNcst"),
  ULTRA_FORECAST("getUltraSrtFcst");

  private final String providerOperation;

  KmaWeatherOperation(String providerOperation) {
    this.providerOperation = providerOperation;
  }

  public String providerOperation() {
    return providerOperation;
  }
}
