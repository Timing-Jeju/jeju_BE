package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

@Endpoint(id = "externaldatahealth")
public final class ExternalDataHealthEndpoint {
  private static final Comparator<ExternalDataHealthDependency> ORDER =
      Comparator.comparing(ExternalDataHealthDependency::provider)
          .thenComparing(ExternalDataHealthDependency::service)
          .thenComparing(ExternalDataHealthDependency::operation);

  private final CompletedProviderDataHealthService service;

  public ExternalDataHealthEndpoint(CompletedProviderDataHealthService service) {
    this.service = Objects.requireNonNull(service, "service는 필수입니다.");
  }

  @ReadOperation
  public ExternalDataHealthResponse health() {
    List<ExternalDataHealthDependency> dependencies = new ArrayList<>();
    dependencies.add(ExternalDataHealthDependency.disabledMobility());
    try {
      service.collect().stream().map(ExternalDataHealthDependency::from).forEach(dependencies::add);
    } catch (ProviderDataHealthException failure) {
      if (failure.code() == ProviderDataHealthException.Code.DATA_HEALTH_UNAVAILABLE) {
        return new ExternalDataHealthResponse(
            ExternalDataHealthOverallStatus.DOWN,
            dependencies,
            ExternalDataHealthFailureCode.DATA_HEALTH_UNAVAILABLE);
      }
      throw failure;
    }
    dependencies.sort(ORDER);
    boolean healthy =
        dependencies.stream()
            .allMatch(
                item ->
                    item.status() == ProviderDataHealthStatus.FRESH
                        || item.status() == ProviderDataHealthStatus.DISABLED);
    return new ExternalDataHealthResponse(
        healthy ? ExternalDataHealthOverallStatus.UP : ExternalDataHealthOverallStatus.DOWN,
        dependencies,
        null);
  }
}
