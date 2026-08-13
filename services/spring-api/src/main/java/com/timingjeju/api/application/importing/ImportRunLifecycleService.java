package com.timingjeju.api.application.importing;

import java.time.Clock;
import java.util.Objects;

public final class ImportRunLifecycleService {

  private final ImportRunStore store;
  private final Clock clock;
  private final ImportRunIdentityGenerator identityGenerator;

  public ImportRunLifecycleService(
      ImportRunStore store, Clock clock, ImportRunIdentityGenerator identityGenerator) {
    this.store = Objects.requireNonNull(store, "store는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.identityGenerator = Objects.requireNonNull(identityGenerator, "identityGenerator는 필수입니다.");
  }

  public ImportRunStartResult start(ImportRunStartCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    return store.start(
        command, identityGenerator.newRunId(), identityGenerator.newOwnerToken(), clock.instant());
  }

  public void addCounts(ImportRunLease lease, ImportRunCounts delta) {
    requireUpdated(store.addCounts(requireLease(lease), requireCounts(delta)));
  }

  public void succeed(ImportRunLease lease, ImportRunCounts finalDelta) {
    finish(lease, ImportRunStatus.SUCCEEDED, finalDelta, null);
  }

  public void completePartial(
      ImportRunLease lease, ImportRunCounts finalDelta, ImportRunFailure failure) {
    if (failure == null || failure == ImportRunFailure.CANCELLED) {
      throw new IllegalArgumentException("partial failure가 올바르지 않습니다.");
    }
    finish(lease, ImportRunStatus.PARTIAL, finalDelta, failure);
  }

  public void fail(ImportRunLease lease, ImportRunFailure failure) {
    if (failure == null || failure == ImportRunFailure.CANCELLED) {
      throw new IllegalArgumentException("failure가 올바르지 않습니다.");
    }
    finish(lease, ImportRunStatus.FAILED, ImportRunCounts.zero(), failure);
  }

  public void cancel(ImportRunLease lease) {
    finish(lease, ImportRunStatus.CANCELLED, ImportRunCounts.zero(), ImportRunFailure.CANCELLED);
  }

  private void finish(
      ImportRunLease lease,
      ImportRunStatus status,
      ImportRunCounts delta,
      ImportRunFailure failure) {
    requireUpdated(
        store.finish(requireLease(lease), status, requireCounts(delta), failure, clock.instant()));
  }

  private static ImportRunLease requireLease(ImportRunLease lease) {
    return Objects.requireNonNull(lease, "lease는 필수입니다.");
  }

  private static ImportRunCounts requireCounts(ImportRunCounts counts) {
    return Objects.requireNonNull(counts, "counts는 필수입니다.");
  }

  private static void requireUpdated(ImportRunMutationOutcome outcome) {
    if (outcome == ImportRunMutationOutcome.UPDATED) {
      return;
    }
    ImportRunLifecycleError error =
        switch (outcome) {
          case NOT_FOUND -> ImportRunLifecycleError.NOT_FOUND;
          case OWNERSHIP_LOST -> ImportRunLifecycleError.OWNERSHIP_LOST;
          case INVALID_TRANSITION -> ImportRunLifecycleError.INVALID_TRANSITION;
          case COUNT_OVERFLOW -> ImportRunLifecycleError.COUNT_OVERFLOW;
          case UPDATED -> throw new IllegalStateException("unreachable");
        };
    throw ImportRunLifecycleException.of(error);
  }
}
