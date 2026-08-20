package com.timingjeju.api.global.tourapi.discovery;

import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryCommitCommand;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryCommitter;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertResult;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalDiscoveryImportCommitter implements DiscoveryCommitter {

  private final PlaceListRepository repository;
  private final ImportRunLifecycleService runService;
  private final ImportCheckpointService checkpointService;

  public TransactionalDiscoveryImportCommitter(
      PlaceListRepository repository,
      ImportRunLifecycleService runService,
      ImportCheckpointService checkpointService) {
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.runService = Objects.requireNonNull(runService, "runService는 필수입니다.");
    this.checkpointService = Objects.requireNonNull(checkpointService, "checkpointService는 필수입니다.");
  }

  @Override
  @Transactional
  public PlaceListUpsertResult commit(DiscoveryCommitCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    var current =
        checkpointService
            .find(command.scope())
            .orElseThrow(DiscoveryImportException::storageFailure);
    if (current.sourceWatermarkAt() != null) {
      int watermarkOrder = command.sourceWatermarkAt().compareTo(current.sourceWatermarkAt());
      String currentManifest = Objects.toString(current.checkpoint().get("manifest"), "");
      if (watermarkOrder < 0
          || (watermarkOrder == 0 && !currentManifest.equals(command.manifestHash()))) {
        throw DiscoveryImportException.invalidResponse();
      }
    }

    PlaceListUpsertResult stored =
        command.writes().isEmpty()
            ? new PlaceListUpsertResult(0, 0, 0)
            : repository.upsert(new PlaceListUpsertCommand(command.writes()));
    var finalCounts =
        new com.timingjeju.api.application.importing.ImportRunCounts(
            command.counts().rowCount(),
            command.counts().fetchedCount(),
            stored.inserted(),
            stored.updated(),
            stored.skipped(),
            command.counts().rejectedCount(),
            command.counts().deletedCount(),
            command.counts().staledCount());
    if (finalCounts.rejectedCount() == 0) {
      runService.succeed(command.lease(), finalCounts);
      checkpointService.advance(
          new ImportCheckpointAdvanceCommand(
              command.scope(),
              command.expectedCheckpointVersion(),
              Map.of("manifest", command.manifestHash(), "pageCount", command.pageCount()),
              command.sourceWatermarkAt(),
              command.lease().runId(),
              ImportRunStatus.SUCCEEDED));
    } else {
      runService.completePartial(
          command.lease(),
          finalCounts,
          com.timingjeju.api.application.importing.ImportRunFailure.PARSE_REJECTED);
    }
    return stored;
  }
}
