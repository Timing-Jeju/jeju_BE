package com.timingjeju.api.application.importing;

import java.time.Instant;
import java.util.UUID;

public interface ImportRunStore {
  ImportRunStartResult start(
      ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt);

  ImportRunMutationOutcome addCounts(ImportRunLease lease, ImportRunCounts delta);

  ImportRunMutationOutcome finish(
      ImportRunLease lease,
      ImportRunStatus status,
      ImportRunCounts delta,
      ImportRunFailure failure,
      Instant finishedAt);
}
