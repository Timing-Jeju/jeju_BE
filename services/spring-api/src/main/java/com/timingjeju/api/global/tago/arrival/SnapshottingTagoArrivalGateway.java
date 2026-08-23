package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingTagoArrivalGateway implements TagoArrivalSnapshotGateway {
  private final SnapshotStoreService snapshots;

  public SnapshottingTagoArrivalGateway(SnapshotStoreService snapshots) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
  }

  @Override
  public SavedTagoArrivalSnapshot capture(
      UUID runId,
      TagoArrivalCacheKey key,
      TagoArrivalSourceResponse response,
      Instant observedAt,
      Instant expiresAt) {
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                runId,
                new SnapshotScope(
                    key.provider(),
                    key.service(),
                    TagoArrivalImportSessionAdapter.OPERATION,
                    TagoArrivalImportSessionAdapter.scopeKey(key)),
                key.nodeId(),
                "arrival",
                200,
                "00",
                observedAt,
                null,
                expiresAt,
                TagoArrivalImportSessionAdapter.PARSER_VERSION,
                response.format(),
                "UTF-8",
                response.payload(),
                Map.of(
                    "endpoint", "/" + TagoArrivalImportSessionAdapter.OPERATION,
                    "cityCode", key.cityCode(),
                    "nodeId", key.nodeId())));
    return new SavedTagoArrivalSnapshot(
        response,
        saved.snapshotId(),
        saved.payloadHash(),
        observedAt,
        expiresAt,
        saved.replayed(),
        saved.status());
  }

  @Override
  public void reject(SavedTagoArrivalSnapshot snapshot, TagoArrivalException.Code code) {
    if (snapshot.status() != SnapshotStatus.RECEIVED) return;
    snapshots.transition(
        new SnapshotTransitionCommand(
            snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
