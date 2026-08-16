package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TagoStopImportService {
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 10_000;
  private final TagoStopSource source;
  private final TagoStopPayloadParser parser;
  private final TagoStopImportSession session;
  private final TagoStopSnapshotGateway snapshots;
  private final TagoStopImportCommitter committer;

  public TagoStopImportService(
      TagoStopSource source,
      TagoStopPayloadParser parser,
      TagoStopImportSession session,
      TagoStopSnapshotGateway snapshots,
      TagoStopImportCommitter committer) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.session = Objects.requireNonNull(session, "session은 필수입니다.");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
  }

  public TagoStopImportResult sync(TagoStopImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    StartedTagoStopImport started = session.start(command);
    if (started.replayed()) {
      if (started.cityCode() == null) throw TagoStopImportException.invalidResponse();
      return new TagoStopImportResult(
          started.lease().runId(),
          started.cityCode(),
          started.counts().rowCount(),
          started.counts().fetchedCount(),
          started.counts(),
          started.checkpointVersion(),
          true);
    }

    try {
      List<TagoStopPageLineage> lineage = new ArrayList<>();
      TagoStopSourceResponse cityResponse = source.fetchCityCodes();
      SavedTagoStopPage savedCity = snapshots.saveCity(started.lease().runId(), cityResponse);
      requireParsable(savedCity);
      List<TagoCityCode> cities;
      String cityCode;
      try {
        cities =
            parser.parseCityCodes(
                savedCity.storedResponse().format(), savedCity.storedResponse().payload());
        cityCode =
            parser.discoverJejuCityCode(
                savedCity.storedResponse().format(), savedCity.storedResponse().payload());
      } catch (RuntimeException failure) {
        snapshots.markRejected(savedCity);
        throw failure;
      }
      snapshots.markParsed(savedCity);
      TagoCityCode jeju =
          cities.stream()
              .filter(city -> city.code().equals(cityCode))
              .findFirst()
              .orElseThrow(TagoStopImportException::invalidResponse);
      lineage.add(lineage("city", 0, cities.size(), savedCity));

      List<TagoStopWrite> stationWrites = new ArrayList<>();
      Set<String> naturalKeys = new HashSet<>();
      int expectedTotal = -1;
      int pageNo = 1;
      while (pageNo <= MAX_PAGES) {
        TagoStopSourceResponse response = source.fetchStations(cityCode, pageNo);
        SavedTagoStopPage saved =
            snapshots.saveStations(started.lease().runId(), cityCode, pageNo, response);
        requireParsable(saved);
        TagoStationPage page;
        try {
          page =
              parser.parseStations(
                  saved.storedResponse().format(),
                  saved.storedResponse().payload(),
                  cityCode,
                  pageNo);
          expectedTotal = validatePage(page, pageNo, expectedTotal, stationWrites.size());
          for (TagoStation station : page.stations()) {
            if (!naturalKeys.add(station.cityCode() + '\u0000' + station.nodeId())) {
              throw TagoStopImportException.invalidResponse();
            }
          }
        } catch (RuntimeException failure) {
          snapshots.markRejected(saved);
          throw failure;
        }
        snapshots.markParsed(saved);
        for (TagoStation station : page.stations()) {
          stationWrites.add(
              new TagoStopWrite(
                  station, saved.snapshotId(), started.lease().runId(), saved.fetchedAt()));
        }
        lineage.add(lineage("station", pageNo, page.stations().size(), saved));
        if (stationWrites.size() == expectedTotal) {
          TagoStopCommitResult committed =
              committer.commit(
                  new TagoStopCommitCommand(
                      started.lease(), started.checkpointVersion(), jeju, stationWrites, lineage));
          return new TagoStopImportResult(
              started.lease().runId(),
              cityCode,
              stationWrites.size(),
              lineage.size(),
              committed.counts(),
              committed.checkpointVersion(),
              false);
        }
        pageNo++;
      }
      throw TagoStopImportException.invalidResponse();
    } catch (RuntimeException failure) {
      session.fail(started.lease());
      if (failure instanceof TagoStopImportException tagoFailure) throw tagoFailure;
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static int validatePage(
      TagoStationPage page, int requestedPage, int expectedTotal, int fetched) {
    if (page.pageNo() != requestedPage || page.numOfRows() != PAGE_SIZE) {
      throw TagoStopImportException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    int received = page.stations().size();
    if (page.totalCount() != total
        || fetched + received > total
        || (fetched + received < total && received != PAGE_SIZE)) {
      throw TagoStopImportException.invalidResponse();
    }
    return total;
  }

  private static void requireParsable(SavedTagoStopPage saved) {
    if (saved.status() == SnapshotStatus.RECEIVED) return;
    if (saved.replayed() && saved.status() == SnapshotStatus.PARSED) return;
    throw TagoStopImportException.invalidResponse();
  }

  private static TagoStopPageLineage lineage(
      String kind, int pageNo, int rawItemCount, SavedTagoStopPage saved) {
    return new TagoStopPageLineage(
        kind, pageNo, rawItemCount, saved.snapshotId(), saved.payloadHash(), saved.fetchedAt());
  }
}
