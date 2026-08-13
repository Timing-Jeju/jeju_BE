package com.timingjeju.api.application.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

public final class SnapshotStoreService {
  public static final int MAX_DECOMPRESSED_PAYLOAD_BYTES = 2 * 1024 * 1024;

  private final SnapshotStore store;
  private final SnapshotRedactor redactor;
  private final Clock clock;
  private final SnapshotIdentityGenerator identityGenerator;

  public SnapshotStoreService(
      SnapshotStore store,
      SnapshotRedactor redactor,
      Clock clock,
      SnapshotIdentityGenerator identityGenerator) {
    this.store = Objects.requireNonNull(store, "store는 필수입니다.");
    this.redactor = Objects.requireNonNull(redactor, "redactor는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.identityGenerator = Objects.requireNonNull(identityGenerator, "identityGenerator는 필수입니다.");
  }

  public SnapshotSaveResult save(SnapshotSaveCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    byte[] payload = command.decompressedPayload();
    if (payload.length > MAX_DECOMPRESSED_PAYLOAD_BYTES) {
      throw SnapshotStoreException.of(SnapshotStoreError.PAYLOAD_TOO_LARGE);
    }
    SnapshotRedactionResult redacted =
        redactor.redact(
            command.payloadFormat(), command.charset(), payload, command.requestMetadata());
    String payloadHash = sha256(payload);
    String requestHash =
        sha256(
            (command.scope().provider()
                    + "\n"
                    + command.scope().service()
                    + "\n"
                    + command.scope().operation()
                    + "\n"
                    + command.scope().scopeKey()
                    + "\n"
                    + command.pageKey()
                    + "\n"
                    + redacted.requestMetadataJson())
                .getBytes(StandardCharsets.UTF_8));
    StoredSnapshot snapshot =
        new StoredSnapshot(
            identityGenerator.newSnapshotId(),
            command.importRunId(),
            command.scope(),
            command.externalRecordId(),
            requestHash,
            command.pageKey(),
            command.httpStatus(),
            command.providerResultCode(),
            command.fetchedAt(),
            command.sourceModifiedAt(),
            command.expiresAt(),
            command.parserVersion(),
            payloadHash,
            redacted.initialStatus(),
            redacted.errorCode(),
            redacted.initialStatus() == SnapshotStatus.REJECTED ? "원천 응답을 안전하게 보존할 수 없습니다." : null,
            redacted.requestMetadataJson(),
            redacted.rawPayloadJson(),
            payload.length,
            redactor.version(),
            command.fetchedAt().plus(SnapshotRetention.FAILED_OR_UNPARSED));
    return store.save(snapshot);
  }

  public void transition(SnapshotTransitionCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    SnapshotFailure failure = command.failure();
    Duration retention =
        command.targetStatus() == SnapshotStatus.PARSED
                || command.targetStatus() == SnapshotStatus.TOMBSTONED
            ? SnapshotRetention.SUCCESSFUL
            : SnapshotRetention.FAILED_OR_UNPARSED;
    SnapshotMutationOutcome outcome =
        store.transition(
            new SnapshotStateMutation(
                command.snapshotId(),
                command.targetStatus(),
                clock.instant(),
                retention,
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message()));
    if (outcome == SnapshotMutationOutcome.UPDATED) {
      return;
    }
    throw SnapshotStoreException.of(
        outcome == SnapshotMutationOutcome.NOT_FOUND
            ? SnapshotStoreError.NOT_FOUND
            : SnapshotStoreError.INVALID_TRANSITION);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }
}
