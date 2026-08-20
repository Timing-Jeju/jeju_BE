package com.timingjeju.api.application.tourapi.discovery;

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
import com.timingjeju.api.application.snapshot.SnapshotMutationOutcome;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotRedactionResult;
import com.timingjeju.api.application.snapshot.SnapshotStateMutation;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStore;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.StoredSnapshot;
import com.timingjeju.api.application.tourapi.place.PlaceListPage;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListSourceResponse;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertResult;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.global.tourapi.discovery.TransactionalDiscoveryImportCommitter;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class DiscoveryImportServiceTest {

  private static final UUID RUN = UUID.fromString("75000000-0000-0000-0000-000000000001");
  private static final UUID OWNER = UUID.fromString("75000000-0000-0000-0000-000000000002");
  private static final UUID SNAPSHOT_1 = UUID.fromString("75000000-0000-0000-0000-000000000003");
  private static final UUID SNAPSHOT_2 = UUID.fromString("75000000-0000-0000-0000-000000000004");
  private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void keyword_page를_적재하면_NFC_alias와_operation별_snapshot_lineage를_함께_전달한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(1, 0, 0));
    DiscoveryImportService service =
        service(
            runs,
            snapshots,
            (command, page) -> response(page),
            (operation, format, payload) -> page(1, 1, List.of(place("100"))),
            repository);

    DiscoveryImportResult result =
        service.importCandidates(DiscoveryImportCommand.keyword("성산  맛집", 2, "keyword-v1"));

    assertThat(result.inserted()).isOne();
    assertThat(repository.commands).singleElement();
    var write = repository.commands.getFirst().writes().getFirst();
    assertThat(write.lineage().operationKey()).isEqualTo("searchKeyword2");
    assertThat(write.aliases())
        .singleElement()
        .satisfies(
            alias -> {
              assertThat(alias.alias()).isEqualTo("성산 맛집");
              assertThat(alias.normalizedAlias()).isEqualTo("성산 맛집");
            });
    assertThat(runs.finishStatus).isEqualTo(ImportRunStatus.SUCCEEDED);
    assertThat(snapshots.statuses).containsExactly(SnapshotStatus.PARSED);
  }

  @Test
  void page_fetch가_두번_일시실패하면_세번째에_같은_page를_재시도한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(1, 0, 0));
    AtomicInteger attempts = new AtomicInteger();
    DiscoveryImportService service =
        service(
            runs,
            snapshots,
            (command, page) -> {
              if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary");
              }
              return response(page);
            },
            (operation, format, payload) -> page(1, 1, List.of(place("100"))),
            repository);

    service.importCandidates(DiscoveryImportCommand.stay(1, "retry-v1"));

    assertThat(attempts).hasValue(3);
    assertThat(snapshots.saved).isOne();
  }

  @Test
  void page간_duplicate_contentid면_snapshot은_보존하고_normalized_write없이_fail한다() {
    FakeRunStore runs = new FakeRunStore(false);
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(0, 0, 0));
    Queue<PlaceListPage> pages = new ArrayDeque<>();
    pages.add(page(1, 2, List.of(place("100"))));
    pages.add(page(2, 2, List.of(place("100"))));
    DiscoveryImportService service =
        service(
            runs,
            snapshots,
            (command, page) -> response(page),
            (operation, format, payload) -> pages.remove(),
            repository);

    assertThatThrownBy(
            () -> service.importCandidates(DiscoveryImportCommand.stay(2, "duplicate-v1")))
        .isInstanceOf(DiscoveryImportException.class)
        .hasMessageContaining("응답 계약");

    assertThat(repository.commands).isEmpty();
    assertThat(snapshots.saved).isEqualTo(2);
    assertThat(runs.finishStatus).isEqualTo(ImportRunStatus.FAILED);
  }

  @Test
  void 동일_idempotency_exact_replay는_이미_저장된_counts와_pageCount를_반환한다() {
    ImportRunCounts counts = new ImportRunCounts(12, 5, 4, 4, 2, 2, 0, 0);
    FakeRunStore runs = new FakeRunStore(true, ImportRunExecutionStatus.SUCCEEDED, counts);
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(0, 0, 0));
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(RUN, 7);
    int[] fetches = {0};
    DiscoveryImportService service =
        service(
            runs,
            snapshots,
            (command, page) -> {
              fetches[0]++;
              return response(page);
            },
            (operation, format, payload) -> {
              throw new AssertionError("parser 호출 금지");
            },
            repository,
            checkpoints);

    DiscoveryImportResult result =
        service.importCandidates(DiscoveryImportCommand.location(126.5, 33.5, 500, 1, "replay"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.pageCount()).isEqualTo(7);
    assertThat(result.inserted()).isEqualTo(4);
    assertThat(result.updated()).isEqualTo(4);
    assertThat(result.skipped()).isEqualTo(2);
    assertThat(result.rejected()).isEqualTo(2);
    assertThat(fetches[0]).isZero();
    assertThat(snapshots.saved).isZero();
    assertThat(repository.commands).isEmpty();
    assertThat(checkpoints.advanceCalls).isZero();
    assertThat(runs.finishStatus).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 100})
  void succeeded_replay의_pageCount는_십진수_끝자리_0이_있는_정수도_허용한다(int pageCount) {
    DiscoveryImportResult result = replayWithPageCount(pageCount);

    assertThat(result.replayed()).isTrue();
    assertThat(result.pageCount()).isEqualTo(pageCount);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "101", "1.5"})
  void succeeded_replay의_pageCount가_누락되거나_범위밖이거나_소수면_실패한다(Object pageCount) {
    assertThatThrownBy(() -> replayWithPageCount(pageCount))
        .isInstanceOf(DiscoveryImportException.class)
        .hasMessageContaining("응답 계약");
  }

  @Test
  void
      running_failed_partial_cancelled_replay는_fetch나_parser_snapshot_normalized_write를_재실행하지_않고_실패한다() {
    for (ImportRunExecutionStatus status :
        List.of(
            ImportRunExecutionStatus.RUNNING,
            ImportRunExecutionStatus.FAILED,
            ImportRunExecutionStatus.PARTIAL,
            ImportRunExecutionStatus.CANCELLED)) {
      FakeRunStore runs = new FakeRunStore(true, status, ImportRunCounts.zero());
      FakeSnapshotStore snapshots = new FakeSnapshotStore();
      FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(0, 0, 0));
      FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(RUN, 0);
      int[] fetches = {0};

      assertThatThrownBy(
              () ->
                  service(
                          runs,
                          snapshots,
                          (command, page) -> {
                            fetches[0]++;
                            return response(page);
                          },
                          (operation, format, payload) -> {
                            throw new AssertionError("parser 호출 금지");
                          },
                          repository,
                          checkpoints)
                      .importCandidates(
                          DiscoveryImportCommand.location(126.5, 33.5, 500, 1, "replay-" + status)))
          .isInstanceOf(DiscoveryImportException.class)
          .hasMessageContaining("응답 계약");

      assertThat(fetches[0]).isZero();
      assertThat(snapshots.saved).isZero();
      assertThat(repository.commands).isEmpty();
      assertThat(checkpoints.advanceCalls).isZero();
      assertThat(runs.finishStatus).isNull();
    }
  }

  @Test
  void succeeded_replay라도_checkpoint_runId가_다르면_실패한다() {
    FakeRunStore runs =
        new FakeRunStore(true, ImportRunExecutionStatus.SUCCEEDED, ImportRunCounts.zero());
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(0, 0, 0));
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(UUID.randomUUID(), 0);

    assertThatThrownBy(
            () ->
                service(
                        runs,
                        snapshots,
                        (command, page) -> {
                          throw new AssertionError("fetch 호출 금지");
                        },
                        (operation, format, payload) -> {
                          throw new AssertionError("parser 호출 금지");
                        },
                        repository,
                        checkpoints)
                    .importCandidates(
                        DiscoveryImportCommand.location(
                            126.5, 33.5, 500, 1, "checkpoint-mismatch")))
        .isInstanceOf(DiscoveryImportException.class)
        .hasMessageContaining("응답 계약");

    assertThat(snapshots.saved).isZero();
    assertThat(repository.commands).isEmpty();
    assertThat(checkpoints.advanceCalls).isZero();
    assertThat(runs.finishStatus).isNull();
  }

  private static DiscoveryImportService service(
      FakeRunStore runs,
      FakeSnapshotStore snapshots,
      DiscoverySource source,
      DiscoveryParser parser,
      FakeRepository repository) {
    return service(runs, snapshots, source, parser, repository, new FakeCheckpointRepository());
  }

  private static DiscoveryImportService service(
      FakeRunStore runs,
      FakeSnapshotStore snapshots,
      DiscoverySource source,
      DiscoveryParser parser,
      FakeRepository repository,
      FakeCheckpointRepository checkpoints) {
    var runService = new ImportRunLifecycleService(runs, CLOCK, new FixedIds());
    var checkpointService = new ImportCheckpointService(checkpoints);
    var committer =
        new TransactionalDiscoveryImportCommitter(repository, runService, checkpointService);
    Queue<UUID> ids = new ArrayDeque<>(List.of(SNAPSHOT_1, SNAPSHOT_2));
    var snapshotService =
        new SnapshotStoreService(snapshots, new SafeRedactor(), CLOCK, ids::remove);
    return new DiscoveryImportService(
        source, parser, committer, runService, checkpointService, snapshotService, CLOCK);
  }

  private static DiscoveryImportResult replayWithPageCount(Object pageCount) {
    FakeRunStore runs =
        new FakeRunStore(true, ImportRunExecutionStatus.SUCCEEDED, ImportRunCounts.zero());
    FakeCheckpointRepository checkpoints = new FakeCheckpointRepository(RUN, pageCount);
    return service(
            runs,
            new FakeSnapshotStore(),
            (command, page) -> {
              throw new AssertionError("fetch 호출 금지");
            },
            (operation, format, payload) -> {
              throw new AssertionError("parser 호출 금지");
            },
            new FakeRepository(new PlaceListUpsertResult(0, 0, 0)),
            checkpoints)
        .importCandidates(DiscoveryImportCommand.location(126.5, 33.5, 500, 1, "replay"));
  }

  private static PlaceListSourceResponse response(int page) {
    return new PlaceListSourceResponse(
        Integer.toString(page).getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static PlaceListPage page(int number, int total, List<TourPlace> places) {
    return new PlaceListPage(number, 100, total, places.size(), places, Map.of());
  }

  private static TourPlace place(String id) {
    return new TourPlace(
        id, "32", "제주 호텔", 126.5, 33.5, "제주", null, null, null, "50", "50110", "AC", null, null,
        NOW);
  }

  private static final class FakeRepository implements PlaceListRepository {
    private final PlaceListUpsertResult result;
    private final List<PlaceListUpsertCommand> commands = new ArrayList<>();

    private FakeRepository(PlaceListUpsertResult result) {
      this.result = result;
    }

    @Override
    public PlaceListUpsertResult upsert(PlaceListUpsertCommand command) {
      commands.add(command);
      return result;
    }
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
    private final ImportRunExecutionStatus status;
    private final ImportRunCounts counts;
    private ImportRunStatus finishStatus;

    private FakeRunStore(boolean replayed) {
      this(replayed, ImportRunExecutionStatus.RUNNING, ImportRunCounts.zero());
    }

    private FakeRunStore(
        boolean replayed, ImportRunExecutionStatus status, ImportRunCounts counts) {
      this.replayed = replayed;
      this.status = status;
      this.counts = counts;
    }

    @Override
    public ImportRunStartResult start(
        ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
      return new ImportRunStartResult(
          new ImportRunLease(runId, ownerToken, 1), replayed, status, counts);
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
      this.finishStatus = status;
      return ImportRunMutationOutcome.UPDATED;
    }
  }

  private static final class FakeSnapshotStore implements SnapshotStore {
    private int saved;
    private final List<SnapshotStatus> statuses = new ArrayList<>();

    @Override
    public com.timingjeju.api.application.snapshot.SnapshotSaveResult save(
        StoredSnapshot snapshot) {
      saved++;
      return snapshot.result(false);
    }

    @Override
    public SnapshotMutationOutcome transition(SnapshotStateMutation mutation) {
      statuses.add(mutation.status());
      return SnapshotMutationOutcome.UPDATED;
    }
  }

  private static final class SafeRedactor
      implements com.timingjeju.api.application.snapshot.SnapshotRedactor {
    @Override
    public String version() {
      return "test-v1";
    }

    @Override
    public SnapshotRedactionResult redact(
        SnapshotPayloadFormat format,
        String charset,
        byte[] payload,
        Map<String, Object> metadata) {
      return new SnapshotRedactionResult("{}", "{}", "{}", SnapshotStatus.RECEIVED, null);
    }
  }

  private static final class FakeCheckpointRepository implements ImportCheckpointRepository {
    private final UUID lastSucceededRunId;
    private final Object pageCount;
    private ImportCheckpoint checkpoint;
    private int advanceCalls;

    private FakeCheckpointRepository() {
      this(null, 0);
    }

    private FakeCheckpointRepository(UUID lastSucceededRunId, Object pageCount) {
      this.lastSucceededRunId = lastSucceededRunId;
      this.pageCount = pageCount;
    }

    @Override
    public Optional<ImportCheckpoint> find(ImportRunScope scope) {
      if (checkpoint == null || !checkpoint.scope().equals(scope)) {
        Map<String, Object> value = new java.util.HashMap<>();
        value.put("manifest", "uninitialized");
        if (pageCount != null) {
          value.put("pageCount", pageCount);
        }
        checkpoint = new ImportCheckpoint(scope, value, Instant.EPOCH, lastSucceededRunId, 0, NOW);
      }
      return Optional.of(checkpoint);
    }

    @Override
    public ImportCheckpoint advance(ImportCheckpointAdvanceCommand command) {
      advanceCalls++;
      checkpoint =
          new ImportCheckpoint(
              command.scope(),
              command.checkpoint(),
              command.sourceWatermarkAt(),
              command.lastSucceededRunId(),
              command.expectedVersion() + 1,
              NOW);
      return checkpoint;
    }
  }
}
