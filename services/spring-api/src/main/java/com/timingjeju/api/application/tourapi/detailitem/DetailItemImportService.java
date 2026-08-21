package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DetailItemImportService {
  private static final int MAX_PAGES = 10_000;
  private final DetailInfoSource source;
  private final DetailInfoSnapshotGateway snapshots;
  private final DetailInfoParser parser;
  private final DetailItemRepository repository;
  private final Clock clock;

  public DetailItemImportService(
      DetailInfoSource source,
      DetailInfoSnapshotGateway snapshots,
      DetailInfoParser parser,
      DetailItemRepository repository,
      Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.snapshots = Objects.requireNonNull(snapshots);
    this.parser = Objects.requireNonNull(parser);
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
  }

  public DetailItemSyncResult importItems(DetailItemImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      List<DetailItemWrite> writes = new ArrayList<>();
      List<DetailItemPageLineage> pageLineages = new ArrayList<>();
      int expectedTotal = -1;
      int fetched = 0;
      int pageNo = 1;
      while (pageNo <= MAX_PAGES) {
        DetailSourceResponse response =
            source.fetch(command.contentId(), command.contentTypeId(), pageNo);
        SavedDetailInfoPage saved =
            snapshots.save(
                command.importRunId(),
                command.contentId(),
                command.contentTypeId(),
                pageNo,
                response);
        if (saved.status() == SnapshotStatus.REJECTED) {
          throw DetailItemImportException.invalidResponse();
        }
        if (saved.status() != SnapshotStatus.RECEIVED
            && !(saved.replayed() && saved.status() == SnapshotStatus.PARSED)) {
          throw DetailItemImportException.invalidResponse();
        }
        DetailItemPage page;
        try {
          page =
              parser.parse(
                  saved.storedResponse().format(),
                  saved.storedResponse().payload(),
                  command.contentId(),
                  command.contentTypeId());
          expectedTotal = validatePage(command, page, pageNo, expectedTotal, fetched);
        } catch (RuntimeException failure) {
          snapshots.markRejected(saved);
          throw failure;
        }
        snapshots.markParsed(saved);
        DetailItemPageLineage pageLineage =
            new DetailItemPageLineage(
                pageNo,
                page.rawItemCount(),
                saved.payloadHash(),
                saved.fetchedAt(),
                saved.lineage());
        pageLineages.add(pageLineage);
        for (DetailItem item : page.items()) {
          writes.add(
              new DetailItemWrite(
                  new DetailItem(
                      item.itemType(),
                      item.sourceItemKey(),
                      item.title(),
                      writes.size() + 1,
                      item.attributes()),
                  pageLineage));
        }
        fetched += page.rawItemCount();
        if (fetched == expectedTotal) {
          DetailItemBatch batch =
              new DetailItemBatch(command.contentId(), command.contentTypeId(), writes);
          DetailItemSweep sweep =
              new DetailItemSweep(command.importRunId(), expectedTotal, pageLineages);
          return repository.sync(
              new DetailItemSyncCommand(
                  command.contentId(), command.contentTypeId(), batch, sweep, clock.instant()));
        }
        pageNo++;
      }
      throw DetailItemImportException.invalidResponse();
    } catch (DetailItemImportException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw DetailItemImportException.invalidResponse();
    }
  }

  private static int validatePage(
      DetailItemImportCommand command,
      DetailItemPage page,
      int requestedPage,
      int expectedTotal,
      int fetched) {
    if (!command.contentId().equals(page.contentId())
        || !command.contentTypeId().equals(page.contentTypeId())
        || page.pageNo() != requestedPage) {
      throw DetailItemImportException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    int remaining = total - fetched;
    if (remaining <= 0) {
      throw DetailItemImportException.invalidResponse();
    }
    int expectedRows = Math.min(DetailInfoRequestContract.PAGE_SIZE, remaining);
    if (page.numOfRows() != expectedRows || page.rawItemCount() != expectedRows) {
      throw DetailItemImportException.invalidResponse();
    }
    if (page.totalCount() != total || fetched + page.rawItemCount() > total) {
      throw DetailItemImportException.invalidResponse();
    }
    if (remaining > 0 && page.rawItemCount() == 0) {
      throw DetailItemImportException.invalidResponse();
    }
    return total;
  }
}
