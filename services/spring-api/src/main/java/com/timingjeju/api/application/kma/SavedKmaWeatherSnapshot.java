package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SavedKmaWeatherSnapshot(
    KmaWeatherSourceResponse response,
    UUID snapshotId,
    String requestFingerprint,
    String payloadHash,
    Instant fetchedAt,
    boolean replayed,
    SnapshotStatus status) {
  public SavedKmaWeatherSnapshot {
    Objects.requireNonNull(response, "response는 필수입니다.");
    Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint는 필수입니다.");
    Objects.requireNonNull(payloadHash, "payloadHash는 필수입니다.");
    Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    Objects.requireNonNull(status, "status는 필수입니다.");
  }
}
