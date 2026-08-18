package com.timingjeju.api.global.tourapi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCursor;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSourceResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class SnapshottingIncrementalSyncGatewayTest {
  private static final Instant CURSOR = Instant.parse("2026-08-16T00:00:00Z");
  private static final Instant FETCHED = Instant.parse("2026-08-16T03:00:00Z");
  private static final UUID RUN = UUID.fromString("30000000-0000-0000-0000-000000000011");
  private static final UUID SNAPSHOT = UUID.fromString("30000000-0000-0000-0000-000000000012");

  @Test
  void cursor_page와_delete를_포함한_raw_response를_redacted_snapshot으로_고정한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    byte[] raw = "{\"showflag\":\"0\"}".getBytes(StandardCharsets.UTF_8);
    when(snapshots.save(any())).thenReturn(result(false, SnapshotStatus.RECEIVED));
    var gateway =
        new SnapshottingIncrementalSyncGateway(snapshots, Clock.fixed(FETCHED, ZoneOffset.UTC));

    var saved =
        gateway.save(
            RUN,
            new IncrementalSyncCursor(CURSOR),
            2,
            new IncrementalSyncSourceResponse(raw, SnapshotPayloadFormat.JSON));

    ArgumentCaptor<SnapshotSaveCommand> command =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(snapshots).save(command.capture());
    assertThat(command.getValue().scope().operation()).isEqualTo("areaBasedSyncList2");
    assertThat(command.getValue().scope().scopeKey()).isEqualTo("jeju");
    assertThat(command.getValue().pageKey()).isEqualTo("2");
    assertThat(command.getValue().requestMetadata())
        .containsEntry("endpoint", "areaBasedSyncList2")
        .containsEntry("modifiedtime", CURSOR.toString())
        .containsEntry("lDongRegnCd", "50")
        .containsEntry("numOfRows", "100");
    assertThat(command.getValue().decompressedPayload()).isEqualTo(raw);
    assertThat(saved.lineage().snapshotId()).isEqualTo(SNAPSHOT);
  }

  @Test
  void terminal_parsed_true_replay는_persisted_fetchedAt을_사용하고_transition을_반복하지_않는다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    when(snapshots.save(any())).thenReturn(result(true, SnapshotStatus.PARSED));
    var gateway =
        new SnapshottingIncrementalSyncGateway(
            snapshots, Clock.fixed(FETCHED.plusSeconds(60), ZoneOffset.UTC));

    var saved =
        gateway.save(
            RUN,
            new IncrementalSyncCursor(CURSOR),
            1,
            new IncrementalSyncSourceResponse(new byte[0], SnapshotPayloadFormat.JSON));
    gateway.markParsed(saved);

    assertThat(saved.fetchedAt()).isEqualTo(FETCHED);
    verify(snapshots, times(0)).transition(any());
  }

  @Test
  void received만_parsed로_전이한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    when(snapshots.save(any())).thenReturn(result(false, SnapshotStatus.RECEIVED));
    var gateway =
        new SnapshottingIncrementalSyncGateway(snapshots, Clock.fixed(FETCHED, ZoneOffset.UTC));
    var saved =
        gateway.save(
            RUN,
            new IncrementalSyncCursor(CURSOR),
            1,
            new IncrementalSyncSourceResponse(new byte[0], SnapshotPayloadFormat.JSON));

    gateway.markParsed(saved);

    verify(snapshots)
        .transition(new SnapshotTransitionCommand(SNAPSHOT, SnapshotStatus.PARSED, null));
  }

  private static SnapshotSaveResult result(boolean replayed, SnapshotStatus status) {
    return new SnapshotSaveResult(
        SNAPSHOT, "a".repeat(64), "b".repeat(64), replayed, FETCHED, status);
  }
}
