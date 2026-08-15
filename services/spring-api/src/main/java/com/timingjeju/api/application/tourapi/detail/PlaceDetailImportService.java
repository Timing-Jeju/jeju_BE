package com.timingjeju.api.application.tourapi.detail;

import java.time.Clock;
import java.util.Objects;

public final class PlaceDetailImportService {
  private final DetailCommonSource commonSource;
  private final DetailIntroSource introSource;
  private final DetailCommonParser commonParser;
  private final DetailIntroParser introParser;
  private final PlaceDetailRepository repository;
  private final Clock clock;

  public PlaceDetailImportService(
      DetailCommonSource commonSource,
      DetailIntroSource introSource,
      DetailCommonParser commonParser,
      DetailIntroParser introParser,
      PlaceDetailRepository repository,
      Clock clock) {
    this.commonSource = Objects.requireNonNull(commonSource);
    this.introSource = Objects.requireNonNull(introSource);
    this.commonParser = Objects.requireNonNull(commonParser);
    this.introParser = Objects.requireNonNull(introParser);
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
  }

  public PlaceDetailUpsertResult importDetail(PlaceDetailImportCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    DetailSourceResponse commonResponse = commonSource.fetch(command.contentId());
    PlaceDetailCommon common =
        commonParser.parse(commonResponse.format(), commonResponse.payload());
    DetailSourceResponse introResponse =
        introSource.fetch(command.contentId(), command.contentTypeId());
    PlaceDetailIntro intro = introParser.parse(introResponse.format(), introResponse.payload());
    if (!command.contentId().equals(common.contentId())
        || !command.contentId().equals(intro.contentId())
        || !command.contentTypeId().equals(common.contentTypeId())
        || !command.contentTypeId().equals(intro.contentTypeId())) {
      throw PlaceDetailImportException.invalidResponse();
    }
    return repository.upsert(
        new PlaceDetailUpsertCommand(
            command.contentId(),
            common,
            intro,
            command.commonLineage(),
            command.introLineage(),
            clock.instant()));
  }
}
