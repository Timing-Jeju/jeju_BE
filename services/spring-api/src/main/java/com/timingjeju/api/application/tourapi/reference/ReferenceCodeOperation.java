package com.timingjeju.api.application.tourapi.reference;

public enum ReferenceCodeOperation {
  LDONG("ldongCode2", "areaCode2"),
  CLASSIFICATION("lclsSystmCode2", "categoryCode2");

  private final String endpointPath;
  private final String provenanceOperation;

  ReferenceCodeOperation(String endpointPath, String provenanceOperation) {
    this.endpointPath = endpointPath;
    this.provenanceOperation = provenanceOperation;
  }

  public String endpointPath() {
    return endpointPath;
  }

  public String provenanceOperation() {
    return provenanceOperation;
  }
}
