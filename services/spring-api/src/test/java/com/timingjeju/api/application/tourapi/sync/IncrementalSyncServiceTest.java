package com.timingjeju.api.application.tourapi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointRepository;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunIdentityGenerator;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunMutationOutcome;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.importing.ImportRunStatus;
import com.timingjeju.api.application.importing.ImportRunStore;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IncrementalSyncServiceTest {
  private static final UUID RUN = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000002");
  private static final UUID SNAPSHOT_1 = UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID SNAPSHOT_2 = UUID.fromString("30000000-0000-0000-0000-000000000004");
  private static final Instant CURSOR = Instant.parse("2026-08-16T00:00:00Z");
  private static final Instant MODIFIED_1 = Instant.parse("2026-08-16T01:00:00Z");
  private static final Instant MODIFIED_2 = Instant.parse("2026-08-16T02:00:00Z");
  private static final Instant NOW = Instant.parse("2026-08-16T03:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ImportRunScope SCOPE =
      new ImportRunScope("tour-api", "KorService2", "areaBasedSyncList2", "jeju");

  @Test
  void 모든_page_snapshot을_검증한_뒤에만_normalized와_checkpoint를_한번_commit한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(checkpoint());
    FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
    Queue<IncrementalSyncPage> pages = new ArrayDeque<>();
    pages.add(page(1, 101, upserts(100, MODIFIED_1)));
    pages.add(page(2, 101, List.of(delete("200", MODIFIED_2))));
    FakeCommitter committer = new FakeCommitter();
    IncrementalSyncService service = service(runs, checkpoints, snapshots, pages, committer);

    IncrementalSyncResult result =
        service.sync(new IncrementalSyncCommand("issue-30-cursor-20260816"));

    assertThat(result.runId()).isEqualTo(RUN);
    assertThat(result.pageCount()).isEqualTo(2);
    assertThat(result.checkpointVersion()).isEqualTo(8);
    assertThat(result.replayed()).isFalse();
    assertThat(committer.commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.expectedCheckpointVersion()).isEqualTo(7);
              assertThat(command.cursorBefore()).isEqualTo(new IncrementalSyncCursor(CURSOR));
              assertThat(command.cursorAfter()).isEqualTo(new IncrementalSyncCursor(MODIFIED_2));
              assertThat(command.writes()).hasSize(101);
              assertThat(command.writes().getFirst().change().action())
                  .isEqualTo(PlaceSyncAction.UPSERT);
              assertThat(command.writes().getFirst().lineage().snapshotId()).isEqualTo(SNAPSHOT_1);
              assertThat(command.writes().getLast().change().action())
                  .isEqualTo(PlaceSyncAction.DELETE);
              assertThat(command.writes().getLast().lineage().snapshotId()).isEqualTo(SNAPSHOT_2);
              assertThat(command.pages()).extracting(page -> page.pageNo()).containsExactly(1, 2);
            });
    assertThat(checkpoints.advanceCalls).isZero();
    assertThat(snapshots.statuses).containsExactly(SnapshotStatus.PARSED, SnapshotStatus.PARSED);
  }

  @Test
  void 중간_page가_truncated되면_normalized_checkpoint_commit없이_run을_fail한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(checkpoint());
    FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
    Queue<IncrementalSyncPage> pages = new ArrayDeque<>();
    pages.add(page(1, 201, upserts(100, MODIFIED_1)));
    pages.add(page(2, 201, List.of(delete("200", MODIFIED_2))));
    FakeCommitter committer = new FakeCommitter();
    IncrementalSyncService service = service(runs, checkpoints, snapshots, pages, committer);

    assertThatThrownBy(() -> service.sync(new IncrementalSyncCommand("issue-30-partial-page")))
        .isInstanceOf(IncrementalSyncException.class);

    assertThat(committer.commands).isEmpty();
    assertThat(checkpoints.advanceCalls).isZero();
    assertThat(runs.status).isEqualTo(ImportRunStatus.FAILED);
    assertThat(runs.failure).isEqualTo(ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  @Test
  void page_간_duplicate_contentid는_complete_batch가_아니므로_commit하지_않는다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(checkpoint());
    FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
    Queue<IncrementalSyncPage> pages = new ArrayDeque<>();
    pages.add(page(1, 101, upserts(100, MODIFIED_1)));
    pages.add(page(2, 101, List.of(delete("1000", MODIFIED_2))));
    FakeCommitter committer = new FakeCommitter();

    assertThatThrownBy(
            () ->
                service(runs, checkpoints, snapshots, pages, committer)
                    .sync(new IncrementalSyncCommand("issue-30-cross-page-duplicate")))
        .isInstanceOf(IncrementalSyncException.class);

    assertThat(committer.commands).isEmpty();
    assertThat(runs.status).isEqualTo(ImportRunStatus.FAILED);
  }

  @Test
  void committed_succeeded_replay만_저장된_counts와_checkpoint를_진실하게_반환한다() {
    ImportRunCounts storedCounts = new ImportRunCounts(9, 3, 4, 2, 2, 0, 1, 0);
    FakeRunStore runs = new FakeRunStore(true, ImportRunExecutionStatus.SUCCEEDED, storedCounts);
    FakeCheckpointRepository checkpoints =
        new FakeCheckpointRepository(
            new ImportCheckpoint(
                SCOPE, Map.of("modifiedTime", CURSOR.toString()), NOW, RUN, 8, NOW));
    FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
    FakeCommitter committer = new FakeCommitter();
    int[] fetchCalls = {0};
    IncrementalSyncService service =
        service(
            runs,
            checkpoints,
            snapshots,
            (cursor, pageNo) -> {
              fetchCalls[0]++;
              return response(pageNo);
            },
            (format, payload) -> {
              throw new AssertionError("parser를 호출하면 안 됩니다.");
            },
            committer);

    IncrementalSyncResult result =
        service.sync(new IncrementalSyncCommand("issue-30-cursor-replay"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.runId()).isEqualTo(RUN);
    assertThat(result.pageCount()).isEqualTo(3);
    assertThat(result.counts()).isEqualTo(storedCounts);
    assertThat(result.checkpointVersion()).isEqualTo(8);
    assertThat(fetchCalls[0]).isZero();
    assertThat(snapshots.saved).isZero();
    assertThat(committer.commands).isEmpty();
  }

  @Test
  void complete_empty_page도_원본_snapshot을_남기고_cursor를_역행없이_commit한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(checkpoint());
    FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
    Queue<IncrementalSyncPage> pages = new ArrayDeque<>();
    pages.add(page(1, 0, List.of()));
    FakeCommitter committer = new FakeCommitter();

    service(runs, checkpoints, snapshots, pages, committer)
        .sync(new IncrementalSyncCommand("issue-30-empty"));

    assertThat(committer.commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.writes()).isEmpty();
              assertThat(command.cursorBefore()).isEqualTo(new IncrementalSyncCursor(CURSOR));
              assertThat(command.cursorAfter()).isEqualTo(new IncrementalSyncCursor(CURSOR));
              assertThat(command.pages()).hasSize(1);
            });
    assertThat(snapshots.statuses).containsExactly(SnapshotStatus.PARSED);
  }

  @Test
  void running_failed_partial_cancelled_replay는_fetch나_commit없이_안정적으로_실패한다() {
    for (ImportRunExecutionStatus status :
        List.of(
            ImportRunExecutionStatus.RUNNING,
            ImportRunExecutionStatus.FAILED,
            ImportRunExecutionStatus.PARTIAL,
            ImportRunExecutionStatus.CANCELLED)) {
      FakeRunStore runs = new FakeRunStore(true, status, ImportRunCounts.zero());
      FakeSnapshotGateway snapshots = new FakeSnapshotGateway();
      FakeCommitter committer = new FakeCommitter();
      int[] fetchCalls = {0};

      assertThatThrownBy(
              () ->
                  service(
                          runs,
                          new FakeCheckpointRepository(checkpoint()),
                          snapshots,
                          (cursor, pageNo) -> {
                            fetchCalls[0]++;
                            return response(pageNo);
                          },
                          (format, payload) -> {
                            throw new AssertionError("parser를 호출하면 안 됩니다.");
                          },
                          committer)
                      .sync(new IncrementalSyncCommand("issue-30-replay-" + status)))
          .isInstanceOf(IncrementalSyncException.class);

      assertThat(fetchCalls[0]).isZero();
      assertThat(snapshots.saved).isZero();
      assertThat(committer.commands).isEmpty();
      assertThat(runs.status).isNull();
    }
  }

  @Test
  void succeeded_replay라도_checkpoint가_그_run을_가리키지_않으면_실패한다() {
    FakeRunStore runs =
        new FakeRunStore(true, ImportRunExecutionStatus.SUCCEEDED, ImportRunCounts.zero());

    assertThatThrownBy(
            () ->
                service(
                        runs,
                        new FakeCheckpointRepository(checkpoint()),
                        new FakeSnapshotGateway(),
                        new ArrayDeque<>(),
                        new FakeCommitter())
                    .sync(new IncrementalSyncCommand("issue-30-mismatched-checkpoint")))
        .isInstanceOf(IncrementalSyncException.class);
  }

  private static IncrementalSyncService service(
      FakeRunStore runs,
      FakeCheckpointRepository checkpoints,
      FakeSnapshotGateway snapshots,
      Queue<IncrementalSyncPage> pages,
      FakeCommitter committer) {
    return service(
        runs,
        checkpoints,
        snapshots,
        (cursor, pageNo) -> response(pageNo),
        (format, payload) -> pages.remove(),
        committer);
  }

  private static IncrementalSyncService service(
      FakeRunStore runs,
      FakeCheckpointRepository checkpoints,
      FakeSnapshotGateway snapshots,
      IncrementalSyncSource source,
      IncrementalSyncParser parser,
      FakeCommitter committer) {
    return new IncrementalSyncService(
        source,
        snapshots,
        parser,
        new ImportCheckpointService(checkpoints),
        new ImportRunLifecycleService(runs, CLOCK, new FixedIds()),
        committer,
        CLOCK);
  }

  private static ImportCheckpoint checkpoint() {
    return new ImportCheckpoint(
        SCOPE, Map.of("modifiedTime", CURSOR.toString()), null, null, 7, NOW);
  }

  private static IncrementalSyncPage page(int pageNo, int total, List<PlaceSyncChange> changes) {
    return new IncrementalSyncPage(pageNo, 100, total, changes.size(), changes);
  }

  private static PlaceSyncChange upsert(String contentId, Instant modifiedAt) {
    TourPlace place =
        new TourPlace(
            contentId,
            "12",
            "성산일출봉",
            126.5,
            33.5,
            "제주",
            null,
            null,
            null,
            "50",
            "50130",
            "VE",
            "VE01",
            "VE0101",
            modifiedAt);
    return new PlaceSyncChange(contentId, "12", modifiedAt, PlaceSyncAction.UPSERT, place);
  }

  private static List<PlaceSyncChange> upserts(int count, Instant modifiedAt) {
    List<PlaceSyncChange> changes = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      changes.add(upsert(Integer.toString(1000 + index), modifiedAt));
    }
    return changes;
  }

  private static PlaceSyncChange delete(String contentId, Instant modifiedAt) {
    return new PlaceSyncChange(contentId, "12", modifiedAt, PlaceSyncAction.DELETE, null);
  }

  private static IncrementalSyncSourceResponse response(int pageNo) {
    return new IncrementalSyncSourceResponse(
        Integer.toString(pageNo).getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static final class FixedIds implements ImportRunIdentityGenerator {
    @Override
    public UUID newRunId() {
      return RUN;
    }

    @Override
    public UUID newOwnerToken() {
      return OWNER;
    }
  }

  private static final class FakeRunStore implements ImportRunStore {
    private final boolean replayed;
    private final ImportRunExecutionStatus executionStatus;
    private final ImportRunCounts counts;
    private ImportRunStatus status;
    private ImportRunFailure failure;

    private FakeRunStore(boolean replayed) {
      this(replayed, ImportRunExecutionStatus.RUNNING, ImportRunCounts.zero());
    }

    private FakeRunStore(
        boolean replayed, ImportRunExecutionStatus executionStatus, ImportRunCounts counts) {
      this.replayed = replayed;
      this.executionStatus = executionStatus;
      this.counts = counts;
    }

    @Override
    public ImportRunStartResult start(
        ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
      return new ImportRunStartResult(
          new ImportRunLease(runId, ownerToken, 1), replayed, executionStatus, counts);
    }

    @Override
    public ImportRunMutationOutcome addCounts(ImportRunLease lease, ImportRunCounts delta) {
      return ImportRunMutationOutcome.UPDATED;
    }

    @Override
    public ImportRunMutationOutcome finish(
        ImportRunLease lease,
        ImportRunStatus status,
        ImportRunCounts delta,
        ImportRunFailure failure,
        Instant finishedAt) {
      this.status = status;
      this.failure = failure;
      return ImportRunMutationOutcome.UPDATED;
    }
  }

  private static final class FakeCheckpointRepository implements ImportCheckpointRepository {
    private final ImportCheckpoint checkpoint;
    private int advanceCalls;

    private FakeCheckpointRepository(ImportCheckpoint checkpoint) {
      this.checkpoint = checkpoint;
    }

    @Override
    public Optional<ImportCheckpoint> find(ImportRunScope scope) {
      return Optional.of(checkpoint);
    }

    @Override
    public ImportCheckpoint advance(ImportCheckpointAdvanceCommand command) {
      advanceCalls++;
      throw new AssertionError("service가 checkpoint를 직접 전진하면 안 됩니다.");
    }
  }

  private static final class FakeSnapshotGateway implements IncrementalSyncSnapshotGateway {
    private final Queue<UUID> ids = new ArrayDeque<>(List.of(SNAPSHOT_1, SNAPSHOT_2));
    private final List<SnapshotStatus> statuses = new ArrayList<>();
    private int saved;

    @Override
    public SavedIncrementalSyncPage save(
        UUID runId,
        IncrementalSyncCursor cursor,
        int pageNo,
        IncrementalSyncSourceResponse response) {
      saved++;
      UUID id = ids.remove();
      return new SavedIncrementalSyncPage(
          response,
          pageNo,
          "a".repeat(64),
          NOW,
          new IncrementalSyncLineage("areaBasedSyncList2", "b".repeat(64), id, runId),
          false,
          SnapshotStatus.RECEIVED);
    }

    @Override
    public void markParsed(SavedIncrementalSyncPage page) {
      statuses.add(SnapshotStatus.PARSED);
    }

    @Override
    public void markRejected(SavedIncrementalSyncPage page) {
      statuses.add(SnapshotStatus.REJECTED);
    }
  }

  private static final class FakeCommitter implements IncrementalSyncCommitter {
    private final List<IncrementalSyncCommitCommand> commands = new ArrayList<>();

    @Override
    public IncrementalSyncCommitResult commit(IncrementalSyncCommitCommand command) {
      commands.add(command);
      return new IncrementalSyncCommitResult(new ImportRunCounts(101, 2, 100, 0, 0, 0, 0, 1), 8);
    }
  }
}
