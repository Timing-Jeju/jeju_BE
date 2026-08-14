package com.timingjeju.api.application.snapshot;

import java.util.Objects;
import java.util.UUID;

public record SnapshotTransitionCommand(
    UUID snapshotId, SnapshotStatus targetStatus, SnapshotFailure failure) {
  public SnapshotTransitionCommand {
    Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    Objects.requireNonNull(targetStatus, "targetStatus는 필수입니다.");
    if (!targetStatus.terminal()) {
      throw new IllegalArgumentException("received 상태로 전환할 수 없습니다.");
    }
    if (targetStatus == SnapshotStatus.REJECTED && failure == null) {
      throw new IllegalArgumentException("rejected 전환에는 failure가 필요합니다.");
    }
    if (targetStatus != SnapshotStatus.REJECTED && failure != null) {
      throw new IllegalArgumentException("rejected 외 전환에는 failure를 지정할 수 없습니다.");
    }
  }
}
