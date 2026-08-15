package com.timingjeju.api.application.tourapi.reference;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportSourceKind;
import com.timingjeju.api.application.importing.ImportSyncMode;
import com.timingjeju.api.application.snapshot.SnapshotFailure;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotScope;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReferenceCodeSyncService {

  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String SCOPE = "jeju";
  private static final String PARSER_VERSION = "tourapi-reference-v1";

  private final ReferenceCodeSource source;
  private final ReferenceCodeParser parser;
  private final ReferenceCodeRepository repository;
  private final ImportRunLifecycleService runService;
  private final SnapshotStoreService snapshotService;
  private final Clock clock;

  public ReferenceCodeSyncService(
      ReferenceCodeSource source,
      ReferenceCodeParser parser,
      ReferenceCodeRepository repository,
      ImportRunLifecycleService runService,
      SnapshotStoreService snapshotService,
      Clock clock) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.runService = Objects.requireNonNull(runService, "runService는 필수입니다.");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public ReferenceCodeSyncResult sync(ReferenceCodeSyncCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    String operation = command.operation().provenanceOperation();
    ImportRunStartResult start =
        runService.start(
            new ImportRunStartCommand(
                ImportSourceKind.TOUR_API,
                "TourAPI KorService2 기준 코드",
                new ImportRunScope(PROVIDER, SERVICE, operation, SCOPE),
                "2026",
                PARSER_VERSION,
                "reference-code-v1",
                ImportSyncMode.FULL,
                requestFingerprint(command),
                command.idempotencyKey(),
                null));
    ImportRunLease lease = start.lease();
    if (start.replayed()) {
      return ReferenceCodeSyncResult.replayed(lease.runId());
    }

    ImportRunFailure terminalFailure = ImportRunFailure.PROVIDER_UNAVAILABLE;
    try {
      ReferenceCodeSourceResponse response = source.fetch(command.operation());
      terminalFailure = ImportRunFailure.INVALID_PROVIDER_RESPONSE;
      var fetchedAt = clock.instant();
      SnapshotSaveCommand snapshotCommand =
          new SnapshotSaveCommand(
              lease.runId(),
              new SnapshotScope(PROVIDER, SERVICE, operation, SCOPE),
              null,
              "1",
              200,
              null,
              fetchedAt,
              null,
              null,
              PARSER_VERSION,
              response.format(),
              "UTF-8",
              response.payload(),
              requestMetadata(command.operation()));
      SnapshotSaveResult snapshot = snapshotService.save(snapshotCommand);

      List<ReferenceCode> codes;
      try {
        codes = parser.parse(command.operation(), response.format(), response.payload());
      } catch (ReferenceCodeSyncException failure) {
        snapshotService.transition(
            new SnapshotTransitionCommand(
                snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
        throw failure;
      }

      snapshotService.transition(
          new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
      terminalFailure = ImportRunFailure.PARSE_REJECTED;
      ReferenceCodeUpsertResult stored =
          repository.upsert(
              new ReferenceCodeUpsertCommand(
                  codes,
                  command.validFrom(),
                  command.validTo(),
                  fetchedAt,
                  new ReferenceCodeLineage(
                      operation,
                      snapshot.requestFingerprint(),
                      snapshot.snapshotId(),
                      lease.runId())));
      runService.succeed(
          lease,
          new ImportRunCounts(
              codes.size(), 1, stored.inserted(), stored.updated(), stored.skipped(), 0, 0, 0));
      return ReferenceCodeSyncResult.completed(
          lease.runId(),
          snapshot.snapshotId(),
          stored.inserted(),
          stored.updated(),
          stored.skipped());
    } catch (ReferenceCodeSyncException failure) {
      runService.fail(lease, terminalFailure);
      throw failure;
    } catch (RuntimeException failure) {
      runService.fail(lease, terminalFailure);
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }

  private static Map<String, Object> requestMetadata(ReferenceCodeOperation operation) {
    return operation == ReferenceCodeOperation.LDONG
        ? Map.of("endpoint", operation.endpointPath(), "pageNo", "1", "lDongRegnCd", "50")
        : Map.of("endpoint", operation.endpointPath(), "pageNo", "1");
  }

  private static String requestFingerprint(ReferenceCodeSyncCommand command) {
    String value = command.operation().provenanceOperation() + ':' + command.idempotencyKey();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }
}
