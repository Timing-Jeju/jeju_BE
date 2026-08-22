package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStartCommand;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class TagoArrivalAdaptersTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID RUN = UUID.fromString("39000000-0000-0000-0000-000000000010");
  private static final UUID OWNER = UUID.fromString("39000000-0000-0000-0000-000000000011");
  private static final UUID SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000012");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 1);
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");
  private static final byte[] EXACT =
      " {\"response\": {\"body\": {\"arrtime\":1.00}}} \n".getBytes(StandardCharsets.UTF_8);

  @Test
  void session은_provider_service_stop_scope와_25초_bucket으로_run을_시작하고_error를_분류한다() {
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    when(runs.start(any()))
        .thenReturn(
            new ImportRunStartResult(
                LEASE, false, ImportRunExecutionStatus.RUNNING, ImportRunCounts.zero()));
    TagoArrivalImportSessionAdapter adapter = new TagoArrivalImportSessionAdapter(runs);

    assertThat(adapter.start(KEY, NOW)).isEqualTo(LEASE);
    adapter.fail(LEASE, TagoArrivalException.Code.RATE_LIMITED);

    ArgumentCaptor<ImportRunStartCommand> command =
        ArgumentCaptor.forClass(ImportRunStartCommand.class);
    verify(runs).start(command.capture());
    assertThat(command.getValue().scope().provider()).isEqualTo("TAGO");
    assertThat(command.getValue().scope().service()).isEqualTo("ArvlInfoInqireService");
    assertThat(command.getValue().scope().operation())
        .isEqualTo("getSttnAcctoArvlPrearngeInfoList");
    assertThat(command.getValue().scope().scopeKey()).contains("39").contains("JEP123");
    assertThat(command.getValue().idempotencyKey()).isEqualTo("arrival:71473536");
    verify(runs)
        .fail(
            LEASE, com.timingjeju.api.application.importing.ImportRunFailure.PROVIDER_UNAVAILABLE);
  }

  @Test
  void gateway는_exact_hash를_계산하는_snapshot_command와_terminal_rejected를_사용한다() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any()))
        .thenReturn(
            new SnapshotSaveResult(
                SNAPSHOT, "a".repeat(64), "b".repeat(64), false, NOW, SnapshotStatus.RECEIVED));
    SnapshottingTagoArrivalGateway gateway = new SnapshottingTagoArrivalGateway(store);
    TagoArrivalSourceResponse response =
        new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON);

    SavedTagoArrivalSnapshot saved = gateway.capture(RUN, KEY, response, NOW, NOW.plusSeconds(25));
    gateway.reject(saved, TagoArrivalException.Code.EMPTY_RESULT);

    ArgumentCaptor<SnapshotSaveCommand> command =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(store).save(command.capture());
    assertThat(command.getValue().decompressedPayload()).containsExactly(EXACT);
    assertThat(command.getValue().scope().provider()).isEqualTo(KEY.provider());
    assertThat(command.getValue().scope().service()).isEqualTo(KEY.service());
    assertThat(command.getValue().scope().scopeKey())
        .isEqualTo(TagoArrivalImportSessionAdapter.scopeKey(KEY));
    assertThat(saved.payloadHash()).isEqualTo("b".repeat(64));
    verify(store).transition(any(SnapshotTransitionCommand.class));
  }

  @Test
  void committer는_snapshot_parsed와_normalized_rows와_run_success를_한_transaction_order로_완료한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    TagoArrivalRepository repository = mock(TagoArrivalRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    when(repository.append(any())).thenReturn(1);
    TransactionalTagoArrivalCommitter committer =
        new TransactionalTagoArrivalCommitter(snapshots, repository, runs);
    TagoArrivalCommitCommand command = command();

    assertThat(committer.commit(command).insertedCount()).isEqualTo(1);

    var order = inOrder(snapshots, repository, runs);
    order.verify(snapshots).transition(any());
    order.verify(repository).append(command);
    order.verify(runs).succeed(eqLease(), any());
  }

  @Test
  void normalized_insert가_실패하면_run_success를_기록하지_않는다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    TagoArrivalRepository repository = mock(TagoArrivalRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    when(repository.append(any())).thenThrow(new IllegalStateException("fixture db failure"));
    TransactionalTagoArrivalCommitter committer =
        new TransactionalTagoArrivalCommitter(snapshots, repository, runs);

    assertThatThrownBy(() -> committer.commit(command())).isInstanceOf(IllegalStateException.class);
    verify(runs, never()).succeed(any(), any());
  }

  private static ImportRunLease eqLease() {
    return org.mockito.ArgumentMatchers.eq(LEASE);
  }

  private static TagoArrivalCommitCommand command() {
    TagoArrivalSourceResponse response =
        new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON);
    SavedTagoArrivalSnapshot saved =
        new SavedTagoArrivalSnapshot(
            response,
            SNAPSHOT,
            "a".repeat(64),
            NOW,
            NOW.plusSeconds(25),
            false,
            SnapshotStatus.RECEIVED);
    return new TagoArrivalCommitCommand(
        LEASE,
        KEY,
        List.of(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4)),
        saved,
        NOW,
        NOW.plusSeconds(25));
  }
}
