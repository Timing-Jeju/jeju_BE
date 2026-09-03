package com.timingjeju.api.application.retention;

import com.timingjeju.api.application.snapshot.PersistedSnapshotProviderCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SnapshotRetentionCommand(
    Instant now, List<String> providers, int batchSize, boolean dryRun) {

  public SnapshotRetentionCommand {
    Objects.requireNonNull(now, "now는 필수입니다.");
    Objects.requireNonNull(providers, "providers는 필수입니다.");
    providers =
        providers.stream().map(provider -> Objects.requireNonNull(provider)).sorted().toList();
    if (!providers.equals(PersistedSnapshotProviderCatalog.providers())) {
      throw new IllegalArgumentException("영속 snapshot 공급자 전체만 retention할 수 있습니다.");
    }
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("batchSize는 1 이상 500 이하여야 합니다.");
    }
  }
}
