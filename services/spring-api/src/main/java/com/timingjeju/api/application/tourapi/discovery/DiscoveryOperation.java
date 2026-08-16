package com.timingjeju.api.application.tourapi.discovery;

public enum DiscoveryOperation {
  LOCATION("locationBasedList2", "locationBasedList2"),
  KEYWORD("searchKeyword2", "searchKeyword2"),
  STAY("searchStay2", "searchStay2");

  private final String operationKey;
  private final String relativePath;

  DiscoveryOperation(String operationKey, String relativePath) {
    this.operationKey = operationKey;
    this.relativePath = relativePath;
  }

  public String operationKey() {
    return operationKey;
  }

  public String relativePath() {
    return relativePath;
  }
}
