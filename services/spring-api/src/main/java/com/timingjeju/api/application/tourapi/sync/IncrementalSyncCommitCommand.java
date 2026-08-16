package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.importing.ImportRunLease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record IncrementalSyncCommitCommand(
    ImportRunLease lease,
    long expectedCheckpointVersion,
    IncrementalSyncCursor cursorBefore,
    IncrementalSyncCursor cursorAfter,
    Instant sourceWatermarkAt,
    List<IncrementalSyncWrite> writes,
    List<IncrementalSyncPageLineage> pages) {
  public IncrementalSyncCommitCommand {
    lease = Objects.requireNonNull(lease, "lease는 필수입니다.");
    if (expectedCheckpointVersion < 0) {
      throw new IllegalArgumentException("expectedCheckpointVersion은 음수일 수 없습니다.");
    }
    cursorBefore = Objects.requireNonNull(cursorBefore, "cursorBefore는 필수입니다.");
    cursorAfter = Objects.requireNonNull(cursorAfter, "cursorAfter는 필수입니다.");
    if (cursorAfter.modifiedAfter().isBefore(cursorBefore.modifiedAfter())) {
      throw new IllegalArgumentException("cursor는 역행할 수 없습니다.");
    }
    sourceWatermarkAt = Objects.requireNonNull(sourceWatermarkAt, "sourceWatermarkAt은 필수입니다.");
    writes = List.copyOf(Objects.requireNonNull(writes, "writes는 필수입니다."));
    pages = List.copyOf(Objects.requireNonNull(pages, "pages는 필수입니다."));
    if (pages.isEmpty()) {
      throw new IllegalArgumentException("pages는 비어 있을 수 없습니다.");
    }
  }
}
