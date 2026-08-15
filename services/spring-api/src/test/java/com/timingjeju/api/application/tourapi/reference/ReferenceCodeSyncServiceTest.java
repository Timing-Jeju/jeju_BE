package com.timingjeju.api.application.tourapi.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunFailure;
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
import com.timingjeju.api.application.snapshot.SnapshotRedactor;
import com.timingjeju.api.application.snapshot.SnapshotStateMutation;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStore;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.StoredSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReferenceCodeSyncServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final UUID RUN = UUID.fromString("25000000-0000-0000-0000-000000000101");
  private static final UUID OWNER = UUID.fromString("25000000-0000-0000-0000-000000000102");
  private static final UUID SNAPSHOT = UUID.fromString("25000000-0000-0000-0000-000000000103");

  @Test
  void 수동_sync는_run_snapshot_parse_upsert를_순서대로_완료한다() {
    FakeRunStore runs = new FakeRunStore();
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    AtomicBoolean repositoryCalled = new AtomicBoolean();
    ReferenceCodeSyncService service =
        service(
            runs,
            snapshots,
            (operation, format, payload) ->
                List.of(
                    new ReferenceCode("ldong-region", "50", null, "제주특별자치도", "제주특별자치도", Map.of())),
            command -> {
              repositoryCalled.set(true);
              assertThat(command.lineage().snapshotId()).isEqualTo(SNAPSHOT);
              assertThat(command.lineage().importRunId()).isEqualTo(RUN);
              return new ReferenceCodeUpsertResult(1, 0, 0);
            });

    ReferenceCodeSyncResult result =
        service.sync(
            new ReferenceCodeSyncCommand(
                ReferenceCodeOperation.LDONG,
                LocalDate.of(2026, 1, 12),
                null,
                "reference-20260815"));

    assertThat(repositoryCalled).isTrue();
    assertThat(result.runId()).isEqualTo(RUN);
    assertThat(result.snapshotId()).isEqualTo(SNAPSHOT);
    assertThat(snapshots.status).isEqualTo(SnapshotStatus.PARSED);
    assertThat(runs.status).isEqualTo(ImportRunStatus.SUCCEEDED);
  }

  @Test
  void parser가_거부하면_snapshot과_run을_실패로_종료하고_저장하지_않는다() {
    FakeRunStore runs = new FakeRunStore();
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    AtomicBoolean repositoryCalled = new AtomicBoolean();
    ReferenceCodeSyncService service =
        service(
            runs,
            snapshots,
            (operation, format, payload) -> {
              throw ReferenceCodeSyncException.invalidResponse();
            },
            command -> {
              repositoryCalled.set(true);
              return new ReferenceCodeUpsertResult(0, 0, 0);
            });

    assertThatThrownBy(
            () ->
                service.sync(
                    new ReferenceCodeSyncCommand(
                        ReferenceCodeOperation.CLASSIFICATION,
                        LocalDate.of(2026, 1, 12),
                        null,
                        "reference-20260815-category")))
        .isInstanceOf(ReferenceCodeSyncException.class);

    assertThat(repositoryCalled).isFalse();
    assertThat(snapshots.status).isEqualTo(SnapshotStatus.REJECTED);
    assertThat(runs.status).isEqualTo(ImportRunStatus.FAILED);
    assertThat(runs.failure).isEqualTo(ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  private ReferenceCodeSyncService service(
      FakeRunStore runs,
      FakeSnapshotStore snapshots,
      ReferenceCodeParser parser,
      ReferenceCodeRepository repository) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ImportRunLifecycleService runService =
        new ImportRunLifecycleService(runs, clock, new FixedRunIdentityGenerator());
    SnapshotStoreService snapshotService =
        new SnapshotStoreService(
            snapshots,
            new SnapshotRedactor() {
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
            },
            clock,
            () -> SNAPSHOT);
    return new ReferenceCodeSyncService(
        operation -> new ReferenceCodeSourceResponse("{}".getBytes(), SnapshotPayloadFormat.JSON),
        parser,
        repository,
        runService,
        snapshotService,
        clock);
  }

  private static final class FixedRunIdentityGenerator
      implements com.timingjeju.api.application.importing.ImportRunIdentityGenerator {
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
    private final ImportRunLease lease = new ImportRunLease(RUN, OWNER, 1);
    private ImportRunStatus status;
    private ImportRunFailure failure;

    @Override
    public ImportRunStartResult start(
        ImportRunStartCommand command, UUID runId, UUID ownerToken, Instant startedAt) {
      return new ImportRunStartResult(lease, false);
    }

    @Override
    public ImportRunMutationOutcome addCounts(
        ImportRunLease lease, com.timingjeju.api.application.importing.ImportRunCounts delta) {
      return ImportRunMutationOutcome.UPDATED;
    }

    @Override
    public ImportRunMutationOutcome finish(
        ImportRunLease lease,
        ImportRunStatus status,
        com.timingjeju.api.application.importing.ImportRunCounts delta,
        ImportRunFailure failure,
        Instant finishedAt) {
      this.status = status;
      this.failure = failure;
      return ImportRunMutationOutcome.UPDATED;
    }
  }

  private static final class FakeSnapshotStore implements SnapshotStore {
    private SnapshotStatus status;

    @Override
    public com.timingjeju.api.application.snapshot.SnapshotSaveResult save(
        StoredSnapshot snapshot) {
      status = snapshot.status();
      return new com.timingjeju.api.application.snapshot.SnapshotSaveResult(
          SNAPSHOT, snapshot.requestHash(), snapshot.payloadHash(), false);
    }

    @Override
    public SnapshotMutationOutcome transition(SnapshotStateMutation mutation) {
      status = mutation.status();
      return SnapshotMutationOutcome.UPDATED;
    }
  }
}
