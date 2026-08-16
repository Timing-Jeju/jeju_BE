package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.kma.KmaWeatherCommitCommand;
import com.timingjeju.api.application.kma.KmaWeatherCommitResult;
import com.timingjeju.api.application.kma.KmaWeatherCommitter;
import com.timingjeju.api.application.kma.KmaWeatherRepository;
import com.timingjeju.api.application.kma.KmaWeatherUpsertCommand;
import com.timingjeju.api.application.kma.KmaWeatherUpsertResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalKmaWeatherCommitter implements KmaWeatherCommitter {

  private final KmaWeatherRepository repository;
  private final ImportRunLifecycleService runs;
  private final ImportCheckpointService checkpoints;

  public TransactionalKmaWeatherCommitter(
      KmaWeatherRepository repository,
      ImportRunLifecycleService runs,
      ImportCheckpointService checkpoints) {
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints는 필수입니다.");
  }

  @Override
  @Transactional
  public KmaWeatherCommitResult commit(KmaWeatherCommitCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    KmaWeatherUpsertResult stored =
        repository.upsert(
            new KmaWeatherUpsertCommand(command.gridPointId(), command.batch(), command.lineage()));
    ImportRunCounts counts =
        new ImportRunCounts(
            command.batch().rawItemCount(),
            1,
            stored.inserted(),
            stored.updated(),
            stored.skipped(),
            0,
            0,
            0);
    runs.succeed(command.lease(), counts);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("baseDate", command.base().baseDate().toString());
    value.put("baseTime", command.base().baseTime().toString());
    value.put("sourceWatermarkAt", command.batch().sourceWatermarkAt().toString());
    value.put("stale", command.stale());
    var checkpoint =
        checkpoints.advance(
            new ImportCheckpointAdvanceCommand(
                command.scope(),
                command.expectedCheckpointVersion(),
                value,
                command.batch().sourceWatermarkAt(),
                command.lease().runId(),
                ImportRunStatus.SUCCEEDED));
    return new KmaWeatherCommitResult(counts, checkpoint.version());
  }
}
