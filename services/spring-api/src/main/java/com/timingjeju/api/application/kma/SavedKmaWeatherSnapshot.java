package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SavedKmaWeatherSnapshot(
    KmaWeatherSourceResponse response,
    UUID snapshotId,
    String requestFingerprint,
    String payloadHash,
    Instant fetchedAt,
    boolean replayed,
    SnapshotStatus status,
    List<SnapshotSaveResult> attemptSnapshots) {
  public SavedKmaWeatherSnapshot {
    Objects.requireNonNull(response, "response는 필수입니다.");
    Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint는 필수입니다.");
    Objects.requireNonNull(payloadHash, "payloadHash는 필수입니다.");
    Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    Objects.requireNonNull(status, "status는 필수입니다.");
    attemptSnapshots =
        List.copyOf(Objects.requireNonNull(attemptSnapshots, "attemptSnapshots는 필수입니다."));
    if (attemptSnapshots.isEmpty()) {
      throw new IllegalArgumentException("attemptSnapshots는 비어 있을 수 없습니다.");
    }
  }

  public SavedKmaWeatherSnapshot(
      KmaWeatherSourceResponse response,
      UUID snapshotId,
      String requestFingerprint,
      String payloadHash,
      Instant fetchedAt,
      boolean replayed,
      SnapshotStatus status) {
    this(
        response,
        snapshotId,
        requestFingerprint,
        payloadHash,
        fetchedAt,
        replayed,
        status,
        List.of(
            new SnapshotSaveResult(
                snapshotId, requestFingerprint, payloadHash, replayed, fetchedAt, status)));
  }
}
