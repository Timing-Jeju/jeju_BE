package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoRequestContract;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoSnapshotGateway;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemLineage;
import com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SnapshottingDetailInfoPageGateway implements DetailInfoSnapshotGateway {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String OPERATION = "detailInfo2";
  private static final String PARSER_VERSION = "detail-info-v1";

  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingDetailInfoPageGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public SavedDetailInfoPage save(
      UUID importRunId,
      String contentId,
      String contentTypeId,
      int pageNo,
      DetailSourceResponse response) {
    Instant fetchedAt = clock.instant();
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
                fetchedAt,
                null,
                null,
                PARSER_VERSION,
                response.format(),
                "UTF-8",
                storedBytes,
                Map.of(
                    "endpoint",
                    "/detailInfo2",
                    "contentId",
                    contentId,
                    "contentTypeId",
                    contentTypeId,
                    "pageNo",
                    Integer.toString(pageNo),
                    "numOfRows",
                    Integer.toString(DetailInfoRequestContract.PAGE_SIZE))));
    DetailItemLineage lineage =
        new DetailItemLineage(
            OPERATION, saved.requestFingerprint(), saved.snapshotId(), importRunId);
    return new SavedDetailInfoPage(
        new DetailSourceResponse(storedBytes, response.format()),
        pageNo,
        saved.payloadHash(),
        fetchedAt,
        lineage);
  }

  @Override
  public void markParsed(UUID snapshotId) {
    snapshots.transition(new SnapshotTransitionCommand(snapshotId, SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(UUID snapshotId) {
    snapshots.transition(
        new SnapshotTransitionCommand(
            snapshotId, SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
