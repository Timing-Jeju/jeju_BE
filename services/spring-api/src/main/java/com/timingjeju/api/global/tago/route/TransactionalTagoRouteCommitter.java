package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.tago.route.TagoRouteCommitCommand;
import com.timingjeju.api.application.tago.route.TagoRouteCommitResult;
import com.timingjeju.api.application.tago.route.TagoRouteImportCommitter;
import com.timingjeju.api.application.tago.route.TagoRouteLineage;
import com.timingjeju.api.application.tago.route.TagoRouteRepository;
import com.timingjeju.api.application.tago.route.TagoRouteWriteResult;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalTagoRouteCommitter implements TagoRouteImportCommitter {
  private final TagoRouteRepository repository;
  private final ImportRunLifecycleService runs;
  private final ImportCheckpointService checkpoints;

  public TransactionalTagoRouteCommitter(
      TagoRouteRepository repository,
      ImportRunLifecycleService runs,
      ImportCheckpointService checkpoints) {
    this.repository = repository;
    this.runs = runs;
    this.checkpoints = checkpoints;
  }

  @Override
  @Transactional
  public TagoRouteCommitResult commit(TagoRouteCommitCommand command) {
    Instant observed =
        command.lineage().stream()
            .map(TagoRouteLineage::fetchedAt)
            .max(Instant::compareTo)
            .orElseThrow();
    TagoRouteWriteResult writes =
        repository.apply(command.routes(), command.routeStops(), command.lease().runId(), observed);
    ImportRunCounts counts =
        new ImportRunCounts(
            command.routeStops().size(),
            command.lineage().size(),
            writes.inserted(),
            writes.updated(),
            writes.skipped(),
            0,
            writes.deleted(),
            0);
    runs.succeed(command.lease(), counts);
    ImportCheckpoint checkpoint =
        checkpoints.advance(
            new ImportCheckpointAdvanceCommand(
                TagoRouteImportSessionAdapter.SCOPE,
                command.expectedCheckpointVersion(),
                Map.of(
                    "routeCount",
                    command.routes().size(),
                    "routeStopCount",
                    command.routeStops().size()),
                observed,
                command.lease().runId(),
                ImportRunStatus.SUCCEEDED));
    return new TagoRouteCommitResult(counts, checkpoint.version());
  }
}
