package com.timingjeju.api.domain.accountdeletion.dto;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionReceipt;
import java.time.Instant;

public record AccountDeletionAcceptedResponse(
    String deletionRequestId,
    String status,
    String statusToken,
    Instant requestedAt,
    Instant statusTokenExpiresAt,
    String pollUrl) {
  public static AccountDeletionAcceptedResponse from(AccountDeletionReceipt receipt) {
    return new AccountDeletionAcceptedResponse(
        receipt.deletionRequestId(),
        receipt.status().wireValue(),
        receipt.statusToken(),
        receipt.requestedAt(),
        receipt.statusTokenExpiresAt(),
        receipt.pollUrl());
  }

  @Override
  public String toString() {
    return "AccountDeletionAcceptedResponse[deletionRequestId="
        + deletionRequestId
        + ", status="
        + status
        + ", statusToken=<redacted>, requestedAt="
        + requestedAt
        + ", statusTokenExpiresAt="
        + statusTokenExpiresAt
        + ", pollUrl="
        + pollUrl
        + "]";
  }
}
