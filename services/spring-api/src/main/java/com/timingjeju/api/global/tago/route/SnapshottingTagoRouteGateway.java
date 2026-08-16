package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.route.SavedTagoRoutePayload;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteSnapshotGateway;
import com.timingjeju.api.application.tago.route.TagoRouteSourceResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingTagoRouteGateway implements TagoRouteSnapshotGateway {
  private static final String PROVIDER = "TAGO";
  private static final String SERVICE = "BusRouteInfoInqireService";
  private static final String SCOPE_KEY = "jeju-routes";
  private static final String VERSION = "tago-route-v1";
  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingTagoRouteGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = snapshots;
    this.clock = clock;
  }

  @Override
  public SavedTagoRoutePayload save(
      UUID run,
      String kind,
      String city,
      String route,
      int page,
      TagoRouteSourceResponse response) {
    Instant fetched = clock.instant();
    SourceOperation operation = operation(kind);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("endpoint", '/' + operation.name());
    metadata.put("kind", kind);
    metadata.put("cityCode", city);
    metadata.put("route", route);
    if (page > 0) {
      metadata.put("pageNo", Integer.toString(page));
      metadata.put("numOfRows", "100");
    }
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                run,
                new SnapshotScope(PROVIDER, SERVICE, operation.name(), SCOPE_KEY),
                null,
                kind + '-' + route + '-' + Math.max(page, 0),
                200,
                "00",
                fetched,
                null,
                null,
                VERSION,
                response.format(),
                "UTF-8",
                response.payload(),
                Map.copyOf(metadata)));
    return new SavedTagoRoutePayload(
        response,
        kind,
        page,
        saved.snapshotId(),
        saved.payloadHash(),
        saved.fetchedAt(),
        saved.replayed(),
        saved.status());
  }

  private static SourceOperation operation(String kind) {
    return switch (kind) {
      case "route-list" -> new SourceOperation("getRouteNoList");
      case "route-detail" -> new SourceOperation("getRouteInfoIem");
      case "route-stops" -> new SourceOperation("getRouteAcctoThrghSttnList");
      default -> throw TagoRouteImportException.invalidRequest();
    };
  }

  private record SourceOperation(String name) {}

  @Override
  public void markParsed(SavedTagoRoutePayload payload) {
    if (payload.replayed() && payload.status() == SnapshotStatus.PARSED) return;
    if (payload.status() != SnapshotStatus.RECEIVED)
      throw TagoRouteImportException.invalidResponse();
    snapshots.transition(
        new SnapshotTransitionCommand(payload.snapshotId(), SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(SavedTagoRoutePayload payload) {
    if (payload.status() == SnapshotStatus.RECEIVED)
      snapshots.transition(
          new SnapshotTransitionCommand(
              payload.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
