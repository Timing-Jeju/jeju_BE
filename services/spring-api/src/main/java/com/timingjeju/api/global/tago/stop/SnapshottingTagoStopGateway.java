package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.stop.SavedTagoStopPage;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopSnapshotGateway;
import com.timingjeju.api.application.tago.stop.TagoStopSourceResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingTagoStopGateway implements TagoStopSnapshotGateway {
  private static final SnapshotScope SCOPE =
      new SnapshotScope("TAGO", "BusSttnInfoInqireService", "getSttnNoList", "jeju");
  private static final String PARSER_VERSION = "tago-stop-v1";
  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingTagoStopGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  @Override
  public SavedTagoStopPage saveCity(UUID runId, TagoStopSourceResponse response) {
    return save(runId, "city", null, 0, response);
  }

  @Override
  public SavedTagoStopPage saveStations(
      UUID runId, String cityCode, int pageNo, TagoStopSourceResponse response) {
    return save(runId, "station", cityCode, pageNo, response);
  }

  private SavedTagoStopPage save(
      UUID runId, String kind, String cityCode, int pageNo, TagoStopSourceResponse response) {
    Instant fetchedAt = clock.instant();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("endpoint", kind.equals("city") ? "/getCtyCodeList" : "/getSttnNoList");
    metadata.put("kind", kind);
    metadata.put("pageNo", Integer.toString(Math.max(pageNo, 1)));
    metadata.put("numOfRows", "100");
    if (cityCode != null) metadata.put("cityCode", cityCode);
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                runId,
                SCOPE,
                null,
                kind + '-' + Math.max(pageNo, 1),
                200,
                "00",
                fetchedAt,
                null,
                null,
                PARSER_VERSION,
                response.format(),
                "UTF-8",
                response.payload(),
                Map.copyOf(metadata)));
    return new SavedTagoStopPage(
        response,
        pageNo,
        saved.snapshotId(),
        saved.payloadHash(),
        saved.fetchedAt(),
        saved.replayed(),
        saved.status());
  }

  @Override
  public void markParsed(SavedTagoStopPage page) {
    if (page.replayed() && page.status() == SnapshotStatus.PARSED) return;
    if (page.status() != SnapshotStatus.RECEIVED) throw TagoStopImportException.invalidResponse();
    snapshots.transition(
        new SnapshotTransitionCommand(page.snapshotId(), SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(SavedTagoStopPage page) {
    if (page.status() != SnapshotStatus.RECEIVED) return;
    snapshots.transition(
        new SnapshotTransitionCommand(
            page.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
