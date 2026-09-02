package com.timingjeju.api.global.datahealth;

import java.util.List;
import java.util.Objects;

public record ExternalDataHealthResponse(
    ExternalDataHealthOverallStatus status,
    List<ExternalDataHealthDependency> dependencies,
    ExternalDataHealthFailureCode failureCode) {

  public ExternalDataHealthResponse {
    Objects.requireNonNull(status, "status는 필수입니다.");
    dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies는 필수입니다."));
    if ((status == ExternalDataHealthOverallStatus.UP && failureCode != null)
        || (failureCode != null
            && failureCode != ExternalDataHealthFailureCode.DATA_HEALTH_UNAVAILABLE)) {
      throw new IllegalArgumentException("상태와 failureCode 조합이 올바르지 않습니다.");
    }
  }
}
