package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DetailItemImportService {
  private static final int MAX_PAGES = 10_000;
  private final DetailInfoSource source;
  private final DetailInfoParser parser;
  private final DetailItemRepository repository;
  private final Clock clock;

  public DetailItemImportService(
      DetailInfoSource source,
      DetailInfoParser parser,
      DetailItemRepository repository,
      Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.parser = Objects.requireNonNull(parser);
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
  }

  public DetailItemSyncResult importItems(DetailItemImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      List<DetailItem> items = new ArrayList<>();
      int expectedTotal = -1;
      int fetched = 0;
      int pageNo = 1;
      while (pageNo <= MAX_PAGES) {
        DetailSourceResponse response =
            source.fetch(command.contentId(), command.contentTypeId(), pageNo);
        DetailItemPage page =
            parser.parse(
                response.format(),
                response.payload(),
                command.contentId(),
                command.contentTypeId());
        expectedTotal = validatePage(command, page, pageNo, expectedTotal, fetched);
        for (DetailItem item : page.items()) {
          items.add(
              new DetailItem(
                  item.itemType(),
                  item.sourceItemKey(),
                  item.title(),
                  items.size() + 1,
                  item.attributes()));
        }
        fetched += page.rawItemCount();
        if (fetched == expectedTotal) {
          DetailItemBatch batch =
              new DetailItemBatch(command.contentId(), command.contentTypeId(), items);
          return repository.sync(
              new DetailItemSyncCommand(
                  command.contentId(),
                  command.contentTypeId(),
                  batch,
                  command.lineage(),
                  clock.instant()));
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
        || page.pageNo() != requestedPage
        || page.numOfRows() != DetailInfoRequestContract.PAGE_SIZE) {
      throw DetailItemImportException.invalidResponse();
    }
    int total = expectedTotal < 0 ? page.totalCount() : expectedTotal;
    if (page.totalCount() != total || fetched + page.rawItemCount() > total) {
      throw DetailItemImportException.invalidResponse();
    }
    int remaining = total - fetched;
    if (remaining > 0 && page.rawItemCount() == 0) {
      throw DetailItemImportException.invalidResponse();
    }
    if (remaining > DetailInfoRequestContract.PAGE_SIZE
        && page.rawItemCount() != DetailInfoRequestContract.PAGE_SIZE) {
      throw DetailItemImportException.invalidResponse();
    }
    return total;
  }
}
