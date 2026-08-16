package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.image.DetailImageRequestContract;
import com.timingjeju.api.application.tourapi.image.DetailImageSnapshotGateway;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import com.timingjeju.api.application.tourapi.image.PlaceImageLineage;
import com.timingjeju.api.application.tourapi.image.SavedDetailImagePage;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingDetailImagePageGateway implements DetailImageSnapshotGateway {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String OPERATION = "detailImage2";
  private static final String PARSER_VERSION = "detail-image-v1";
  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingDetailImagePageGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public SavedDetailImagePage save(
      UUID importRunId, String contentId, int pageNo, DetailSourceResponse response) {
    Instant requestedFetchedAt = clock.instant();
    byte[] storedBytes = response.payload();
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                importRunId,
                new SnapshotScope(PROVIDER, SERVICE, OPERATION, "content:" + contentId),
                contentId,
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
                    "/detailImage2",
                    "contentId",
                    contentId,
                    "imageYN",
                    "Y",
                    "subImageYN",
                    "Y",
                    "pageNo",
                    Integer.toString(pageNo),
                    "numOfRows",
                    Integer.toString(DetailImageRequestContract.PAGE_SIZE))));
    return new SavedDetailImagePage(
        new DetailSourceResponse(storedBytes, response.format()),
        pageNo,
        saved.payloadHash(),
        saved.fetchedAt(),
        new PlaceImageLineage(
            OPERATION, saved.requestFingerprint(), saved.snapshotId(), importRunId),
        saved.replayed(),
        saved.status());
  }

  @Override
  public void markParsed(SavedDetailImagePage page) {
    if (page.status() == SnapshotStatus.PARSED && page.replayed()) return;
    if (page.status() != SnapshotStatus.RECEIVED) {
      throw PlaceImageImportException.invalidResponse();
    }
    snapshots.transition(
        new SnapshotTransitionCommand(page.lineage().snapshotId(), SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(SavedDetailImagePage page) {
    if (page.status() != SnapshotStatus.RECEIVED) return;
    snapshots.transition(
        new SnapshotTransitionCommand(
            page.lineage().snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
