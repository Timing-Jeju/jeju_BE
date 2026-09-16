package com.timingjeju.api.domain.accountdeletion.model;

import java.time.Instant;
import java.util.UUID;

public record AccountDeletionRecord(
    String id,
    UUID userProfileId,
    byte[] authSubjectFingerprint,
    byte[] idempotencyHash,
    byte[] requestHash,
    byte[] statusTokenHash,
    String statusTokenCiphertext,
    String statusTokenKeyVersion,
    Instant statusTokenExpiresAt,
    String authSubjectCiphertext,
    String authSubjectKeyVersion,
    AccountDeletionStatus status,
    String currentStep,
    Instant nextRetryAt,
    Instant requestedAt,
    Instant completedAt) {

  public AccountDeletionRecord {
    if (authSubjectFingerprint != null && authSubjectFingerprint.length != 32) {
      throw new IllegalArgumentException("auth subject fingerprint는 32-byte여야 합니다.");
    }
    authSubjectFingerprint = authSubjectFingerprint == null ? null : authSubjectFingerprint.clone();
    idempotencyHash = idempotencyHash.clone();
    requestHash = requestHash.clone();
    statusTokenHash = statusTokenHash.clone();
  }

  @Override
  public byte[] authSubjectFingerprint() {
    return authSubjectFingerprint == null ? null : authSubjectFingerprint.clone();
  }

  @Override
  public byte[] idempotencyHash() {
    return idempotencyHash.clone();
  }

  @Override
  public byte[] requestHash() {
    return requestHash.clone();
  }

  @Override
  public byte[] statusTokenHash() {
    return statusTokenHash.clone();
  }

  public AccountDeletionRecord withTokenExpiresAt(Instant expiresAt) {
    return new AccountDeletionRecord(
        id,
        userProfileId,
        authSubjectFingerprint,
        idempotencyHash,
        requestHash,
        statusTokenHash,
        statusTokenCiphertext,
        statusTokenKeyVersion,
        expiresAt,
        authSubjectCiphertext,
        authSubjectKeyVersion,
        status,
        currentStep,
        nextRetryAt,
        requestedAt,
        completedAt);
  }

  public AccountDeletionRecord withStatus(AccountDeletionStatus newStatus) {
    return new AccountDeletionRecord(
        id,
        userProfileId,
        authSubjectFingerprint,
        idempotencyHash,
        requestHash,
        statusTokenHash,
        statusTokenCiphertext,
        statusTokenKeyVersion,
        statusTokenExpiresAt,
        authSubjectCiphertext,
        authSubjectKeyVersion,
        newStatus,
        currentStep,
        nextRetryAt,
        requestedAt,
        completedAt);
  }

  @Override
  public String toString() {
    return "AccountDeletionRecord[id="
        + id
        + ", userProfileId=<redacted>, authSubjectFingerprint=<redacted>, idempotencyHash=<redacted>, requestHash=<redacted>, statusTokenHash=<redacted>, statusTokenCiphertext=<redacted>, statusTokenKeyVersion="
        + statusTokenKeyVersion
        + ", statusTokenExpiresAt="
        + statusTokenExpiresAt
        + ", authSubjectCiphertext=<redacted>, authSubjectKeyVersion="
        + authSubjectKeyVersion
        + ", status="
        + status
        + ", currentStep="
        + currentStep
        + ", nextRetryAt="
        + nextRetryAt
        + ", requestedAt="
        + requestedAt
        + ", completedAt="
        + completedAt
        + "]";
  }
}
