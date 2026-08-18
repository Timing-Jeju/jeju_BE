package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportSourceKind;
import com.timingjeju.api.application.importing.ImportSyncMode;
import com.timingjeju.api.application.tago.route.StartedTagoRouteImport;
import com.timingjeju.api.application.tago.route.TagoRouteImportCommand;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteImportSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class TagoRouteImportSessionAdapter implements TagoRouteImportSession {
  static final ImportRunScope SCOPE =
      new ImportRunScope("TAGO", "BusRouteInfoInqireService", "getRouteNoList", "jeju-routes");
  private static final String VERSION = "tago-route-v1";
  private final ImportRunLifecycleService runs;
  private final ImportCheckpointService checkpoints;

  public TagoRouteImportSessionAdapter(
      ImportRunLifecycleService runs, ImportCheckpointService checkpoints) {
    this.runs = runs;
    this.checkpoints = checkpoints;
  }

  @Override
  public StartedTagoRouteImport start(TagoRouteImportCommand command) {
    ImportCheckpoint checkpoint =
        checkpoints.find(SCOPE).orElseThrow(TagoRouteImportException::invalidResponse);
    String selection = String.join(",", command.routeNumbers());
    ImportRunStartResult started =
        runs.start(
            new ImportRunStartCommand(
                ImportSourceKind.TAGO,
                "TAGO 제주 노선·경유 정류장",
                SCOPE,
                "2026",
                VERSION,
                VERSION,
                ImportSyncMode.FULL,
                sha256(
                    "getRouteNoList:getRouteInfoIem:getRouteAcctoThrghSttnList:"
                        + selection
                        + ':'
                        + VERSION),
                command.idempotencyKey(),
                null));
    if (started.replayed()
        && (started.status() != ImportRunExecutionStatus.SUCCEEDED
            || !started.lease().runId().equals(checkpoint.lastSucceededRunId())))
      throw TagoRouteImportException.invalidResponse();
    return new StartedTagoRouteImport(
        started.lease(),
        started.replayed(),
        checkpoint.version(),
        number(checkpoint.checkpoint().get("routeCount")),
        number(checkpoint.checkpoint().get("routeStopCount")),
        started.counts());
  }

  @Override
  public void fail(ImportRunLease lease) {
    runs.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static int number(Object value) {
    if (!(value instanceof Number number) || number.intValue() < 0) {
      throw TagoRouteImportException.invalidResponse();
    }
    return number.intValue();
  }
}
