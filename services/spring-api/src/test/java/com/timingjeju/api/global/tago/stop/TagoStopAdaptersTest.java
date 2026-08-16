package com.timingjeju.api.global.tago.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunFailure;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStopCommitCommand;
import com.timingjeju.api.application.tago.stop.TagoStopImportCommand;
import com.timingjeju.api.application.tago.stop.TagoStopPageLineage;
import com.timingjeju.api.application.tago.stop.TagoStopRepository;
import com.timingjeju.api.application.tago.stop.TagoStopSourceResponse;
import com.timingjeju.api.application.tago.stop.TagoStopWrite;
import com.timingjeju.api.application.tago.stop.TagoStopWriteResult;
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
class TagoStopAdaptersTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID RUN = UUID.fromString("35000000-0000-0000-0000-000000000101");
  private static final UUID OWNER = UUID.fromString("35000000-0000-0000-0000-000000000102");
  private static final UUID CITY = UUID.fromString("35000000-0000-0000-0000-000000000103");
  private static final UUID STATION = UUID.fromString("35000000-0000-0000-0000-000000000104");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 1);

  @Test
  void session은_checkpoint와_run_replay를_검증하고_failure를_분류한다() {
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    ImportCheckpoint checkpoint = checkpoint(RUN, 7, "39");
    when(checkpoints.find(TagoStopImportSessionAdapter.SCOPE)).thenReturn(Optional.of(checkpoint));
    when(runs.start(any()))
        .thenReturn(
            new ImportRunStartResult(
                LEASE, false, ImportRunExecutionStatus.RUNNING, ImportRunCounts.zero()))
        .thenReturn(
            new ImportRunStartResult(
                LEASE,
                true,
                ImportRunExecutionStatus.SUCCEEDED,
                new ImportRunCounts(9, 3, 4, 2, 3, 0, 0, 0)));
    TagoStopImportSessionAdapter adapter = new TagoStopImportSessionAdapter(runs, checkpoints);

    assertThat(adapter.start(new TagoStopImportCommand("issue-35-new")).replayed()).isFalse();
    var replay = adapter.start(new TagoStopImportCommand("issue-35-replay"));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.cityCode()).isEqualTo("39");

    adapter.fail(LEASE);
    verify(runs).fail(LEASE, ImportRunFailure.INVALID_PROVIDER_RESPONSE);
  }

  @Test
  void snapshot_gateway는_raw_city와_station_page를_저장하고_parse_state를_전이한다() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any()))
        .thenReturn(
            new SnapshotSaveResult(
                CITY, "a".repeat(64), "b".repeat(64), false, NOW, SnapshotStatus.RECEIVED),
            new SnapshotSaveResult(
                STATION, "c".repeat(64), "d".repeat(64), false, NOW, SnapshotStatus.RECEIVED));
    SnapshottingTagoStopGateway gateway =
        new SnapshottingTagoStopGateway(store, Clock.fixed(NOW, ZoneOffset.UTC));
    TagoStopSourceResponse response =
        new TagoStopSourceResponse(
            "fixture".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);

    var city = gateway.saveCity(RUN, response);
    var station = gateway.saveStations(RUN, "39", 2, response);
    gateway.markParsed(city);
    gateway.markRejected(station);

    assertThat(city.snapshotId()).isEqualTo(CITY);
    assertThat(station.pageNo()).isEqualTo(2);
    ArgumentCaptor<SnapshotTransitionCommand> transitions =
        ArgumentCaptor.forClass(SnapshotTransitionCommand.class);
    verify(store, org.mockito.Mockito.times(2)).transition(transitions.capture());
    assertThat(transitions.getAllValues())
        .extracting(SnapshotTransitionCommand::targetStatus)
        .containsExactly(SnapshotStatus.PARSED, SnapshotStatus.REJECTED);
  }

  @Test
  void committer는_normalized_run_checkpoint를_한_transaction_command로_연결한다() {
    TagoStopRepository repository = mock(TagoStopRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    when(repository.apply(any(), any(), any(), any(), any(), any()))
        .thenReturn(new TagoStopWriteResult(1, 0, 0, 2));
    when(checkpoints.advance(any())).thenReturn(checkpoint(RUN, 8, "39"));
    TransactionalTagoStopCommitter committer =
        new TransactionalTagoStopCommitter(repository, runs, checkpoints);
    TagoStopPageLineage city = lineage("city", 0, CITY, NOW);
    TagoStopPageLineage station = lineage("station", 1, STATION, NOW.plusSeconds(1));
    TagoStopWrite write =
        new TagoStopWrite(
            new TagoStation("39", "NODE-1", "101", "정류장", 126.5, 33.5),
            STATION,
            RUN,
            NOW.plusSeconds(1));

    var result =
        committer.commit(
            new TagoStopCommitCommand(
                LEASE,
                7,
                new TagoCityCode("39", "제주특별자치도"),
                List.of(write),
                List.of(city, station)));

    assertThat(result.checkpointVersion()).isEqualTo(8);
    assertThat(result.counts().insertedCount()).isEqualTo(1);
    assertThat(result.counts().staledCount()).isEqualTo(2);
    verify(runs).succeed(LEASE, result.counts());
    verify(checkpoints).advance(any());
  }

  private static ImportCheckpoint checkpoint(UUID run, long version, String cityCode) {
    return new ImportCheckpoint(
        TagoStopImportSessionAdapter.SCOPE, Map.of("cityCode", cityCode), NOW, run, version, NOW);
  }

  private static TagoStopPageLineage lineage(
      String kind, int pageNo, UUID snapshot, Instant fetchedAt) {
    return new TagoStopPageLineage(kind, pageNo, 1, snapshot, "e".repeat(64), fetchedAt);
  }
}
