package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCursor;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncException;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncLineage;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncRequestContract;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSnapshotGateway;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSourceResponse;
import com.timingjeju.api.application.tourapi.sync.SavedIncrementalSyncPage;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingIncrementalSyncGateway implements IncrementalSyncSnapshotGateway {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String OPERATION = "areaBasedSyncList2";
  private static final String PARSER_VERSION = "tourapi-incremental-sync-v1";
  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingIncrementalSyncGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  @Override
  public SavedIncrementalSyncPage save(
      UUID runId,
      IncrementalSyncCursor cursor,
      int pageNo,
      IncrementalSyncSourceResponse response) {
    Instant requestedFetchedAt = clock.instant();
    byte[] storedBytes = response.payload();
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                runId,
                new SnapshotScope(PROVIDER, SERVICE, OPERATION, "jeju"),
                null,
                Integer.toString(pageNo),
                200,
                "0000",
                requestedFetchedAt,
                null,
                null,
                PARSER_VERSION,
                response.format(),
                "UTF-8",
                storedBytes,
                Map.of(
                    "endpoint",
                    "/areaBasedSyncList2",
                    "modifiedtime",
                    cursor.modifiedAfter().toString(),
                    "lDongRegnCd",
                    "50",
                    "pageNo",
                    Integer.toString(pageNo),
                    "numOfRows",
                    Integer.toString(IncrementalSyncRequestContract.PAGE_SIZE))));
    return new SavedIncrementalSyncPage(
        new IncrementalSyncSourceResponse(storedBytes, response.format()),
        pageNo,
        saved.payloadHash(),
        saved.fetchedAt(),
        new IncrementalSyncLineage(
            OPERATION, saved.requestFingerprint(), saved.snapshotId(), runId),
        saved.replayed(),
        saved.status());
  }

  @Override
  public void markParsed(SavedIncrementalSyncPage page) {
    if (page.replayed() && page.status() == SnapshotStatus.PARSED) return;
    if (page.status() != SnapshotStatus.RECEIVED) {
      throw IncrementalSyncException.invalidResponse();
    }
    snapshots.transition(
        new SnapshotTransitionCommand(page.lineage().snapshotId(), SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(SavedIncrementalSyncPage page) {
    if (page.status() != SnapshotStatus.RECEIVED) return;
    snapshots.transition(
        new SnapshotTransitionCommand(
            page.lineage().snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
