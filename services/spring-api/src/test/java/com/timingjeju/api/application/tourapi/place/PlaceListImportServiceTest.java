package com.timingjeju.api.application.tourapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunIdentityGenerator;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunMutationOutcome;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class PlaceListImportServiceTest {

  private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000026");
  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000126");
  private static final UUID SNAPSHOT_1 = UUID.fromString("00000000-0000-0000-0000-000000000226");
  private static final UUID SNAPSHOT_2 = UUID.fromString("00000000-0000-0000-0000-000000000326");
  private static final Instant NOW = Instant.parse("2026-08-16T03:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void 두페이지_duplicate와_invalid_coordinate를_원자적_batch로_적재하고_partial_summary를_남긴다() {
    FakeRunStore runStore = new FakeRunStore(false);
    FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    Queue<PlaceListPage> pages = new ArrayDeque<>();
    pages.add(page(1, 100, 4, List.of(place("1"), place("2")), Map.of()));
    pages.add(
        page(2, 100, 4, List.of(place("1")), Map.of(PlaceRejectReason.INVALID_COORDINATE, 1)));
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(2, 0, 1));
    PlaceListImportService service = service(runStore, snapshotStore, pages, repository);

    PlaceListImportResult result =
        service.importPlaces(new PlaceListImportCommand("issue-26-fixture-v1"));

    assertThat(result.runId()).isEqualTo(RUN_ID);
    assertThat(result.pageCount()).isEqualTo(2);
    assertThat(result.inserted()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.rejectedReasons()).containsEntry(PlaceRejectReason.INVALID_COORDINATE, 1);
    assertThat(result.replayed()).isFalse();
    assertThat(repository.commands)
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.writes()).hasSize(3);
              assertThat(command.writes())
                  .extracting(write -> write.place().contentId())
                  .containsExactly("1", "2", "1");
              assertThat(command.writes())
                  .extracting(write -> write.lineage().snapshotId())
                  .containsExactly(SNAPSHOT_1, SNAPSHOT_1, SNAPSHOT_2);
              assertThat(command.writes())
                  .allSatisfy(
                      write -> {
                        assertThat(write.lineage().operationKey()).isEqualTo("areaBasedList2");
                        assertThat(write.lineage().importRunId()).isEqualTo(RUN_ID);
                      });
            });
    assertThat(runStore.status).isEqualTo(ImportRunStatus.PARTIAL);
    assertThat(runStore.failure).isEqualTo(ImportRunFailure.PARSE_REJECTED);
    assertThat(runStore.counts).isEqualTo(new ImportRunCounts(4, 2, 2, 0, 1, 1, 0, 0));
    assertThat(snapshotStore.statuses)
        .containsExactly(SnapshotStatus.PARSED, SnapshotStatus.PARSED);
  }

  @Test
  void 같은_idempotency_snapshot_replay는_fetch와_normalized_write를_반복하지_않는다() {
    FakeRunStore runStore = new FakeRunStore(true);
    FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(0, 0, 0));
    int[] fetchCalls = {0};
    PlaceListImportService service =
        service(
            runStore,
            snapshotStore,
            pageNo -> {
              fetchCalls[0]++;
              return response(pageNo);
            },
            (format, payload) -> {
              throw new AssertionError("parser를 호출하면 안 됩니다.");
            },
            repository);

    PlaceListImportResult result =
        service.importPlaces(new PlaceListImportCommand("issue-26-fixture-v1"));

    assertThat(result.replayed()).isTrue();
    assertThat(fetchCalls[0]).isZero();
    assertThat(repository.commands).isEmpty();
    assertThat(snapshotStore.saved).isZero();
  }

  @Test
  void 응답_pageNo나_totalCount가_요청계약과_다르면_normalized_write없이_run을_fail한다() {
    FakeRunStore runStore = new FakeRunStore(false);
    FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    Queue<PlaceListPage> pages = new ArrayDeque<>();
    pages.add(page(2, 1, 1, List.of(place("1")), Map.of()));
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(1, 0, 0));
    PlaceListImportService service = service(runStore, snapshotStore, pages, repository);

    assertThatThrownBy(() -> service.importPlaces(new PlaceListImportCommand("bad-page")))
        .isInstanceOf(PlaceListImportException.class);

    assertThat(repository.commands).isEmpty();
    assertThat(runStore.status).isEqualTo(ImportRunStatus.FAILED);
    assertThat(runStore.failure).isEqualTo(ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 99, 101, Integer.MAX_VALUE})
  void 응답_numOfRows가_fixed_100과_다르면_첫_page에서_fail_fast한다(int numOfRows) {
    FakeRunStore runStore = new FakeRunStore(false);
    FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    FakeRepository repository = new FakeRepository(new PlaceListUpsertResult(1, 0, 0));
    int[] sourceCalls = {0};
    int[] parserCalls = {0};
    PlaceListImportService service =
        service(
            runStore,
            snapshotStore,
            pageNo -> {
              sourceCalls[0]++;
              return response(pageNo);
            },
            (format, payload) -> {
              parserCalls[0]++;
              return page(1, numOfRows, 1, List.of(place("1")), Map.of());
            },
            repository);

    assertThatThrownBy(() -> service.importPlaces(new PlaceListImportCommand("bad-page-size")))
        .isInstanceOf(PlaceListImportException.class);

    assertThat(sourceCalls[0]).isOne();
    assertThat(parserCalls[0]).isOne();
    assertThat(repository.commands).isEmpty();
    assertThat(snapshotStore.saved).isOne();
    assertThat(snapshotStore.statuses).containsExactly(SnapshotStatus.REJECTED);
    assertThat(runStore.terminalMutations).isOne();
    assertThat(runStore.status).isEqualTo(ImportRunStatus.FAILED);
    assertThat(runStore.failure).isEqualTo(ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  private static PlaceListImportService service(
      FakeRunStore runStore,
      FakeSnapshotStore snapshotStore,
      Queue<PlaceListPage> pages,
      FakeRepository repository) {
    return service(
        runStore,
        snapshotStore,
        pageNo -> response(pageNo),
        (format, payload) -> pages.remove(),
        repository);
  }

  private static PlaceListImportService service(
      FakeRunStore runStore,
      FakeSnapshotStore snapshotStore,
      PlaceListSource source,
      PlaceListParser parser,
      FakeRepository repository) {
    ImportRunLifecycleService runService =
        new ImportRunLifecycleService(runStore, CLOCK, new FixedRunIds());
    Queue<UUID> snapshotIds = new ArrayDeque<>(List.of(SNAPSHOT_1, SNAPSHOT_2));
    SnapshotStoreService snapshotService =
        new SnapshotStoreService(snapshotStore, new SafeRedactor(), CLOCK, snapshotIds::remove);
    return new PlaceListImportService(
        source, parser, repository, runService, snapshotService, CLOCK);
  }

  private static PlaceListSourceResponse response(int pageNo) {
    return new PlaceListSourceResponse(
        Integer.toString(pageNo).getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static PlaceListPage page(
      int pageNo,
      int rows,
      int total,
      List<TourPlace> places,
      Map<PlaceRejectReason, Integer> rejected) {
    return new PlaceListPage(
        pageNo,
        rows,
        total,
        places.size() + rejected.values().stream().mapToInt(Integer::intValue).sum(),
        places,
        rejected);
  }

  private static TourPlace place(String contentId) {
    return new TourPlace(
        contentId, "12", "성산일출봉", 126.5, 33.5, "제주", "성산읍", null, null, "50", "50130", "VE", "VE01",
        "VE0101", NOW);
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

  private static final class FixedRunIds implements ImportRunIdentityGenerator {
    @Override
    public UUID newRunId() {
      return RUN_ID;
    }

    @Override
    public UUID newOwnerToken() {
      return OWNER_ID;
    }
  }

  private static final class FakeRunStore implements ImportRunStore {
    private final boolean replayed;
    private ImportRunStatus status;
    private ImportRunFailure failure;
    private ImportRunCounts counts;
    private int terminalMutations;

    private FakeRunStore(boolean replayed) {
      this.replayed = replayed;
    }

    @Override
    public ImportRunStartResult start(
        ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
      return new ImportRunStartResult(new ImportRunLease(runId, ownerToken, 1L), replayed);
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
      terminalMutations++;
      this.status = status;
      this.failure = failure;
      this.counts = delta;
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
      return "test-redactor-v1";
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
}
