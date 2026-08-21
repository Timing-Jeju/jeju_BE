package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TagoRouteImportService {
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 10_000;
  private static final String CITY = "39";
  private static final String PROVIDER = "TAGO";
  private static final String STOP_SERVICE = "BusSttnInfoInqireService";
  private final TagoRouteSource source;
  private final TagoRoutePayloadParser parser;
  private final TagoRouteImportSession session;
  private final TagoRouteSnapshotGateway snapshots;
  private final TagoRouteStopCatalog stops;
  private final TagoRouteImportCommitter committer;

  public TagoRouteImportService(
      TagoRouteSource source,
      TagoRoutePayloadParser parser,
      TagoRouteImportSession session,
      TagoRouteSnapshotGateway snapshots,
      TagoRouteStopCatalog stops,
      TagoRouteImportCommitter committer) {
    this.source = Objects.requireNonNull(source);
    this.parser = Objects.requireNonNull(parser);
    this.session = Objects.requireNonNull(session);
    this.snapshots = Objects.requireNonNull(snapshots);
    this.stops = Objects.requireNonNull(stops);
    this.committer = Objects.requireNonNull(committer);
  }

  public TagoRouteImportResult sync(TagoRouteImportCommand command) {
    StartedTagoRouteImport started = session.start(Objects.requireNonNull(command));
    if (started.replayed())
      return new TagoRouteImportResult(
          started.lease().runId(),
          started.routeCount(),
          started.routeStopCount(),
          started.counts().fetchedCount(),
          started.counts(),
          started.checkpointVersion(),
          true);
    try {
      List<TagoRouteLineage> lineage = new ArrayList<>();
      List<TagoRoute> summaries = new ArrayList<>();
      Set<String> routeIds = new HashSet<>();
      for (String routeNo : command.routeNumbers()) {
        int fetched = 0;
        int expectedTotal = -1;
        boolean complete = false;
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
          int currentPage = pageNo;
          SavedTagoRoutePayload saved =
              save(
                  "route-list",
                  routeNo,
                  pageNo,
                  source.fetchRouteList(CITY, routeNo, pageNo),
                  started);
          TagoRoutePage page =
              parse(
                  saved,
                  () ->
                      parser.parseRouteList(
                          saved.storedResponse().format(),
                          saved.storedResponse().payload(),
                          CITY,
                          routeNo,
                          currentPage));
          lineage.add(lineage(saved, routeNo, page.routes().size()));
          expectedTotal =
              validatePage(
                  page.pageNo(),
                  page.numOfRows(),
                  page.totalCount(),
                  page.routes().size(),
                  pageNo,
                  fetched,
                  expectedTotal);
          for (TagoRoute route : page.routes()) {
            if (!routeNo.equals(route.routeNo()) || !routeIds.add(route.externalRouteId()))
              throw TagoRouteImportException.invalidResponse();
            summaries.add(route);
          }
          fetched += page.routes().size();
          if (fetched == page.totalCount()) {
            complete = true;
            break;
          }
        }
        if (!complete) throw TagoRouteImportException.invalidResponse();
      }
      List<TagoRouteWrite> routeWrites = new ArrayList<>();
      List<TagoRouteStopWrite> stopWrites = new ArrayList<>();
      Set<String> allNodeIds = new HashSet<>();
      for (TagoRoute summary : summaries) {
        SavedTagoRoutePayload detailSaved =
            save(
                "route-detail",
                summary.externalRouteId(),
                0,
                source.fetchRouteDetail(CITY, summary.externalRouteId()),
                started);
        TagoRoute detail =
            parse(
                detailSaved,
                () ->
                    parser.parseRouteDetail(
                        detailSaved.storedResponse().format(),
                        detailSaved.storedResponse().payload(),
                        CITY,
                        summary.externalRouteId()));
        lineage.add(lineage(detailSaved, detail.externalRouteId(), 1));
        if (!summary.routeNo().equals(detail.routeNo()))
          throw TagoRouteImportException.invalidResponse();
        routeWrites.add(
            new TagoRouteWrite(
                detail,
                detailSaved.snapshotId(),
                started.lease().runId(),
                detailSaved.fetchedAt()));
        int fetched = 0;
        int expectedTotal = -1;
        boolean complete = false;
        Set<Integer> sequences = new HashSet<>();
        Set<String> routeNodes = new HashSet<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
          int currentPage = pageNo;
          SavedTagoRoutePayload stopSaved =
              save(
                  "route-stops",
                  detail.externalRouteId(),
                  pageNo,
                  source.fetchRouteStops(CITY, detail.externalRouteId(), pageNo),
                  started);
          TagoRouteStopPage page =
              parse(
                  stopSaved,
                  () ->
                      parser.parseRouteStops(
                          stopSaved.storedResponse().format(),
                          stopSaved.storedResponse().payload(),
                          CITY,
                          detail.externalRouteId(),
                          currentPage));
          lineage.add(lineage(stopSaved, detail.externalRouteId(), page.stops().size()));
          expectedTotal =
              validatePage(
                  page.pageNo(),
                  page.numOfRows(),
                  page.totalCount(),
                  page.stops().size(),
                  pageNo,
                  fetched,
                  expectedTotal);
          for (TagoRouteStop stop : page.stops()) {
            if (stop.stopSequence() != fetched + 1
                || !sequences.add(stop.stopSequence())
                || !routeNodes.add(stop.nodeId())) throw TagoRouteImportException.invalidResponse();
            fetched++;
            allNodeIds.add(stop.nodeId());
            stopWrites.add(
                new TagoRouteStopWrite(
                    stop,
                    detail.directionKey(),
                    stopSaved.snapshotId(),
                    started.lease().runId(),
                    stopSaved.fetchedAt()));
          }
          if (fetched == page.totalCount()) {
            complete = true;
            break;
          }
        }
        if (!complete) throw TagoRouteImportException.invalidResponse();
      }
      stops.requireExisting(PROVIDER, STOP_SERVICE, CITY, Set.copyOf(allNodeIds));
      TagoRouteCommitResult committed =
          committer.commit(
              new TagoRouteCommitCommand(
                  started.lease(), started.checkpointVersion(), routeWrites, stopWrites, lineage));
      return new TagoRouteImportResult(
          started.lease().runId(),
          routeWrites.size(),
          stopWrites.size(),
          lineage.size(),
          committed.counts(),
          committed.checkpointVersion(),
          false);
    } catch (RuntimeException failure) {
      session.fail(started.lease());
      if (failure instanceof TagoRouteImportException tago) throw tago;
      throw TagoRouteImportException.invalidResponse();
    }
  }

  private SavedTagoRoutePayload save(
      String kind,
      String route,
      int page,
      TagoRouteSourceResponse response,
      StartedTagoRouteImport started) {
    SavedTagoRoutePayload saved =
        snapshots.save(started.lease().runId(), kind, CITY, route, page, response);
    if (saved.status() != SnapshotStatus.RECEIVED
        && !(saved.replayed() && saved.status() == SnapshotStatus.PARSED))
      throw TagoRouteImportException.invalidResponse();
    return saved;
  }

  private <T> T parse(SavedTagoRoutePayload saved, java.util.function.Supplier<T> parserCall) {
    try {
      T parsed = parserCall.get();
      snapshots.markParsed(saved);
      return parsed;
    } catch (RuntimeException failure) {
      snapshots.markRejected(saved);
      throw failure;
    }
  }

  private static int validatePage(
      int actualPage,
      int rows,
      int total,
      int received,
      int requested,
      int fetched,
      int expectedTotal) {
    int stableTotal = expectedTotal < 0 ? total : expectedTotal;
    if (actualPage != requested
        || rows != PAGE_SIZE
        || total < 1
        || total != stableTotal
        || fetched + received > total
        || (fetched + received < total && received != PAGE_SIZE))
      throw TagoRouteImportException.invalidResponse();
    return stableTotal;
  }

  private static TagoRouteLineage lineage(SavedTagoRoutePayload saved, String routeId, int count) {
    return new TagoRouteLineage(
        saved.kind(),
        routeId,
        saved.pageNo(),
        count,
        saved.snapshotId(),
        saved.payloadHash(),
        saved.fetchedAt());
  }
}
