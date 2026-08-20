package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
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
import com.timingjeju.api.application.tourapi.place.PlaceAliasWrite;
import com.timingjeju.api.application.tourapi.place.PlaceLineage;
import com.timingjeju.api.application.tourapi.place.PlaceListPage;
import com.timingjeju.api.application.tourapi.place.PlaceListRequestContract;
import com.timingjeju.api.application.tourapi.place.PlaceListSourceResponse;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertResult;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DiscoveryImportService {

  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String PARSER_VERSION = "tourapi-discovery-v1";
  private static final int MAX_PAGE_ATTEMPTS = 3;
  private static final int MAX_PAGE_COUNT = 100;

  private final DiscoverySource source;
  private final DiscoveryParser parser;
  private final DiscoveryCommitter committer;
  private final ImportRunLifecycleService runService;
  private final ImportCheckpointService checkpointService;
  private final SnapshotStoreService snapshotService;
  private final Clock clock;

  public DiscoveryImportService(
      DiscoverySource source,
      DiscoveryParser parser,
      DiscoveryCommitter committer,
      ImportRunLifecycleService runService,
      ImportCheckpointService checkpointService,
      SnapshotStoreService snapshotService,
      Clock clock) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
    this.runService = Objects.requireNonNull(runService, "runService는 필수입니다.");
    this.checkpointService = Objects.requireNonNull(checkpointService, "checkpointService는 필수입니다.");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public DiscoveryImportResult importCandidates(DiscoveryImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    ImportRunScope scope = scope(command);
    ImportRunStartResult start = runService.start(startCommand(command));
    ImportRunLease lease = start.lease();
    Optional<ImportCheckpoint> checkpoint = checkpointService.find(scope);
    if (start.replayed()) {
      return replay(start, checkpoint.orElseThrow(DiscoveryImportException::invalidResponse));
    }

    long expectedCheckpointVersion =
        checkpoint.orElseThrow(DiscoveryImportException::storageFailure).version();
    ImportRunFailure terminalFailure = ImportRunFailure.PROVIDER_UNAVAILABLE;
    try {
      List<PlaceListWrite> writes = new ArrayList<>();
      Map<PlaceRejectReason, Integer> rejectedReasons = new EnumMap<>(PlaceRejectReason.class);
      Set<String> contentIds = new HashSet<>();
      List<String> pageManifest = new ArrayList<>();
      int expectedTotal = -1;
      int rawRows = 0;
      int pageNo = 1;
      while (pageNo <= command.pageBudget()) {
        PlaceListSourceResponse response = fetchWithRetry(command, pageNo);
        terminalFailure = ImportRunFailure.INVALID_PROVIDER_RESPONSE;
        Instant fetchedAt = clock.instant();
        SnapshotSaveResult snapshot =
            snapshotService.save(snapshotCommand(command, lease, pageNo, response, fetchedAt));
        PlaceListPage page;
        try {
          page = parser.parse(command.operation(), response.format(), response.payload());
          expectedTotal = validatePage(page, pageNo, expectedTotal, rawRows);
          for (var place : page.places()) {
            if (!contentIds.add(place.contentId())) {
              throw DiscoveryImportException.invalidResponse();
            }
          }
        } catch (RuntimeException failure) {
          snapshotService.transition(
              new SnapshotTransitionCommand(
                  snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
          throw DiscoveryImportException.invalidResponse();
        }
        snapshotService.transition(
            new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
        pageManifest.add(
            snapshot.snapshotId()
                + ":"
                + snapshot.requestFingerprint()
                + ":"
                + snapshot.payloadHash());
        PlaceLineage lineage =
            new PlaceLineage(
                command.operation().operationKey(),
                snapshot.requestFingerprint(),
                snapshot.snapshotId(),
                lease.runId());
        List<PlaceAliasWrite> aliases = aliases(command);
        page.places()
            .forEach(place -> writes.add(new PlaceListWrite(place, fetchedAt, lineage, aliases)));
        page.rejectedReasons()
            .forEach((reason, count) -> rejectedReasons.merge(reason, count, Integer::sum));
        rawRows += page.rawItemCount();
        if (rawRows == expectedTotal) {
          break;
        }
        pageNo++;
      }
      if (rawRows != expectedTotal) {
        throw DiscoveryImportException.invalidResponse();
      }

      int rejected = rejectedReasons.values().stream().mapToInt(Integer::intValue).sum();
      ImportRunCounts counts = new ImportRunCounts(rawRows, pageNo, 0, 0, 0, rejected, 0, 0);
      terminalFailure = ImportRunFailure.PARSE_REJECTED;
      PlaceListUpsertResult stored =
          committer.commit(
              new DiscoveryCommitCommand(
                  lease,
                  scope,
                  expectedCheckpointVersion,
                  clock.instant(),
                  sha256(String.join("|", pageManifest)),
                  pageNo,
                  writes,
                  counts));
      return new DiscoveryImportResult(
          lease.runId(),
          pageNo,
          stored.inserted(),
          stored.updated(),
          stored.skipped(),
          rejected,
          rejectedReasons,
          false);
    } catch (DiscoveryImportException failure) {
      runService.fail(lease, terminalFailure);
      throw failure;
    } catch (RuntimeException failure) {
      runService.fail(lease, terminalFailure);
      throw terminalFailure == ImportRunFailure.PARSE_REJECTED
          ? DiscoveryImportException.storageFailure()
          : DiscoveryImportException.invalidResponse();
    }
  }

  private static DiscoveryImportResult replay(
      ImportRunStartResult start, ImportCheckpoint checkpoint) {
    if (start.status() != ImportRunExecutionStatus.SUCCEEDED
        || !start.lease().runId().equals(checkpoint.lastSucceededRunId())) {
      throw DiscoveryImportException.invalidResponse();
    }
    return DiscoveryImportResult.replayed(
        start.lease().runId(), start.counts(), pageCount(checkpoint));
  }

  private static int pageCount(ImportCheckpoint checkpoint) {
    Object value = checkpoint.checkpoint().get("pageCount");
    try {
      int pageCount = new BigDecimal(value.toString()).intValueExact();
      if (pageCount < 1 || pageCount > MAX_PAGE_COUNT) {
        throw DiscoveryImportException.invalidResponse();
      }
      return pageCount;
    } catch (RuntimeException failure) {
      throw DiscoveryImportException.invalidResponse();
    }
  }

  private PlaceListSourceResponse fetchWithRetry(DiscoveryImportCommand command, int pageNo) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS; attempt++) {
      try {
        return source.fetch(command, pageNo);
      } catch (RuntimeException failure) {
        last = failure;
      }
    }
    throw Objects.requireNonNull(last);
  }

  private static int validatePage(
      PlaceListPage page, int requestedPage, int expectedTotal, int alreadyFetched) {
    if (page.pageNo() != requestedPage
        || page.numOfRows() != PlaceListRequestContract.PAGE_SIZE
        || (expectedTotal >= 0 && page.totalCount() != expectedTotal)
        || alreadyFetched + page.rawItemCount() > page.totalCount()
        || (alreadyFetched < page.totalCount() && page.rawItemCount() == 0)) {
      throw DiscoveryImportException.invalidResponse();
    }
    return expectedTotal < 0 ? page.totalCount() : expectedTotal;
  }

  private static List<PlaceAliasWrite> aliases(DiscoveryImportCommand command) {
    if (command.operation() != DiscoveryOperation.KEYWORD) {
      return List.of();
    }
    String normalized =
        Normalizer.normalize(command.keyword(), Normalizer.Form.NFC)
            .strip()
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    return List.of(new PlaceAliasWrite(command.keyword(), normalized));
  }

  private static ImportRunStartCommand startCommand(DiscoveryImportCommand command) {
    String canonical = canonicalRequest(command);
    return new ImportRunStartCommand(
        ImportSourceKind.TOUR_API,
        "TourAPI 제주 후보 보강",
        scope(command),
        "2026",
        PARSER_VERSION,
        "tourapi-discovery-v1",
        ImportSyncMode.FULL,
        sha256(canonical),
        command.idempotencyKey(),
        null);
  }

  private static SnapshotSaveCommand snapshotCommand(
      DiscoveryImportCommand command,
      ImportRunLease lease,
      int pageNo,
      PlaceListSourceResponse response,
      Instant fetchedAt) {
    return new SnapshotSaveCommand(
        lease.runId(),
        new SnapshotScope(PROVIDER, SERVICE, command.operation().operationKey(), "jeju"),
        null,
        Integer.toString(pageNo),
        200,
        "0000",
        fetchedAt,
        null,
        null,
        PARSER_VERSION,
        response.format(),
        "UTF-8",
        response.payload(),
        Map.of(
            "endpoint",
            command.operation().relativePath(),
            "pageNo",
            Integer.toString(pageNo),
            "numOfRows",
            Integer.toString(PlaceListRequestContract.PAGE_SIZE),
            "requestContractFingerprint",
            sha256(canonicalRequest(command))));
  }

  private static ImportRunScope scope(DiscoveryImportCommand command) {
    return new ImportRunScope(PROVIDER, SERVICE, command.operation().operationKey(), "jeju");
  }

  private static String canonicalRequest(DiscoveryImportCommand command) {
    return switch (command.operation()) {
      case LOCATION ->
          "locationBasedList2:mapX="
              + command.longitude()
              + ":mapY="
              + command.latitude()
              + ":radius="
              + command.radiusMeters();
      case KEYWORD -> "searchKeyword2:keyword=" + command.keyword() + ":lDongRegnCd=50";
      case STAY -> "searchStay2:lDongRegnCd=50";
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
}
