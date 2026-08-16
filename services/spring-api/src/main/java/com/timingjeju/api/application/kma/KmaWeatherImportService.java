package com.timingjeju.api.application.kma;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
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
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class KmaWeatherImportService implements KmaVillageForecastImporter {

  public static final String PROVIDER = "kma";
  public static final String SERVICE = "VilageFcstInfoService_2.0";
  public static final String PARSER_VERSION = "kma-ultra-weather-v1";
  public static final String VILLAGE_PARSER_VERSION = "kma-village-weather-v2";

  private final KmaWeatherSource source;
  private final KmaWeatherSnapshotGateway snapshots;
  private final KmaWeatherParser parser;
  private final ImportCheckpointService checkpoints;
  private final ImportRunLifecycleService runs;
  private final KmaWeatherCommitter committer;
  private final KmaWeatherBaseTimeResolver bases;

  public KmaWeatherImportService(
      KmaWeatherSource source,
      KmaWeatherSnapshotGateway snapshots,
      KmaWeatherParser parser,
      ImportCheckpointService checkpoints,
      ImportRunLifecycleService runs,
      KmaWeatherCommitter committer,
      KmaWeatherBaseTimeResolver bases) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints는 필수입니다.");
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
    this.bases = Objects.requireNonNull(bases, "bases는 필수입니다.");
  }

  public KmaWeatherImportResult importWeather(
      KmaWeatherImportCommand command, KmaWeatherOperation operation) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    ImportRunScope scope = scope(operation, command.nx(), command.ny());
    Optional<ImportCheckpoint> checkpoint = checkpoints.find(scope);
    ImportRunStartResult started = runs.start(startCommand(command, operation, scope));
    ImportRunLease lease = started.lease();
    if (started.replayed()) return replay(started, checkpoint);

    try {
      return importWithFallback(command, operation, scope, checkpoint, lease);
    } catch (ImportCheckpointException failure) {
      runs.fail(
          lease,
          failure.code() == ImportCheckpointError.STALE_VERSION
              ? ImportRunFailure.STALE_WRITER
              : ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      throw failure;
    } catch (KmaWeatherImportException failure) {
      runs.fail(lease, importFailure(failure));
      throw failure;
    } catch (RuntimeException failure) {
      runs.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      throw KmaWeatherImportException.storageFailure();
    }
  }

  @Override
  public KmaWeatherImportResult importVillageForecast(KmaWeatherImportCommand command) {
    return importWeather(command, KmaWeatherOperation.VILLAGE_FORECAST);
  }

  private KmaWeatherImportResult importWithFallback(
      KmaWeatherImportCommand command,
      KmaWeatherOperation operation,
      ImportRunScope scope,
      Optional<ImportCheckpoint> checkpoint,
      ImportRunLease lease) {
    ForecastBaseTime latest = bases.latest(operation);
    try {
      return attempt(command, operation, scope, checkpoint, lease, latest, false);
    } catch (RetryPreviousBase ignored) {
      try {
        return attempt(
            command, operation, scope, checkpoint, lease, bases.previous(operation, latest), true);
      } catch (RetryPreviousBase finalFailure) {
        throw finalFailure.cause;
      }
    }
  }

  private KmaWeatherImportResult attempt(
      KmaWeatherImportCommand command,
      KmaWeatherOperation operation,
      ImportRunScope scope,
      Optional<ImportCheckpoint> checkpoint,
      ImportRunLease lease,
      ForecastBaseTime base,
      boolean stale) {
    KmaWeatherSourceResponse response;
    try {
      response = source.fetch(operation, base, command.nx(), command.ny());
    } catch (RuntimeException failure) {
      throw retry(
          failure instanceof KmaWeatherImportException weatherFailure
              ? weatherFailure
              : KmaWeatherImportException.providerUnavailable());
    }

    SavedKmaWeatherSnapshot snapshot =
        snapshots.capture(lease.runId(), operation, base, command, response);
    requireParsable(snapshot);
    KmaWeatherBatch batch;
    try {
      batch = parser.parse(operation, snapshot.response().payload());
      requireMatchingGridAndBase(command, base, batch, operation);
    } catch (KmaWeatherImportException failure) {
      snapshots.markRejected(snapshot);
      if (failure.code() == KmaWeatherImportError.UNSUPPORTED_CATEGORY) throw failure;
      throw retry(failure);
    }
    snapshots.markParsed(snapshot);
    KmaWeatherCommitResult committed =
        committer.commit(
            new KmaWeatherCommitCommand(
                lease,
                scope,
                checkpoint.map(ImportCheckpoint::version).orElse(0L),
                command.gridPointId(),
                base,
                batch,
                new KmaWeatherLineage(
                    operation.providerOperation(),
                    snapshot.requestFingerprint(),
                    snapshot.snapshotId(),
                    lease.runId()),
                snapshot.fetchedAt(),
                stale));
    return new KmaWeatherImportResult(
        lease.runId(),
        committed.counts(),
        committed.checkpointVersion(),
        stale ? KmaWeatherFreshness.STALE_WEATHER_DATA : KmaWeatherFreshness.FRESH,
        false);
  }

  private static void requireParsable(SavedKmaWeatherSnapshot snapshot) {
    if (snapshot.attemptSnapshots().stream()
        .allMatch(saved -> saved.status() == SnapshotStatus.RECEIVED)) return;
    if (snapshot.attemptSnapshots().stream()
        .allMatch(saved -> saved.replayed() && saved.status() == SnapshotStatus.PARSED)) return;
    throw KmaWeatherImportException.invalidResponse();
  }

  private static void requireMatchingGridAndBase(
      KmaWeatherImportCommand command,
      ForecastBaseTime base,
      KmaWeatherBatch batch,
      KmaWeatherOperation operation) {
    if (batch.nx() != command.nx() || batch.ny() != command.ny()) {
      throw KmaWeatherImportException.invalidResponse();
    }
    boolean matches =
        switch (operation) {
          case ULTRA_CURRENT ->
              batch.observations().size() == 1
                  && batch.forecasts().isEmpty()
                  && batch.observations().getFirst().baseDate().equals(base.baseDate())
                  && batch.observations().getFirst().baseTime().equals(base.baseTime());
          case ULTRA_FORECAST, VILLAGE_FORECAST ->
              batch.observations().isEmpty()
                  && !batch.forecasts().isEmpty()
                  && batch.forecasts().stream()
                      .allMatch(
                          forecast ->
                              forecast
                                  .forecastedAt()
                                  .equals(
                                      java.time.LocalDateTime.of(base.baseDate(), base.baseTime())
                                          .atZone(java.time.ZoneId.of("Asia/Seoul"))
                                          .toInstant()));
        };
    if (!matches) throw KmaWeatherImportException.invalidResponse();
  }

  private static RetryPreviousBase retry(KmaWeatherImportException cause) {
    return new RetryPreviousBase(cause);
  }

  private static KmaWeatherImportResult replay(
      ImportRunStartResult started, Optional<ImportCheckpoint> checkpoint) {
    ImportCheckpoint stored = checkpoint.orElseThrow(KmaWeatherImportException::invalidReplay);
    if (started.status() != ImportRunExecutionStatus.SUCCEEDED
        || !started.lease().runId().equals(stored.lastSucceededRunId())) {
      throw KmaWeatherImportException.invalidReplay();
    }
    Object stale = stored.checkpoint().get("stale");
    return new KmaWeatherImportResult(
        started.lease().runId(),
        started.counts(),
        stored.version(),
        Boolean.TRUE.equals(stale)
            ? KmaWeatherFreshness.STALE_WEATHER_DATA
            : KmaWeatherFreshness.FRESH,
        true);
  }

  private static ImportRunStartCommand startCommand(
      KmaWeatherImportCommand command, KmaWeatherOperation operation, ImportRunScope scope) {
    return new ImportRunStartCommand(
        ImportSourceKind.WEATHER_API,
        "KMA " + operation.providerOperation(),
        scope,
        "2607",
        parserVersion(operation),
        parserVersion(operation),
        ImportSyncMode.SNAPSHOT,
        sha256(operation.providerOperation() + ':' + command.nx() + ':' + command.ny()),
        command.idempotencyKey(),
        null);
  }

  public static ImportRunScope scope(KmaWeatherOperation operation, int nx, int ny) {
    return new ImportRunScope(
        PROVIDER, SERVICE, operation.providerOperation(), "nx=" + nx + ";ny=" + ny);
  }

  public static String parserVersion(KmaWeatherOperation operation) {
    return operation == KmaWeatherOperation.VILLAGE_FORECAST
        ? VILLAGE_PARSER_VERSION
        : PARSER_VERSION;
  }

  private static ImportRunFailure importFailure(KmaWeatherImportException failure) {
    return switch (failure.code()) {
      case PROVIDER_UNAVAILABLE -> ImportRunFailure.PROVIDER_UNAVAILABLE;
      case STORAGE_FAILURE -> ImportRunFailure.INVALID_PROVIDER_RESPONSE;
      case INVALID_PROVIDER_RESPONSE, UNSUPPORTED_CATEGORY, INVALID_REPLAY ->
          ImportRunFailure.INVALID_PROVIDER_RESPONSE;
    };
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

  private static final class RetryPreviousBase extends RuntimeException {
    private final KmaWeatherImportException cause;

    private RetryPreviousBase(KmaWeatherImportException cause) {
      super(null, cause, false, false);
      this.cause = cause;
    }
  }
}
