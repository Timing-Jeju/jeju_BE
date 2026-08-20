package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DiscoveryCommitCommand(
    ImportRunLease lease,
    ImportRunScope scope,
    long expectedCheckpointVersion,
    Instant sourceWatermarkAt,
    String manifestHash,
    int pageCount,
    List<PlaceListWrite> writes,
    ImportRunCounts counts) {

  public DiscoveryCommitCommand {
    lease = Objects.requireNonNull(lease, "lease는 필수입니다.");
    scope = Objects.requireNonNull(scope, "scope는 필수입니다.");
    if (expectedCheckpointVersion < 0) {
      throw new IllegalArgumentException("expectedCheckpointVersion은 음수일 수 없습니다.");
    }
    sourceWatermarkAt = Objects.requireNonNull(sourceWatermarkAt, "sourceWatermarkAt은 필수입니다.");
    if (manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("manifestHash 형식이 올바르지 않습니다.");
    }
    if (pageCount < 1) {
      throw new IllegalArgumentException("pageCount는 1 이상이어야 합니다.");
    }
    writes = List.copyOf(Objects.requireNonNull(writes, "writes는 필수입니다."));
    counts = Objects.requireNonNull(counts, "counts는 필수입니다.");
  }
}
