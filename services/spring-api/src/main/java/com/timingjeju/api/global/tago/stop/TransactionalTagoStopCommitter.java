package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.tago.stop.TagoStopCommitCommand;
import com.timingjeju.api.application.tago.stop.TagoStopCommitResult;
import com.timingjeju.api.application.tago.stop.TagoStopImportCommitter;
import com.timingjeju.api.application.tago.stop.TagoStopPageLineage;
import com.timingjeju.api.application.tago.stop.TagoStopRepository;
import com.timingjeju.api.application.tago.stop.TagoStopWriteResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalTagoStopCommitter implements TagoStopImportCommitter {
  private final TagoStopRepository repository;
  private final ImportRunLifecycleService runs;
  private final ImportCheckpointService checkpoints;

  public TransactionalTagoStopCommitter(
      TagoStopRepository repository,
      ImportRunLifecycleService runs,
      ImportCheckpointService checkpoints) {
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints는 필수입니다.");
  }

  @Override
  @Transactional
  public TagoStopCommitResult commit(TagoStopCommitCommand command) {
    TagoStopPageLineage cityLineage =
        command.pages().stream()
            .filter(page -> page.kind().equals("city"))
            .findFirst()
            .orElseThrow();
    TagoStopPageLineage stationSweepLineage =
        command.pages().stream()
            .filter(page -> page.kind().equals("station"))
            .max(java.util.Comparator.comparingInt(TagoStopPageLineage::pageNo))
            .orElseThrow();
    Instant observedAt =
        command.pages().stream()
            .map(TagoStopPageLineage::fetchedAt)
            .max(Instant::compareTo)
            .orElseThrow();
    TagoStopWriteResult writes =
        repository.apply(
            command.cityCode(),
            cityLineage,
            stationSweepLineage,
            command.stations(),
            command.lease().runId(),
            observedAt);
    ImportRunCounts counts =
        new ImportRunCounts(
            command.stations().size(),
            command.pages().size(),
            writes.inserted(),
            writes.updated(),
            writes.skipped(),
            0,
            0,
            writes.staled());
    runs.succeed(command.lease(), counts);
    var checkpoint =
        checkpoints.advance(
            new ImportCheckpointAdvanceCommand(
                TagoStopImportSessionAdapter.SCOPE,
                command.expectedCheckpointVersion(),
                Map.of("cityCode", command.cityCode().code()),
                observedAt,
                command.lease().runId(),
                ImportRunStatus.SUCCEEDED));
    return new TagoStopCommitResult(counts, checkpoint.version());
  }
}
