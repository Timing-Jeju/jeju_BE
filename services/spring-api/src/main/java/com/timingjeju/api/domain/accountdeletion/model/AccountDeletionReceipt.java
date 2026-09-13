package com.timingjeju.api.domain.accountdeletion.model;

import java.time.Instant;

public record AccountDeletionReceipt(
    String deletionRequestId,
    AccountDeletionStatus status,
    String statusToken,
    Instant requestedAt,
    Instant statusTokenExpiresAt,
    String pollUrl) {}
