package com.timingjeju.api.application.tourapi.sync;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IncrementalSyncService {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String OPERATION = "areaBasedSyncList2";
  private static final String SCOPE_KEY = "jeju";
  private static final String PARSER_VERSION = "tourapi-incremental-sync-v1";
  private static final int MAX_PAGES = 10_000;
  private static final ImportRunScope SCOPE =
      new ImportRunScope(PROVIDER, SERVICE, OPERATION, SCOPE_KEY);

  private final IncrementalSyncSource source;
  private final IncrementalSyncSnapshotGateway snapshots;
  private final IncrementalSyncParser parser;
  private final ImportCheckpointService checkpoints;
  private final ImportRunLifecycleService runs;
  private final IncrementalSyncCommitter committer;
  private final Clock clock;

  public IncrementalSyncService(
      IncrementalSyncSource source,
      IncrementalSyncSnapshotGateway snapshots,
      IncrementalSyncParser parser,
      ImportCheckpointService checkpoints,
      ImportRunLifecycleService runs,
      IncrementalSyncCommitter committer,
      Clock clock) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints는 필수입니다.");
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
  }

  public IncrementalSyncResult sync(IncrementalSyncCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    ImportCheckpoint checkpoint =
        checkpoints.find(SCOPE).orElseThrow(IncrementalSyncException::invalidResponse);
    IncrementalSyncCursor cursor = cursor(checkpoint);
    ImportRunStartResult started = runs.start(startCommand(command));
    ImportRunLease lease = started.lease();
    if (started.replayed()) {
      return replay(started, checkpoint);
    }

    try {
      List<IncrementalSyncWrite> writes = new ArrayList<>();
      List<IncrementalSyncPageLineage> pages = new ArrayList<>();
      Set<String> contentIds = new HashSet<>();
      int expectedTotal = -1;
      int fetched = 0;
      int pageNo = 1;
      Instant newestModifiedAt = cursor.modifiedAfter();
      while (pageNo <= MAX_PAGES) {
        IncrementalSyncSourceResponse response = source.fetch(cursor, pageNo);
        SavedIncrementalSyncPage saved = snapshots.save(lease.runId(), cursor, pageNo, response);
        requireParsable(saved);
        IncrementalSyncPage page;
        try {
          page = parser.parse(saved.storedResponse().format(), saved.storedResponse().payload());
          expectedTotal = validatePage(page, pageNo, expectedTotal, fetched);
          for (PlaceSyncChange change : page.changes()) {
            if (!contentIds.add(change.contentId())) {
              throw IncrementalSyncException.invalidResponse();
            }
          }
        } catch (RuntimeException failure) {
          snapshots.markRejected(saved);
          throw failure;
        }
        snapshots.markParsed(saved);
        IncrementalSyncPageLineage pageLineage =
            new IncrementalSyncPageLineage(
                pageNo,
                page.rawItemCount(),
                saved.payloadHash(),
                saved.fetchedAt(),
                saved.lineage());
        pages.add(pageLineage);
        for (PlaceSyncChange change : page.changes()) {
          writes.add(new IncrementalSyncWrite(change, saved.fetchedAt(), saved.lineage()));
          if (change.sourceModifiedAt().isAfter(newestModifiedAt)) {
            newestModifiedAt = change.sourceModifiedAt();
          }
        }
        fetched += page.rawItemCount();
        if (fetched == expectedTotal) {
          IncrementalSyncCommitResult committed =
              committer.commit(
                  new IncrementalSyncCommitCommand(
                      lease,
                      checkpoint.version(),
                      cursor,
                      new IncrementalSyncCursor(newestModifiedAt),
                      pages.stream()
                          .map(IncrementalSyncPageLineage::fetchedAt)
                          .max(Instant::compareTo)
                          .orElseThrow(),
                      writes,
                      pages));
          return new IncrementalSyncResult(
              lease.runId(),
              pages.size(),
              committed.counts(),
              committed.checkpointVersion(),
              false);
        }
        pageNo++;
      }
      throw IncrementalSyncException.invalidResponse();
    } catch (ImportCheckpointException failure) {
      runs.fail(
          lease,
          failure.code() == ImportCheckpointError.STALE_VERSION
              ? ImportRunFailure.STALE_WRITER
              : ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      throw failure;
    } catch (IncrementalSyncException failure) {
      runs.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      throw failure;
    } catch (RuntimeException failure) {
      runs.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      throw IncrementalSyncException.invalidResponse();
    }
  }

  private static IncrementalSyncResult replay(
      ImportRunStartResult started, ImportCheckpoint checkpoint) {
    if (started.status() != ImportRunExecutionStatus.SUCCEEDED
        || !started.lease().runId().equals(checkpoint.lastSucceededRunId())) {
      throw IncrementalSyncException.invalidResponse();
    }
    return IncrementalSyncResult.replayed(
        started.lease().runId(),
        started.counts().fetchedCount(),
        started.counts(),
        checkpoint.version());
  }

  private static void requireParsable(SavedIncrementalSyncPage saved) {
    if (saved.status() == SnapshotStatus.RECEIVED) return;
    if (saved.replayed() && saved.status() == SnapshotStatus.PARSED) return;
    throw IncrementalSyncException.invalidResponse();
  }

  private static int validatePage(
      IncrementalSyncPage page, int requestedPage, int expectedTotal, int fetched) {
    if (page.pageNo() != requestedPage
        || page.numOfRows() != IncrementalSyncRequestContract.PAGE_SIZE) {
      throw IncrementalSyncException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    if (page.totalCount() != total || fetched + page.rawItemCount() > total) {
      throw IncrementalSyncException.invalidResponse();
    }
    if (fetched + page.rawItemCount() < total
        && page.rawItemCount() != IncrementalSyncRequestContract.PAGE_SIZE) {
      throw IncrementalSyncException.invalidResponse();
    }
    return total;
  }

  private static IncrementalSyncCursor cursor(ImportCheckpoint checkpoint) {
    Object value = checkpoint.checkpoint().get("modifiedTime");
    if (!(value instanceof String text)) {
      throw IncrementalSyncException.invalidResponse();
    }
    try {
      return new IncrementalSyncCursor(Instant.parse(text));
    } catch (RuntimeException failure) {
      throw IncrementalSyncException.invalidResponse();
    }
  }

  private static ImportRunStartCommand startCommand(IncrementalSyncCommand command) {
    return new ImportRunStartCommand(
        ImportSourceKind.TOUR_API,
        "TourAPI 제주 장소 증분 동기화",
        SCOPE,
        "2026",
        PARSER_VERSION,
        "tourapi-incremental-sync-v1",
        ImportSyncMode.INCREMENTAL,
        sha256(OPERATION + ":jeju:" + PARSER_VERSION),
        command.idempotencyKey(),
        null);
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
