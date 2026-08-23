package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherImportCommand;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherImportService;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.application.kma.KmaWeatherResponsePart;
import com.timingjeju.api.application.kma.KmaWeatherSnapshotGateway;
import com.timingjeju.api.application.kma.KmaWeatherSourceResponse;
import com.timingjeju.api.application.kma.SavedKmaWeatherSnapshot;
import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class SnapshottingKmaWeatherGateway implements KmaWeatherSnapshotGateway {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
  private final SnapshotStoreService snapshots;
  private final Clock clock;

  public SnapshottingKmaWeatherGateway(SnapshotStoreService snapshots, Clock clock) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  @Override
  public SavedKmaWeatherSnapshot capture(
      UUID runId,
      KmaWeatherOperation operation,
      ForecastBaseTime base,
      KmaWeatherImportCommand command,
      KmaWeatherSourceResponse response) {
    Objects.requireNonNull(runId, "runId는 필수입니다.");
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(base, "base는 필수입니다.");
    Objects.requireNonNull(command, "command는 필수입니다.");
    Objects.requireNonNull(response, "response는 필수입니다.");
    String baseDate = DATE.format(base.baseDate());
    String baseTime = TIME.format(base.baseTime());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("endpoint", "/" + operation.providerOperation());
    metadata.put("pageNo", "1");
    metadata.put("numOfRows", "1000");
    metadata.put("dataType", "JSON");
    metadata.put("base_date", baseDate);
    metadata.put("base_time", baseTime);
    metadata.put("nx", Integer.toString(command.nx()));
    metadata.put("ny", Integer.toString(command.ny()));
    if (operation == KmaWeatherOperation.VILLAGE_FORECAST) {
      metadata.put("versionEndpoint", "/getFcstVersion");
      metadata.put("versionFtype", "SHRT");
      metadata.put("versionBasedatetime", baseDate + baseTime);
    }
    SnapshotScope scope =
        new SnapshotScope(
            KmaWeatherImportService.PROVIDER,
            KmaWeatherImportService.SERVICE,
            operation.providerOperation(),
            "nx=" + command.nx() + ";ny=" + command.ny());
    List<SnapshotSaveResult> attemptSnapshots = new ArrayList<>();
    SnapshotSaveResult saved;
    if (operation == KmaWeatherOperation.VILLAGE_FORECAST) {
      for (KmaWeatherResponsePart part : response.parts()) {
        Map<String, Object> partMetadata = new LinkedHashMap<>(metadata);
        partMetadata.put("responseOperation", part.providerOperation());
        partMetadata.put("responsePage", Integer.toString(part.pageNumber()));
        SnapshotSaveResult partSaved =
            save(
                runId,
                scope,
                part.providerOperation() + ":" + part.pageNumber(),
                part.format(),
                part.payload(),
                partMetadata,
                operation);
        attemptSnapshots.add(partSaved);
      }
      Map<String, Object> manifestMetadata = new LinkedHashMap<>(metadata);
      manifestMetadata.put("manifest", "ordered-response-snapshots-v1");
      saved =
          save(
              runId,
              scope,
              "manifest",
              com.timingjeju.api.application.snapshot.SnapshotPayloadFormat.JSON,
              manifest(attemptSnapshots, response.parts()),
              manifestMetadata,
              operation);
      attemptSnapshots.add(saved);
    } else {
      saved =
          save(
              runId,
              scope,
              baseDate + baseTime,
              response.format(),
              response.payload(),
              metadata,
              operation);
      attemptSnapshots.add(saved);
    }
    return new SavedKmaWeatherSnapshot(
        response,
        saved.snapshotId(),
        saved.requestFingerprint(),
        saved.payloadHash(),
        saved.fetchedAt(),
        saved.replayed(),
        saved.status(),
        attemptSnapshots);
  }

  private SnapshotSaveResult save(
      UUID runId,
      SnapshotScope scope,
      String pageKey,
      com.timingjeju.api.application.snapshot.SnapshotPayloadFormat format,
      byte[] payload,
      Map<String, Object> metadata,
      KmaWeatherOperation operation) {
    return snapshots.save(
        new SnapshotSaveCommand(
            runId,
            scope,
            null,
            pageKey,
            200,
            null,
            clock.instant(),
            null,
            null,
            KmaWeatherImportService.parserVersion(operation),
            format,
            "UTF-8",
            payload,
            metadata));
  }

  private static byte[] manifest(
      List<SnapshotSaveResult> snapshots, List<KmaWeatherResponsePart> parts) {
    StringBuilder json = new StringBuilder("{\"schema\":\"kma-response-manifest-v1\",\"parts\":[");
    for (int index = 0; index < snapshots.size(); index++) {
      if (index > 0) json.append(',');
      SnapshotSaveResult snapshot = snapshots.get(index);
      KmaWeatherResponsePart part = parts.get(index);
      json.append("{\"snapshotId\":\"")
          .append(snapshot.snapshotId())
          .append("\",\"payloadHash\":\"")
          .append(snapshot.payloadHash())
          .append("\",\"operation\":\"")
          .append(part.providerOperation())
          .append("\",\"page\":")
          .append(part.pageNumber())
          .append('}');
    }
    return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void markParsed(SavedKmaWeatherSnapshot snapshot) {
    for (SnapshotSaveResult saved : snapshot.attemptSnapshots()) {
      if (saved.replayed() && saved.status() == SnapshotStatus.PARSED) continue;
      if (saved.status() != SnapshotStatus.RECEIVED) {
        throw KmaWeatherImportException.invalidResponse();
      }
      snapshots.transition(
          new SnapshotTransitionCommand(saved.snapshotId(), SnapshotStatus.PARSED, null));
    }
  }

  @Override
  public void markRejected(SavedKmaWeatherSnapshot snapshot) {
    for (SnapshotSaveResult saved : snapshot.attemptSnapshots()) {
      if (saved.status() != SnapshotStatus.RECEIVED) continue;
      snapshots.transition(
          new SnapshotTransitionCommand(
              saved.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
    }
  }
}
