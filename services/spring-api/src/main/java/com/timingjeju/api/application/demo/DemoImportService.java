package com.timingjeju.api.application.demo;

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
import com.timingjeju.api.application.tourapi.detail.DetailCommonParser;
import com.timingjeju.api.application.tourapi.detail.DetailCommonSource;
import com.timingjeju.api.application.tourapi.detail.DetailIntroParser;
import com.timingjeju.api.application.tourapi.detail.DetailIntroSource;
import com.timingjeju.api.application.tourapi.detail.DetailLineage;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailCommon;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailIntro;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailRepository;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertCommand;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailUpsertResult;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportCommand;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportService;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemSyncResult;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportCommand;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportService;
import com.timingjeju.api.application.tourapi.image.PlaceImageSyncResult;
import com.timingjeju.api.application.tourapi.place.PlaceListImportCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListImportResult;
import com.timingjeju.api.application.tourapi.place.PlaceListImportService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

public final class DemoImportService {
  private static final String PROVIDER = "tour-api";
  private static final String SERVICE = "KorService2";
  private static final String PARSER_VERSION = "tour-demo-detail-v1";
  private static final String SCHEMA_VERSION = "tour-demo-v1";
  private static final String SCOPE_PREFIX = "content:";
  private static final String DATA_VERSION = "2026";
  private static final List<String> DETAIL_CONTENT_TYPES = List.of("12", "32", "39");

