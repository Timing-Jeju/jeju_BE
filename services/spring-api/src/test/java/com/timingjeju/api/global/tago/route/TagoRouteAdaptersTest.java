package com.timingjeju.api.global.tago.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.tago.route.TagoRouteCommitCommand;
import com.timingjeju.api.application.tago.route.TagoRouteImportCommand;
import com.timingjeju.api.application.tago.route.TagoRouteLineage;
import com.timingjeju.api.application.tago.route.TagoRouteRepository;
import com.timingjeju.api.application.tago.route.TagoRouteSourceResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class TagoRouteAdaptersTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID RUN = UUID.fromString("36000000-0000-0000-0000-000000000101");
  private static final UUID OWNER = UUID.fromString("36000000-0000-0000-0000-000000000102");
  private static final UUID SNAPSHOT = UUID.fromString("36000000-0000-0000-0000-000000000103");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 2);

  @Test
  void session은_route_selection을_fingerprint에_포함하고_succeeded_replay만_허용한다() {
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    when(checkpoints.find(TagoRouteImportSessionAdapter.SCOPE))
        .thenReturn(Optional.of(checkpoint(RUN, 4)));
    when(runs.start(any()))
        .thenReturn(
            new ImportRunStartResult(
                LEASE,
                true,
                ImportRunExecutionStatus.SUCCEEDED,
                new ImportRunCounts(4, 8, 2, 2, 0, 0, 0, 0)));

    var result =
        new TagoRouteImportSessionAdapter(runs, checkpoints)
            .start(new TagoRouteImportCommand("issue-36-replay", List.of("101", "201")));

    assertThat(result.replayed()).isTrue();
    ArgumentCaptor<com.timingjeju.api.application.importing.ImportRunStartCommand> command =
        ArgumentCaptor.forClass(
            com.timingjeju.api.application.importing.ImportRunStartCommand.class);
    verify(runs).start(command.capture());
    assertThat(command.getValue().requestFingerprint()).hasSize(64);
    assertThat(command.getValue().scope()).isEqualTo(TagoRouteImportSessionAdapter.SCOPE);
  }

  @Test
  void snapshot은_각_원문_응답의_공식_operation을_source_scope에_기록하고_credential을_남기지_않는다() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any()))
        .thenReturn(
            new SnapshotSaveResult(
                SNAPSHOT, "a".repeat(64), "b".repeat(64), false, NOW, SnapshotStatus.RECEIVED));
    SnapshottingTagoRouteGateway gateway =
        new SnapshottingTagoRouteGateway(store, Clock.fixed(NOW, ZoneOffset.UTC));

    TagoRouteSourceResponse response =
        new TagoRouteSourceResponse(
            "fixture".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
    gateway.save(RUN, "route-list", "39", "101", 1, response);
    gateway.save(RUN, "route-detail", "39", "JEB405410111", 0, response);
    gateway.save(RUN, "route-stops", "39", "JEB405410111", 2, response);

    ArgumentCaptor<SnapshotSaveCommand> command =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(store, org.mockito.Mockito.times(3)).save(command.capture());
    assertThat(command.getAllValues())
        .extracting(saved -> saved.scope().operation())
        .containsExactly("getRouteNoList", "getRouteInfoIem", "getRouteAcctoThrghSttnList");
    assertThat(command.getAllValues().get(2).requestMetadata())
        .containsEntry("endpoint", "/getRouteAcctoThrghSttnList")
        .containsEntry("cityCode", "39")
        .doesNotContainKeys("serviceKey", "apiKey", "Authorization", "url");
  }

  @Test
  void normalized_write가_실패하면_run_success와_checkpoint_CAS를_시도하지_않는다() {
    TagoRouteRepository repository = mock(TagoRouteRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    when(repository.apply(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("write failed"));
    TransactionalTagoRouteCommitter committer =
        new TransactionalTagoRouteCommitter(repository, runs, checkpoints);
    TagoRouteLineage lineage =
        new TagoRouteLineage("route-detail", "R", 0, 1, SNAPSHOT, "c".repeat(64), NOW);

    assertThatThrownBy(
            () ->
                committer.commit(
                    new TagoRouteCommitCommand(LEASE, 4, List.of(), List.of(), List.of(lineage))))
        .isInstanceOf(IllegalStateException.class);

    verify(runs, never()).succeed(any(), any());
    verify(checkpoints, never()).advance(any());
  }

  private static ImportCheckpoint checkpoint(UUID run, long version) {
    return new ImportCheckpoint(
        TagoRouteImportSessionAdapter.SCOPE,
        Map.of("routeCount", 4, "routeStopCount", 8),
        NOW,
        run,
        version,
        NOW);
  }
}
