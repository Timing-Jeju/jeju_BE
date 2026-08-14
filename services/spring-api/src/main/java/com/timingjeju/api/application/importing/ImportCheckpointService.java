package com.timingjeju.api.application.importing;

import java.util.Objects;
import java.util.Optional;

public final class ImportCheckpointService {

  private final ImportCheckpointRepository repository;

  public ImportCheckpointService(ImportCheckpointRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
  }

  public Optional<ImportCheckpoint> find(ImportRunScope scope) {
    return repository.find(Objects.requireNonNull(scope, "scope는 필수입니다."));
  }

  public ImportCheckpoint advance(ImportCheckpointAdvanceCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    if (command.runStatus() != ImportRunStatus.SUCCEEDED) {
      throw ImportCheckpointException.of(ImportCheckpointError.RUN_NOT_SUCCEEDED);
    }
    return repository.advance(command);
  }
}