  private final PlaceListImportService placeImporter;
  private final DemoStorageReader storageReader;
  private final ImportRunLifecycleService runService;
  private final SnapshotStoreService snapshotService;
  private final DetailCommonSource commonSource;
  private final DetailCommonParser commonParser;
  private final DetailIntroSource introSource;
  private final DetailIntroParser introParser;
  private final PlaceDetailRepository detailRepository;
  private final DetailItemImportService detailItemImportService;
  private final PlaceImageImportService detailImageImportService;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public DemoImportService(
      PlaceListImportService placeImporter,
      DemoStorageReader storageReader,
      ImportRunLifecycleService runService,
      SnapshotStoreService snapshotService,
      DetailCommonSource commonSource,
      DetailCommonParser commonParser,
      DetailIntroSource introSource,
      DetailIntroParser introParser,
      PlaceDetailRepository detailRepository,
      DetailItemImportService detailItemImportService,
      PlaceImageImportService detailImageImportService,
      Clock clock,
      ObjectMapper objectMapper) {
    this.placeImporter = Objects.requireNonNull(placeImporter, "placeImporter는 필수입니다.");
    this.storageReader = Objects.requireNonNull(storageReader, "storageReader는 필수입니다.");
    this.runService = Objects.requireNonNull(runService, "runService는 필수입니다.");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService는 필수입니다.");
    this.commonSource = Objects.requireNonNull(commonSource, "commonSource는 필수입니다.");
    this.commonParser = Objects.requireNonNull(commonParser, "commonParser는 필수입니다.");
    this.introSource = Objects.requireNonNull(introSource, "introSource는 필수입니다.");
    this.introParser = Objects.requireNonNull(introParser, "introParser는 필수입니다.");
    this.detailRepository = Objects.requireNonNull(detailRepository, "detailRepository는 필수입니다.");
    this.detailItemImportService =
        Objects.requireNonNull(detailItemImportService, "detailItemImportService는 필수입니다.");
    this.detailImageImportService =
        Objects.requireNonNull(detailImageImportService, "detailImageImportService는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
  }

  public DemoImportResult importTourPlaces() {
    PlaceListImportResult result =
        placeImporter.importPlaces(
            new PlaceListImportCommand("demo-" + clock.instant().toEpochMilli()));
    List<DemoPlaceRow> candidates =
        storageReader.candidates(result.runId(), DETAIL_CONTENT_TYPES.toArray(new String[0]));
    int detailStageSucceeded = 0;
    int detailStageFailed = 0;
    for (DemoPlaceRow place : candidates) {
      StageStatus detailCommonStatus = importPlaceDetail(place.contentId(), place.contentTypeId());
      StageStatus detailInfoStatus = importDetailInfo(place.contentId(), place.contentTypeId());
      StageStatus detailImageStatus = importDetailImage(place.contentId(), place.contentTypeId());
      detailStageSucceeded +=
          stageDelta(detailCommonStatus)
              + stageDelta(detailInfoStatus)
              + stageDelta(detailImageStatus);
      detailStageFailed +=
          failureDelta(detailCommonStatus)
              + failureDelta(detailInfoStatus)
              + failureDelta(detailImageStatus);
    }
    return new DemoImportResult(
        result.runId(),
        result.pageCount(),
        result.inserted(),
        result.updated(),
        result.skipped(),
        result.rejected(),
        result.replayed(),
        candidates.size(),
        detailStageSucceeded,
        detailStageFailed);
  }

  private static int stageDelta(StageStatus status) {
    return status == StageStatus.SUCCEEDED ? 1 : 0;
  }

  private static int failureDelta(StageStatus status) {
    return status == StageStatus.FAILED ? 1 : 0;
  }

  private enum StageStatus {
    SUCCEEDED,
    FAILED,
    SKIPPED
  }

  public DemoStorageView latestStorage() {
    return storageReader.latest();
  }

  public String storageView() {
    return buildSafeHtml(storageReader.latest());
  }

  private StageStatus importPlaceDetail(String contentId, String contentTypeId) {
    ImportRunStartResult commonStart =
        startRun(
            "detailCommon2",
            "demo-detail-common-" + contentId,
            contentId,
            detailRequestFingerprint("detailCommon2", contentId, null));
    ImportRunStartResult introStart =
        startRun(
            "detailIntro2",
            "demo-detail-intro-" + contentId + "-" + contentTypeId,
            contentId,
            detailRequestFingerprint("detailIntro2", contentId, contentTypeId));
    if (commonStart.replayed()) {
      if (introStart.replayed()) {
        return StageStatus.SKIPPED;
      }
      runService.fail(introStart.lease(), ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      return StageStatus.FAILED;
    }
    if (introStart.replayed()) {
      runService.fail(commonStart.lease(), ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      return StageStatus.FAILED;
    }

    ImportRunLease commonLease = commonStart.lease();
    SavedCommonSection common;
    ImportRunLease introLease = introStart.lease();
    try {
      common = parseCommonWithLineage(commonLease, contentId);
    } catch (RuntimeException failure) {
      runService.fail(commonLease, ImportRunFailure.PARSE_REJECTED);
      runService.fail(introLease, ImportRunFailure.PARSE_REJECTED);
      return StageStatus.FAILED;
    }
    SavedIntroSection intro;
    try {
      intro = parseIntroWithLineage(introLease, contentId, contentTypeId);
    } catch (RuntimeException failure) {
      runService.fail(commonLease, ImportRunFailure.PARSE_REJECTED);
      runService.fail(introLease, ImportRunFailure.PARSE_REJECTED);
      return StageStatus.FAILED;
    }
    try {
      PlaceDetailUpsertResult upserted =
          detailRepository.upsert(
              new PlaceDetailUpsertCommand(
                  contentId,
                  common.detail(),
                  intro.detail(),
                  common.lineage(),
                  intro.lineage(),
                  clock.instant()));
      ImportRunCounts counts = upsertCounts(upserted);
      runService.succeed(commonLease, counts);
      runService.succeed(introLease, counts);
      return StageStatus.SUCCEEDED;
    } catch (RuntimeException failure) {
      runService.fail(commonLease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      runService.fail(introLease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      return StageStatus.FAILED;
    }
  }

  private SavedCommonSection parseCommonWithLineage(ImportRunLease lease, String contentId) {
    DetailSourceResponse response = commonSource.fetch(contentId);
    SnapshotSaveResult snapshot = saveSnapshot(lease, "detailCommon2", contentId, null, response);
    try {
      PlaceDetailCommon common = commonParser.parse(response.format(), response.payload());
      snapshotService.transition(
          new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
      return new SavedCommonSection(
          common,
          new DetailLineage(
              "detailCommon2",
              snapshot.requestFingerprint(),
              snapshot.snapshotId(),
              lease.runId()));
    } catch (RuntimeException failure) {
      snapshotService.transition(
          new SnapshotTransitionCommand(
              snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
      throw PlaceDetailImportException.invalidResponse();
    }
  }

  private SavedIntroSection parseIntroWithLineage(
      ImportRunLease lease, String contentId, String contentTypeId) {
    DetailSourceResponse response = introSource.fetch(contentId, contentTypeId);
    SnapshotSaveResult snapshot =
        saveSnapshot(lease, "detailIntro2", contentId, contentTypeId, response);
    try {
      PlaceDetailIntro intro = introParser.parse(response.format(), response.payload());
      snapshotService.transition(
          new SnapshotTransitionCommand(snapshot.snapshotId(), SnapshotStatus.PARSED, null));
      return new SavedIntroSection(
          intro,
          new DetailLineage(
              "detailIntro2", snapshot.requestFingerprint(), snapshot.snapshotId(), lease.runId()));
    } catch (RuntimeException failure) {
      snapshotService.transition(
          new SnapshotTransitionCommand(
              snapshot.snapshotId(), SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
      throw PlaceDetailImportException.invalidResponse();
    }
  }

  private StageStatus importDetailInfo(String contentId, String contentTypeId) {
    ImportRunStartResult started =
        startRun(
            "detailInfo2",
            "demo-detail-info-" + contentId + "-" + contentTypeId,
            contentId,
            detailRequestFingerprint("detailInfo2", contentId, contentTypeId));
    if (started.replayed()) return StageStatus.SKIPPED;
    ImportRunLease lease = started.lease();
    try {
      DetailItemSyncResult sync =
          detailItemImportService.importItems(
              new DetailItemImportCommand(contentId, contentTypeId, lease.runId()));
      runService.succeed(
          lease,
          countsFromItem(
              lease.runId(),
              "detailInfo2",
              sync.insertedCount(),
              sync.updatedCount(),
              sync.skippedCount(),
              sync.staledCount(),
              sync.tombstonedCount()));
      return StageStatus.SUCCEEDED;
    } catch (DetailItemImportException failure) {
      runService.fail(lease, ImportRunFailure.PARSE_REJECTED);
      return StageStatus.FAILED;
    } catch (RuntimeException failure) {
      runService.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      return StageStatus.FAILED;
    }
  }

  private StageStatus importDetailImage(String contentId, String contentTypeId) {
    ImportRunStartResult started =
        startRun(
            "detailImage2",
            "demo-detail-image-" + contentId + "-" + contentTypeId,
            contentId,
            detailRequestFingerprint("detailImage2", contentId, contentTypeId));
    if (started.replayed()) return StageStatus.SKIPPED;
    ImportRunLease lease = started.lease();
    try {
      PlaceImageSyncResult sync =
          detailImageImportService.importImages(
              new PlaceImageImportCommand(contentId, contentTypeId, lease.runId()));
      runService.succeed(
          lease,
          countsFromItem(
              lease.runId(),
              "detailImage2",
              sync.insertedCount(),
              sync.updatedCount(),
              sync.skippedCount(),
              sync.staledCount(),
              sync.tombstonedCount()));
      return StageStatus.SUCCEEDED;
    } catch (PlaceImageImportException failure) {
      runService.fail(lease, ImportRunFailure.PARSE_REJECTED);
      return StageStatus.FAILED;
    } catch (RuntimeException failure) {
      runService.fail(lease, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
      return StageStatus.FAILED;
    }
  }

  private ImportRunCounts countsFromItem(
      UUID importRunId,
      String operation,
      int inserted,
      int updated,
      int skipped,
      int staled,
      int tombstoned) {
    DemoSweepStats stats = storageReader.sweepStats(importRunId, operation);
    return new ImportRunCounts(
        stats.expectedTotal(),
        stats.pageCount(),
        inserted,
        updated,
        skipped,
        0,
        tombstoned,
        staled);
  }

  private ImportRunCounts upsertCounts(PlaceDetailUpsertResult upserted) {
    if (upserted.inserted()) {
      return new ImportRunCounts(1, 1, 1, 0, 0, 0, 0, 0);
    }
    if (upserted.updated()) {
      return new ImportRunCounts(1, 1, 0, 1, 0, 0, 0, 0);
    }
    return new ImportRunCounts(1, 1, 0, 0, 1, 0, 0, 0);
  }

  private ImportRunStartResult startRun(
      String operation, String idempotencyKey, String contentId, String fingerprint) {
    return runService.start(
        new ImportRunStartCommand(
            ImportSourceKind.TOUR_API,
            "Tour API Demo " + operation,
            new ImportRunScope(PROVIDER, SERVICE, operation, SCOPE_PREFIX + contentId),
            DATA_VERSION,
            PARSER_VERSION,
            SCHEMA_VERSION,
            ImportSyncMode.SNAPSHOT,
            fingerprint,
            idempotencyKey,
            null));
  }

  private SnapshotSaveResult saveSnapshot(
      ImportRunLease lease,
      String operation,
      String contentId,
      String contentTypeId,
      DetailSourceResponse response) {
    return snapshotService.save(
        new SnapshotSaveCommand(
            lease.runId(),
            new SnapshotScope(PROVIDER, SERVICE, operation, SCOPE_PREFIX + contentId),
            contentId,
            "1",
            200,
            "0000",
            clock.instant(),
            null,
            null,
            PARSER_VERSION,
            response.format(),
            "UTF-8",
            response.payload(),
            contentTypeId == null
                ? Map.of("endpoint", operation, "contentId", contentId)
                : Map.of(
                    "endpoint",
                    operation,
                    "contentId",
                    contentId,
                    "contentTypeId",
                    contentTypeId)));
  }

  private static String detailRequestFingerprint(
      String operation, String contentId, String contentTypeId) {
    return sha256(operation + ":" + contentId + ":" + (contentTypeId == null ? "" : contentTypeId));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  private String buildSafeHtml(DemoStorageView view) {
    String payload;
    try {
      payload = objectMapper.writeValueAsString(view);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("데모 뷰 변환에 실패했습니다.");
    }
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset=\"UTF-8\" />
          <title>Demo Storage View</title>
          <style>
            body { font-family: Arial, sans-serif; padding: 1rem; }
            h1, h2 { margin-top: 1.25rem; }
            table { border-collapse: collapse; width: 100%; margin-bottom: 1rem; }
            th, td { border: 1px solid #ddd; padding: 0.5rem; vertical-align: top; }
            th { background: #f5f5f5; text-align: left; }
            .card { border: 1px solid #ddd; border-radius: 8px; padding: 0.75rem; margin-bottom: 1rem; }
            .muted { color: #666; font-size: 0.9rem; }
            img { max-width: 160px; max-height: 120px; display: block; }
            ul { margin: 0.25rem 0 0 1.2rem; padding: 0; }
          </style>
        </head>
        <body>
          <h1>Tour API Demo Storage</h1>
          <div class="card">
            <h2>요약</h2>
            <div>최근 import_run: %s</div>
            <div class="muted">총 데이터: runs=%d, snapshots=%d, places=%d, detail_items=%d, images=%d, provenances=%d</div>
            <div class="muted">JSON 길이: %d bytes</div>
          </div>
          <section>
            <h2>데모 Run</h2>
            %s
          </section>
          <section>
            <h2>Snapshot</h2>
            %s
          </section>
          <section>
            <h2>Places (개요/이미지)</h2>
            %s
          </section>
          <section>
            <h2>Place Detail</h2>
            %s
          </section>
          <section>
            <h2>Detail Item</h2>
            %s
          </section>
          <section>
            <h2>Image Thumbnails</h2>
            %s
          </section>
          <section>
            <h2>Lineage</h2>
            %s
          </section>
        </body>
        </html>
        """
        .formatted(
            safe(view.runs().isEmpty() ? "없음" : view.runs().getFirst().id().toString()),
            view.runs().size(),
            view.snapshots().size(),
            view.places().size(),
            view.detailItems().size(),
            view.placeImages().size(),
            view.provenances().size(),
            payload.length(),
            buildRunsTable(view),
            buildSnapshotTable(view),
            buildPlacesTable(view),
            buildPlaceDetailsTable(view),
            buildDetailItemsTable(view),
            buildImageCards(view),
            buildProvenanceTable(view));
  }

  private static String buildRunsTable(DemoStorageView view) {
    if (view.runs().isEmpty()) {
      return "<p>실행 이력 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>run</th><th>operation</th><th>status</th><th>fetched</th><th>inserted</th><th>started</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.runs(),
                row ->
                    """
                <tr>
                  <td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%s</td>
                </tr>
                """
                        .formatted(
                            safe(row.id()),
                            safe(row.sourceOperation()),
                            safe(row.status()),
                            row.fetchedCount(),
                            row.insertedCount(),
                            safe(row.startedAt()))));
  }

  private static String buildSnapshotTable(DemoStorageView view) {
    if (view.snapshots().isEmpty()) {
      return "<p>스냅샷 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>snapshot</th><th>run</th><th>operation</th><th>parseStatus</th><th>size</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.snapshots(),
                row ->
                    """
                <tr>
                  <td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%d</td>
                </tr>
                """
                        .formatted(
                            safe(row.id()),
                            safe(row.importRunId()),
                            safe(row.operation()),
                            safe(row.parseStatus()),
                            row.payloadSizeBytes())));
  }

  private static String buildPlacesTable(DemoStorageView view) {
    if (view.places().isEmpty()) {
      return "<p>places 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>place</th><th>contentId</th><th>type</th><th>category</th><th>address</th><th>overview</th><th>image</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.places(),
                place ->
                    """
                <tr>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                </tr>
                """
                        .formatted(
                            safe(place.id()),
                            safe(place.contentId()),
                            safe(place.contentTypeId()),
                            safe(place.category()),
                            safe(place.address()),
                            safe(place.overview()),
                            buildImageThumb(place.imageUrl(), place.thumbnailUrl(), null))));
  }

  private static String buildPlaceDetailsTable(DemoStorageView view) {
    if (view.placeDetails().isEmpty()) {
      return "<p>place_details 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>place</th><th>phone</th><th>openHours</th><th>closed</th><th>parking</th><th>intro</th><th>snapshot</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.placeDetails(),
                detail ->
                    """
                <tr>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td><pre>%s</pre></td>
                  <td>%s</td>
                </tr>
                """
                        .formatted(
                            safe(detail.placeId()),
                            safe(detail.phone()),
                            safe(detail.operatingHoursText()),
                            safe(detail.closedDaysText()),
                            safe(detail.parkingText()),
                            safeJson(detail.introAttributes()),
                            safe(detail.sourceSnapshotId()))));
  }

  private static String buildDetailItemsTable(DemoStorageView view) {
    if (view.detailItems().isEmpty()) {
      return "<p>place_detail_items 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>place</th><th>type</th><th>itemType</th><th>sourceItemKey</th><th>seq</th><th>title</th><th>run</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.detailItems(),
                item ->
                    """
                <tr>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%d</td>
                  <td>%s</td>
                  <td>%s</td>
                </tr>
                """
                        .formatted(
                            safe(item.placeId()),
                            safe(item.contentTypeId()),
                            safe(item.itemType()),
                            safe(item.sourceItemKey()),
                            item.sequenceNo(),
                            safe(item.title()),
                            safe(item.importRunId()))));
  }

  private static String buildImageCards(DemoStorageView view) {
    if (view.placeImages().isEmpty()) {
      return "<p>place_images 없음</p>";
    }
    return """
        <ul>%s</ul>
        """
        .formatted(
            buildRows(
                view.placeImages(),
                image ->
                    """
                <li><div>%s</div><div class=\"muted\">%s</div>%s</li>
                """
                        .formatted(
                            safe(image.sourceImageId()),
                            safe(image.importRunId()),
                            buildImageThumb(
                                image.imageUrl(), image.thumbnailUrl(), image.sourceImageId()))));
  }

  private static String buildProvenanceTable(DemoStorageView view) {
    if (view.provenances().isEmpty()) {
      return "<p>provenance 없음</p>";
    }
    return """
        <table>
          <thead>
            <tr><th>row</th><th>entity</th><th>operation</th><th>contentType</th><th>fingerprint</th><th>snapshot</th></tr>
          </thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(
            buildRows(
                view.provenances(),
                provenance ->
                    """
                <tr>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                </tr>
                """
                        .formatted(
                            safe(provenance.normalizedRowId()),
                            safe(provenance.normalizedEntityType()),
                            safe(provenance.operationKey()),
                            safe(provenance.contentTypeId()),
                            safe(provenance.requestFingerprint()),
                            safe(provenance.sourceSnapshotId()))));
  }

  private static <T> String buildRows(
      Iterable<T> rows, java.util.function.Function<T, String> mapper) {
    StringBuilder rendered = new StringBuilder();
    for (T row : rows) {
      rendered.append(mapper.apply(row));
    }
    return rendered.toString();
  }

  private static String buildImageThumb(String imageUrl, String thumbnailUrl, String alt) {
    String safeImage = safeAbsoluteImageUrl(imageUrl);
    String safeThumb = safeAbsoluteImageUrl(thumbnailUrl);
    String resolved = !safeImage.isEmpty() ? safeImage : safeThumb;
    if (resolved.isEmpty()) {
      return "<span class=\"muted\">썸네일 없음</span>";
    }
    return """
        <img src=\"%s\" alt=\"%s\" />
        """
        .formatted(resolved, safe(alt));
  }

  private static String safeAbsoluteImageUrl(String url) {
    if (url == null) {
      return "";
    }
    try {
      URI uri = URI.create(url);
      if (!uri.isAbsolute()) {
        return "";
      }
      String scheme = uri.getScheme();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
        return "";
      }
      return safe(uri.toString());
    } catch (RuntimeException failure) {
      return "";
    }
  }

  private static String safe(Object value) {
    return HtmlUtils.htmlEscape(value == null ? "" : String.valueOf(value));
  }

  private static String safeJson(String value) {
    return safe(value == null ? "{}" : value);
  }

  private record SavedCommonSection(PlaceDetailCommon detail, DetailLineage lineage) {}

  private record SavedIntroSection(PlaceDetailIntro detail, DetailLineage lineage) {}
}
