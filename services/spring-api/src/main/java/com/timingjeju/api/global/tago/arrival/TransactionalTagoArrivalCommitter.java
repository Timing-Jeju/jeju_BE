package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitResult;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalTagoArrivalCommitter implements TagoArrivalCommitter {
  private final SnapshotStoreService snapshots;
  private final TagoArrivalRepository repository;
  private final ImportRunLifecycleService runs;

  public TransactionalTagoArrivalCommitter(
      SnapshotStoreService snapshots,
      TagoArrivalRepository repository,
      ImportRunLifecycleService runs) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.runs = Objects.requireNonNull(runs, "runs는 필수입니다.");
  }

  @Override
  @Transactional
  public TagoArrivalCommitResult commit(TagoArrivalCommitCommand command) {
    snapshots.transition(
        new SnapshotTransitionCommand(
            command.snapshot().snapshotId(), SnapshotStatus.PARSED, null));
    int inserted = repository.append(command);
    runs.succeed(
        command.lease(),
        new ImportRunCounts(command.arrivals().size(), 1, inserted, 0, 0, 0, 0, 0));
    return new TagoArrivalCommitResult(inserted);
  }
}
