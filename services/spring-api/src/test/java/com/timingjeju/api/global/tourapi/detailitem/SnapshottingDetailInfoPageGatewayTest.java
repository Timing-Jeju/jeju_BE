package com.timingjeju.api.global.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        .thenReturn(
            new SnapshotSaveResult(
                snapshotId, "a".repeat(64), "b".repeat(64), false, NOW, SnapshotStatus.RECEIVED));
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
        .containsEntry("endpoint", "detailInfo2")
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

    gateway.markParsed(savedPage(parsed, false, SnapshotStatus.RECEIVED));
    gateway.markRejected(savedPage(rejected, false, SnapshotStatus.RECEIVED));

    verify(snapshots)
        .transition(new SnapshotTransitionCommand(parsed, SnapshotStatus.PARSED, null));
    verify(snapshots)
        .transition(
            new SnapshotTransitionCommand(
                rejected, SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
  }

  @Test
  void 같은_run_page_payload_replay는_최초_snapshot_fetchedAt을_유지한다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    Clock clock = mock(Clock.class);
    UUID runId = UUID.fromString("28000000-0000-0000-0000-000000000031");
    UUID snapshotId = UUID.fromString("28000000-0000-0000-0001-000000000031");
    byte[] raw = "{\"pageNo\":1}".getBytes(StandardCharsets.UTF_8);
    when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(5));
    when(snapshots.save(any()))
        .thenReturn(
            snapshotResult(snapshotId, false, SnapshotStatus.RECEIVED),
            snapshotResult(snapshotId, true, SnapshotStatus.RECEIVED));
    var gateway = new SnapshottingDetailInfoPageGateway(snapshots, clock);
    DetailSourceResponse response = new DetailSourceResponse(raw, SnapshotPayloadFormat.JSON);

    var first = gateway.save(runId, "100", "12", 1, response);
    var replay = gateway.save(runId, "100", "12", 1, response);

    assertThat(replay.lineage()).isEqualTo(first.lineage());
    assertThat(replay.fetchedAt()).isEqualTo(first.fetchedAt());
  }

  @Test
  void parsed_terminal_snapshot_replay는_parsed_transition을_다시_시도하지_않는다() {
    SnapshotStoreService snapshots = mock(SnapshotStoreService.class);
    UUID runId = UUID.fromString("28000000-0000-0000-0000-000000000032");
    UUID snapshotId = UUID.fromString("28000000-0000-0000-0001-000000000032");
    byte[] raw = "{\"pageNo\":1}".getBytes(StandardCharsets.UTF_8);
    when(snapshots.save(any()))
        .thenReturn(
            snapshotResult(snapshotId, false, SnapshotStatus.RECEIVED),
            snapshotResult(snapshotId, true, SnapshotStatus.PARSED));
    var gateway =
        new SnapshottingDetailInfoPageGateway(snapshots, Clock.fixed(NOW, ZoneOffset.UTC));
    DetailSourceResponse response = new DetailSourceResponse(raw, SnapshotPayloadFormat.JSON);

    var first = gateway.save(runId, "100", "12", 1, response);
    gateway.markParsed(first);
    var replay = gateway.save(runId, "100", "12", 1, response);
    gateway.markParsed(replay);

    verify(snapshots, times(1))
        .transition(new SnapshotTransitionCommand(snapshotId, SnapshotStatus.PARSED, null));
  }

  private static SnapshotSaveResult snapshotResult(
      UUID snapshotId, boolean replayed, SnapshotStatus status) {
    return new SnapshotSaveResult(
        snapshotId, "a".repeat(64), "b".repeat(64), replayed, NOW, status);
  }

  private static com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage savedPage(
      UUID snapshotId, boolean replayed, SnapshotStatus status) {
    return new com.timingjeju.api.application.tourapi.detailitem.SavedDetailInfoPage(
        new DetailSourceResponse("{}".getBytes(StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON),
        1,
        "b".repeat(64),
        NOW,
        new com.timingjeju.api.application.tourapi.detailitem.DetailItemLineage(
            "detailInfo2", "a".repeat(64), snapshotId, UUID.randomUUID()),
        replayed,
        status);
  }
}
