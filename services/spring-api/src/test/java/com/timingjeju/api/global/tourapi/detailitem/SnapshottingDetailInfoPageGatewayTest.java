package com.timingjeju.api.global.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.snapshot.SnapshotFailure;
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
class SnapshottingDetailInfoPageGatewayTest {
  private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");

  @Test
  void raw_page를_page_specific_snapshot_command로_저장하고_그_exact_bytes와_lineage를_반환한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    UUID runId = UUID.fromString("28000000-0000-0000-0000-000000000028");
    UUID snapshotId = UUID.fromString("28000000-0000-0000-0001-000000000028");
    byte[] raw = "{\"pageNo\":2}".getBytes(StandardCharsets.UTF_8);
    when(snapshots.save(any()))
        .thenReturn(new SnapshotSaveResult(snapshotId, "a".repeat(64), "b".repeat(64), false));
    var gateway =
        new SnapshottingDetailInfoPageGateway(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));

    var saved =
        gateway.save(
            runId, "100", "12", 2, new DetailSourceResponse(raw, SnapshotPayloadFormat.JSON));

    ArgumentCaptor<SnapshotSaveCommand> command =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(snapshots).save(command.capture());
    assertThat(command.getValue().importRunId()).isEqualTo(runId);
    assertThat(command.getValue().scope().scopeKey()).isEqualTo("content:100");
    assertThat(command.getValue().pageKey()).isEqualTo("2");
    assertThat(command.getValue().requestMetadata())
        .containsEntry("endpoint", "/detailInfo2")
        .containsEntry("contentId", "100")
        .containsEntry("contentTypeId", "12")
        .containsEntry("numOfRows", "100");
    assertThat(command.getValue().decompressedPayload()).isEqualTo(raw);
    assertThat(saved.storedResponse().payload()).isEqualTo(raw);
    assertThat(saved.lineage().snapshotId()).isEqualTo(snapshotId);
    assertThat(saved.lineage().requestFingerprint()).isEqualTo("a".repeat(64));
    assertThat(saved.payloadHash()).isEqualTo("b".repeat(64));
  }

  @Test
  void parse_result를_snapshot_status로_전이한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    UUID parsed = UUID.fromString("28000000-0000-0000-0001-000000000029");
    UUID rejected = UUID.fromString("28000000-0000-0000-0001-000000000030");
    var gateway =
        new SnapshottingDetailInfoPageGateway(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));

    gateway.markParsed(parsed);
    gateway.markRejected(rejected);

    verify(snapshots)
        .transition(new SnapshotTransitionCommand(parsed, SnapshotStatus.PARSED, null));
    verify(snapshots)
        .transition(
            new SnapshotTransitionCommand(
                rejected, SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }
}
