package com.timingjeju.api.application.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlaceImageImportService {
  private static final int MAX_PAGES = 10_000;
  private final DetailImageSource source;
  private final DetailImageSnapshotGateway snapshots;
  private final DetailImageParser parser;
  private final PlaceImageRepository repository;
  private final Clock clock;

  public PlaceImageImportService(
      DetailImageSource source,
      DetailImageSnapshotGateway snapshots,
      DetailImageParser parser,
      PlaceImageRepository repository,
      Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.snapshots = Objects.requireNonNull(snapshots);
    this.parser = Objects.requireNonNull(parser);
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
  }

  public PlaceImageSyncResult importImages(PlaceImageImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      List<PlaceImageWrite> writes = new ArrayList<>();
      List<PlaceImagePageLineage> pageLineages = new ArrayList<>();
      int expectedTotal = -1;
      int fetched = 0;
      int pageNo = 1;
      while (pageNo <= MAX_PAGES) {
        DetailSourceResponse response = source.fetch(command.contentId(), pageNo);
        SavedDetailImagePage saved =
            snapshots.save(command.importRunId(), command.contentId(), pageNo, response);
        if (saved.status() == SnapshotStatus.REJECTED
            || (saved.status() != SnapshotStatus.RECEIVED
                && !(saved.replayed() && saved.status() == SnapshotStatus.PARSED))) {
          throw PlaceImageImportException.invalidResponse();
        }
        PlaceImagePage page;
        try {
          page =
              parser.parse(
                  saved.storedResponse().format(),
                  saved.storedResponse().payload(),
                  command.contentId());
          expectedTotal = validatePage(command, page, pageNo, expectedTotal, fetched);
        } catch (RuntimeException failure) {
          snapshots.markRejected(saved);
          throw failure;
        }
        snapshots.markParsed(saved);
        PlaceImagePageLineage pageLineage =
            new PlaceImagePageLineage(
                pageNo,
                page.rawItemCount(),
                saved.payloadHash(),
                saved.fetchedAt(),
                saved.lineage());
        pageLineages.add(pageLineage);
        for (PlaceImage image : page.images()) {
          PlaceImage ordered =
              new PlaceImage(
                  image.sourceImageId(),
                  image.imageUrl(),
                  image.thumbnailUrl(),
                  image.imageName(),
                  image.copyrightCode(),
                  image.copyrightOwner(),
                  image.licenseText(),
                  writes.size() + 1);
          writes.add(new PlaceImageWrite(ordered, pageLineage));
        }
        fetched += page.rawItemCount();
        if (fetched == expectedTotal) {
          return repository.sync(
              new PlaceImageSyncCommand(
                  command.contentId(),
                  command.contentTypeId(),
                  new PlaceImageBatch(command.contentId(), command.contentTypeId(), writes),
                  new PlaceImageSweep(command.importRunId(), expectedTotal, pageLineages),
                  clock.instant()));
        }
        pageNo++;
      }
      throw PlaceImageImportException.invalidResponse();
    } catch (PlaceImageImportException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw PlaceImageImportException.invalidResponse();
    }
  }

  private static int validatePage(
      PlaceImageImportCommand command,
      PlaceImagePage page,
      int requestedPage,
      int expectedTotal,
      int fetched) {
    if (!command.contentId().equals(page.contentId())
        || page.pageNo() != requestedPage
        || page.numOfRows() != DetailImageRequestContract.PAGE_SIZE) {
      throw PlaceImageImportException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    if (page.totalCount() != total || fetched + page.rawItemCount() > total) {
      throw PlaceImageImportException.invalidResponse();
    }
    int remaining = total - fetched;
    if (remaining > 0 && page.rawItemCount() == 0) {
      throw PlaceImageImportException.invalidResponse();
    }
    if (remaining > DetailImageRequestContract.PAGE_SIZE
        && page.rawItemCount() != DetailImageRequestContract.PAGE_SIZE) {
      throw PlaceImageImportException.invalidResponse();
    }
    return total;
  }
}
