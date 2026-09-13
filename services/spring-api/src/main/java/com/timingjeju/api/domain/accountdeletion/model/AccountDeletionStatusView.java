package com.timingjeju.api.domain.accountdeletion.model;

import java.time.Instant;

public record AccountDeletionStatusView(
    String deletionRequestId,
    AccountDeletionStatus status,
    String currentStep,
    Instant nextRetryAt,
    Instant completedAt) {}
