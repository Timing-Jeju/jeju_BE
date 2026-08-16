package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportSourceKind;
import com.timingjeju.api.application.importing.ImportSyncMode;
import com.timingjeju.api.application.tago.stop.StartedTagoStopImport;
import com.timingjeju.api.application.tago.stop.TagoStopImportCommand;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopImportSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class TagoStopImportSessionAdapter implements TagoStopImportSession {
  static final ImportRunScope SCOPE =
      new ImportRunScope("TAGO", "BusSttnInfoInqireService", "getSttnNoList", "jeju");
  private static final String PARSER_VERSION = "tago-stop-v1";
  private final ImportRunLifecycleService runs;
  private final ImportCheckpointService checkpoints;

  public TagoStopImportSessionAdapter(
      ImportRunLifecycleService runs, ImportCheckpointService checkpoints) {
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints는 필수입니다.");
  }

  @Override
  public StartedTagoStopImport start(TagoStopImportCommand command) {
    ImportCheckpoint checkpoint =
        checkpoints.find(SCOPE).orElseThrow(TagoStopImportException::invalidResponse);
    ImportRunStartResult started =
        runs.start(
            new ImportRunStartCommand(
                ImportSourceKind.TAGO,
                "TAGO 제주 도시코드·정류장",
                SCOPE,
                "2026",
                PARSER_VERSION,
                "tago-stop-v1",
                ImportSyncMode.FULL,
                sha256("getCtyCodeList:getSttnNoList:jeju:" + PARSER_VERSION),
                command.idempotencyKey(),
                null));
    if (!started.replayed()) {
      return new StartedTagoStopImport(
          started.lease(), false, checkpoint.version(), null, started.counts());
    }
    Object cityCode = checkpoint.checkpoint().get("cityCode");
    if (started.status() != ImportRunExecutionStatus.SUCCEEDED
        || !started.lease().runId().equals(checkpoint.lastSucceededRunId())
        || !(cityCode instanceof String value)
        || value.isBlank()) {
      throw TagoStopImportException.invalidResponse();
    }
    return new StartedTagoStopImport(
        started.lease(), true, checkpoint.version(), (String) cityCode, started.counts());
  }

  @Override
  public void fail(com.timingjeju.api.application.importing.ImportRunLease lease) {
    runs.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }
}
