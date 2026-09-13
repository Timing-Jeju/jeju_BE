package com.timingjeju.api.domain.accountdeletion.dto;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatusView;
import java.time.Instant;

public record AccountDeletionStatusResponse(
    String deletionRequestId,
    String status,
    String currentStep,
    Instant nextRetryAt,
    Instant completedAt) {
  public static AccountDeletionStatusResponse from(AccountDeletionStatusView view) {
    return new AccountDeletionStatusResponse(
        view.deletionRequestId(),
        view.status().wireValue(),
        view.currentStep(),
        view.nextRetryAt(),
        view.completedAt());
  }
}
