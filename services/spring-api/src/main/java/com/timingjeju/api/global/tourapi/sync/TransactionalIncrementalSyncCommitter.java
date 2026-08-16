package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.tourapi.sync.IncrementalPlaceRepository;
import com.timingjeju.api.application.tourapi.sync.IncrementalPlaceWriteResult;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitCommand;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitResult;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCommitter;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalIncrementalSyncCommitter implements IncrementalSyncCommitter {
  private static final ImportRunScope SCOPE =
      new ImportRunScope("tour-api", "KorService2", "areaBasedSyncList2", "jeju");

  private final IncrementalPlaceRepository placeRepository;
  private final ImportRunLifecycleService runService;
  private final ImportCheckpointService checkpointService;

  public TransactionalIncrementalSyncCommitter(
      IncrementalPlaceRepository placeRepository,
      ImportRunLifecycleService runService,
      ImportCheckpointService checkpointService) {
    this.placeRepository = Objects.requireNonNull(placeRepository, "placeRepository는 필수입니다.");
    this.runService = Objects.requireNonNull(runService, "runService는 필수입니다.");
    this.checkpointService = Objects.requireNonNull(checkpointService, "checkpointService는 필수입니다.");
  }

  @Override
  @Transactional
  public IncrementalSyncCommitResult commit(IncrementalSyncCommitCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    IncrementalPlaceWriteResult writeResult = placeRepository.apply(command.writes());
    ImportRunCounts counts =
        new ImportRunCounts(
            command.writes().size(),
            command.pages().size(),
            writeResult.inserted(),
            writeResult.updated(),
            writeResult.skipped(),
            0,
            writeResult.tombstoned(),
            writeResult.staled());
    runService.succeed(command.lease(), counts);
    var checkpoint =
        checkpointService.advance(
            new ImportCheckpointAdvanceCommand(
                SCOPE,
                command.expectedCheckpointVersion(),
                Map.of("modifiedTime", command.cursorAfter().modifiedAfter().toString()),
                command.sourceWatermarkAt(),
                command.lease().runId(),
                ImportRunStatus.SUCCEEDED));
    return new IncrementalSyncCommitResult(counts, checkpoint.version());
  }
}
