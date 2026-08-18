package com.timingjeju.api.application.tourapi.place;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlaceListImportService {

  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String OPERATION = "areaBasedList2";
  private static final String SCOPE = "jeju";
  private static final String PARSER_VERSION = "tourapi-place-list-v1";
  private static final int MAX_PAGES = 10_000;

  private final PlaceListSource source;
  private final PlaceListParser parser;
  private final PlaceListRepository repository;
  private final ImportRunLifecycleService runService;
  private final SnapshotStoreService snapshotService;
  private final Clock clock;

  public PlaceListImportService(
      PlaceListSource source,
      PlaceListParser parser,
      PlaceListRepository repository,
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

  public PlaceListImportResult importPlaces(PlaceListImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    ImportRunStartResult start = runService.start(startCommand(command));
    ImportRunLease lease = start.lease();
    if (start.replayed()) {
      return PlaceListImportResult.replayed(lease.runId());
    }

    ImportRunFailure terminalFailure = ImportRunFailure.PROVIDER_UNAVAILABLE;
    try {
      List<PlaceListWrite> writes = new ArrayList<>();
      Map<PlaceRejectReason, Integer> rejectedReasons = new EnumMap<>(PlaceRejectReason.class);
      int expectedTotal = -1;
      int rawRows = 0;
      int pageNo = 1;
      while (true) {
        if (pageNo > MAX_PAGES) {
          throw PlaceListImportException.invalidResponse();
        }
        PlaceListSourceResponse response = source.fetch(pageNo);
        terminalFailure = ImportRunFailure.INVALID_PROVIDER_RESPONSE;
        Instant fetchedAt = clock.instant();
        SnapshotSaveResult snapshot =
            snapshotService.save(snapshotCommand(lease, pageNo, response, fetchedAt));
        PlaceListPage page;
        try {
          page = parser.parse(response.format(), response.payload());
          expectedTotal = validatePage(page, pageNo, expectedTotal, rawRows);
        } catch (PlaceListImportException failure) {
          snapshotService.transition(
              new SnapshotTransitionCommand(
                  snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
          throw failure;
        }
        snapshotService.transition(
            new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
        PlaceLineage lineage =
            new PlaceLineage(
                OPERATION, snapshot.requestFingerprint(), snapshot.snapshotId(), lease.runId());
        page.places().forEach(place -> writes.add(new PlaceListWrite(place, fetchedAt, lineage)));
        page.rejectedReasons()
            .forEach((reason, count) -> rejectedReasons.merge(reason, count, Integer::sum));
        rawRows += page.rawItemCount();
        if (rawRows == expectedTotal) {
          break;
        }
        pageNo++;
      }
      if (rawRows != expectedTotal) {
        throw PlaceListImportException.invalidResponse();
      }

      terminalFailure = ImportRunFailure.PARSE_REJECTED;
      PlaceListUpsertResult stored =
          writes.isEmpty()
              ? new PlaceListUpsertResult(0, 0, 0)
              : repository.upsert(new PlaceListUpsertCommand(writes));
      int rejected = rejectedReasons.values().stream().mapToInt(Integer::intValue).sum();
      ImportRunCounts counts =
          new ImportRunCounts(
              rawRows,
              pageNo,
              stored.inserted(),
              stored.updated(),
              stored.skipped(),
              rejected,
              0,
              0);
      if (rejected == 0) {
        runService.succeed(lease, counts);
      } else {
        runService.completePartial(lease, counts, ImportRunFailure.PARSE_REJECTED);
      }
      return new PlaceListImportResult(
          lease.runId(),
          pageNo,
          stored.inserted(),
          stored.updated(),
          stored.skipped(),
          rejected,
          rejectedReasons,
          false);
    } catch (PlaceListImportException failure) {
      runService.fail(lease, terminalFailure);
      throw failure;
    } catch (RuntimeException failure) {
      runService.fail(lease, terminalFailure);
      throw terminalFailure == ImportRunFailure.PARSE_REJECTED
          ? PlaceListImportException.storageFailure()
          : PlaceListImportException.invalidResponse();
    }
  }

  private static int validatePage(
      PlaceListPage page, int requestedPage, int expectedTotal, int alreadyFetched) {
    if (page.pageNo() != requestedPage) {
      throw PlaceListImportException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    int remaining = total - alreadyFetched;
    if (remaining <= 0) {
      throw PlaceListImportException.invalidResponse();
    }
    int expectedRows = Math.min(PlaceListRequestContract.PAGE_SIZE, remaining);
    if (page.numOfRows() != expectedRows || page.rawItemCount() != expectedRows) {
      throw PlaceListImportException.invalidResponse();
    }
    if (page.totalCount() != total || alreadyFetched + page.rawItemCount() > total) {
      throw PlaceListImportException.invalidResponse();
    }
    if (alreadyFetched < total && page.rawItemCount() == 0) {
      throw PlaceListImportException.invalidResponse();
    }
    return total;
  }

  private static ImportRunStartCommand startCommand(PlaceListImportCommand command) {
    return new ImportRunStartCommand(
        ImportSourceKind.TOUR_API,
        "TourAPI 제주 장소 기본 목록",
        new ImportRunScope(PROVIDER, SERVICE, OPERATION, SCOPE),
        "2026",
        PARSER_VERSION,
        "tour-place-list-v1",
        ImportSyncMode.FULL,
        sha256(OPERATION + ":lDongRegnCd=50:numOfRows=" + PlaceListRequestContract.PAGE_SIZE),
        command.idempotencyKey(),
        null);
  }

  private static SnapshotSaveCommand snapshotCommand(
      ImportRunLease lease, int pageNo, PlaceListSourceResponse response, Instant fetchedAt) {
    return new SnapshotSaveCommand(
        lease.runId(),
        new SnapshotScope(PROVIDER, SERVICE, OPERATION, SCOPE),
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
            "areaBasedList2",
            "pageNo",
            Integer.toString(pageNo),
            "numOfRows",
            Integer.toString(PlaceListRequestContract.PAGE_SIZE),
            "lDongRegnCd",
            "50"));
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
