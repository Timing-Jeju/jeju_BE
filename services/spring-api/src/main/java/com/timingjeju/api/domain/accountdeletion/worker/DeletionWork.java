package com.timingjeju.api.domain.accountdeletion.worker;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DeletionWork(
    UUID requestId,
    boolean cancellationRequested,
    Set<DeletionStep> completedSteps,
    EncryptedAuthSubject encryptedSubject) {

  public DeletionWork {
    Objects.requireNonNull(requestId, "requestId는 필수입니다.");
    Objects.requireNonNull(completedSteps, "completedSteps는 필수입니다.");
    completedSteps = Set.copyOf(completedSteps);
    if (!completedSteps.contains(DeletionStep.AUTH_USER_DELETED)) {
      Objects.requireNonNull(encryptedSubject, "Auth 삭제 전 encryptedSubject는 필수입니다.");
    }
  }

  public boolean isCompleted(DeletionStep step) {
    return completedSteps.contains(step);
  }

  public boolean destructiveStepStarted() {
    return !completedSteps.isEmpty();
  }

  public EnumSet<DeletionStep> mutableCompletedSteps() {
    return completedSteps.isEmpty()
        ? EnumSet.noneOf(DeletionStep.class)
        : EnumSet.copyOf(completedSteps);
  }
}
