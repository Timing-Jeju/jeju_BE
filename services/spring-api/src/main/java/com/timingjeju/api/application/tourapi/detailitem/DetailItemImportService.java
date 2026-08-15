package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Clock;
import java.util.Objects;

public final class DetailItemImportService {
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
    DetailSourceResponse response = source.fetch(command.contentId(), command.contentTypeId());
    DetailItemBatch batch =
        parser.parse(
            response.format(), response.payload(), command.contentId(), command.contentTypeId());
    if (!command.contentId().equals(batch.contentId())
        || !command.contentTypeId().equals(batch.contentTypeId())) {
      throw DetailItemImportException.invalidResponse();
    }
    return repository.sync(
        new DetailItemSyncCommand(
            command.contentId(),
            command.contentTypeId(),
            batch,
            command.lineage(),
            clock.instant()));
  }
}
