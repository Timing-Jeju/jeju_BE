package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherImportCommand;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherImportService;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
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
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    SnapshotSaveResult saved =
        snapshots.save(
            new SnapshotSaveCommand(
                runId,
                new SnapshotScope(
                    KmaWeatherImportService.PROVIDER,
                    KmaWeatherImportService.SERVICE,
                    operation.providerOperation(),
                    "nx=" + command.nx() + ";ny=" + command.ny()),
                null,
                baseDate + baseTime,
                200,
                null,
                clock.instant(),
                null,
                null,
                KmaWeatherImportService.PARSER_VERSION,
                response.format(),
                "UTF-8",
                response.payload(),
                metadata));
    return new SavedKmaWeatherSnapshot(
        response,
        saved.snapshotId(),
        saved.requestFingerprint(),
        saved.payloadHash(),
        saved.fetchedAt(),
        saved.replayed(),
        saved.status());
  }

  @Override
  public void markParsed(SavedKmaWeatherSnapshot snapshot) {
    if (snapshot.replayed() && snapshot.status() == SnapshotStatus.PARSED) return;
    if (snapshot.status() != SnapshotStatus.RECEIVED) {
      throw KmaWeatherImportException.invalidResponse();
    }
    snapshots.transition(
        new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
  }

  @Override
  public void markRejected(SavedKmaWeatherSnapshot snapshot) {
    if (snapshot.status() != SnapshotStatus.RECEIVED) return;
    snapshots.transition(
        new SnapshotTransitionCommand(
            snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
