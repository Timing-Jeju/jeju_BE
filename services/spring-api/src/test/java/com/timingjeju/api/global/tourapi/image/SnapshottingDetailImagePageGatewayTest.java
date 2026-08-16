package com.timingjeju.api.global.tourapi.image;

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
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class SnapshottingDetailImagePageGatewayTest {
  private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");
  private static final UUID RUN = UUID.fromString("29000000-0000-0000-0000-000000000029");
  private static final UUID SNAPSHOT = UUID.fromString("29000000-0000-0000-0001-000000000029");

  @Test
  void raw_page와_page_specific_request를_snapshot으로_고정한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    byte[] raw = "{\"pageNo\":2}".getBytes(StandardCharsets.UTF_8);
    when(snapshots.save(any())).thenReturn(result(false, SnapshotStatus.RECEIVED));
    var gateway =
        new SnapshottingDetailImagePageGateway(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));

    var saved =
        gateway.save(RUN, "100", 2, new DetailSourceResponse(raw, SnapshotPayloadFormat.JSON));

    ArgumentCaptor<SnapshotSaveCommand> command =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(snapshots).save(command.capture());
    assertThat(command.getValue().scope().operation()).isEqualTo("detailImage2");
    assertThat(command.getValue().scope().scopeKey()).isEqualTo("content:100");
    assertThat(command.getValue().pageKey()).isEqualTo("2");
    assertThat(command.getValue().requestMetadata())
        .containsEntry("endpoint", "/detailImage2")
        .containsEntry("imageYN", "Y")
        .containsEntry("subImageYN", "Y")
        .containsEntry("numOfRows", "100");
    assertThat(command.getValue().decompressedPayload()).isEqualTo(raw);
    assertThat(saved.lineage().snapshotId()).isEqualTo(SNAPSHOT);
  }

  @Test
  void true_replay는_persisted_fetchedAt과_terminal_status를_사용하고_transition을_반복하지_않는다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    when(snapshots.save(any())).thenReturn(result(true, SnapshotStatus.PARSED));
    var gateway = new SnapshottingDetailImagePageGateway(snapshots, clock);

    var saved =
        gateway.save(
            RUN,
            "100",
            1,
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON));
    gateway.markParsed(saved);

    assertThat(saved.fetchedAt()).isEqualTo(NOW);
    verify(snapshots, times(0)).transition(any());
  }

  @Test
  void received만_parsed로_전이한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    when(snapshots.save(any())).thenReturn(result(false, SnapshotStatus.RECEIVED));
    var gateway =
        new SnapshottingDetailImagePageGateway(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));
    var saved =
        gateway.save(
            RUN,
            "100",
            1,
            new DetailSourceResponse(
                "{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON));

    gateway.markParsed(saved);

    verify(snapshots)
        .transition(new SnapshotTransitionCommand(SNAPSHOT, SnapshotStatus.PARSED, null));
  }

  private static SnapshotSaveResult result(boolean replayed, SnapshotStatus status) {
    return new SnapshotSaveResult(SNAPSHOT, "a".repeat(64), "b".repeat(64), replayed, NOW, status);
  }
}
