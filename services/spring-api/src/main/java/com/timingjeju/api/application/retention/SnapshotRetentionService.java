package com.timingjeju.api.application.retention;

import com.timingjeju.api.application.snapshot.PersistedSnapshotProviderCatalog;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public class SnapshotRetentionService {
  private final SnapshotRetentionPort port;
  private final Clock clock;

  public SnapshotRetentionService(SnapshotRetentionPort port, Clock clock) {
    this.port = Objects.requireNonNull(port, "port는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public SnapshotRetentionResult execute(boolean dryRun, int batchSize) {
    SnapshotRetentionCommand command =
        new SnapshotRetentionCommand(
            clock.instant().truncatedTo(ChronoUnit.MICROS),
            PersistedSnapshotProviderCatalog.providers(),
            batchSize,
            dryRun);
    SnapshotRetentionResult result = port.execute(command);
    if (result.dryRun() != dryRun) {
      throw new IllegalStateException("snapshot retention 결과 mode가 요청과 다릅니다.");
    }
    return result;
  }
}
