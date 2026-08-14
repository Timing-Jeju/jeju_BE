package com.timingjeju.api.application.importing;

import java.util.Optional;

public interface ImportCheckpointRepository {

  Optional<ImportCheckpoint> find(ImportRunScope scope);

  ImportCheckpoint advance(ImportCheckpointAdvanceCommand command);
}
