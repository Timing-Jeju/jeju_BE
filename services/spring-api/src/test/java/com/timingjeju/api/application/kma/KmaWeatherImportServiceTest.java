package com.timingjeju.api.application.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunExecutionStatus;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@Tag("unit")
class KmaWeatherImportServiceTest {

  private final KmaWeatherSource source = mock(KmaWeatherSource.class);
  private final KmaWeatherSnapshotGateway snapshots = mock(KmaWeatherSnapshotGateway.class);
  private final KmaWeatherParser parser = mock(KmaWeatherParser.class);
  private final ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
  private final ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
  private final KmaWeatherCommitter committer = mock(KmaWeatherCommitter.class);
  private final ImportRunLease lease = new ImportRunLease(UUID.randomUUID(), UUID.randomUUID(), 1L);
  private final UUID gridPointId = UUID.randomUUID();
  private final UUID snapshotId = UUID.randomUUID();
  private final KmaWeatherImportService service =
      new KmaWeatherImportService(
          source,
          snapshots,
          parser,
          checkpoints,
          runs,
          committer,
          new KmaWeatherBaseTimeResolver(
              Clock.fixed(Instant.parse("2026-08-15T15:45:00Z"), ZoneOffset.UTC)));

  @BeforeEach
  void setUp() {
    when(checkpoints.find(any())).thenReturn(Optional.empty());
    when(runs.start(any())).thenReturn(new ImportRunStartResult(lease, false));
    when(snapshots.capture(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new SavedKmaWeatherSnapshot(
                    invocation.getArgument(4),
                    snapshotId,
                    "request-hash",
                    "payload-hash",
                    Instant.parse("2026-08-15T15:45:01Z"),
                    false,
                    SnapshotStatus.RECEIVED));
    when(parser.parse(any(), any()))
        .thenAnswer(
            invocation ->
                new String(
                            (byte[]) invocation.getArgument(1),
                            java.nio.charset.StandardCharsets.UTF_8)
                        .contains("previous")
                    ? batchAt("2026-08-15T14:30:00Z")
                    : batchAt("2026-08-15T15:30:00Z"));
    when(committer.commit(any()))
        .thenReturn(new KmaWeatherCommitResult(new ImportRunCounts(6, 1, 1, 0, 0, 0, 0, 0), 1));
  }

  @Test
  void savesRawSnapshotBeforeParsingThenCommitsLineageAndCheckpointCas() {
    when(source.fetch(any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(response("fresh"));

    KmaWeatherImportResult result =
        service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST);

    InOrder order = inOrder(source, snapshots, parser, committer);
    order.verify(source).fetch(any(), any(), any(Integer.class), any(Integer.class));
    order.verify(snapshots).capture(any(), any(), any(), any(), any());
    order.verify(parser).parse(any(), any());
    order.verify(snapshots).markParsed(any());
    order.verify(committer).commit(any());
    ArgumentCaptor<KmaWeatherCommitCommand> captured =
        ArgumentCaptor.forClass(KmaWeatherCommitCommand.class);
    verify(committer).commit(captured.capture());
    assertThat(captured.getValue().expectedCheckpointVersion()).isZero();
    assertThat(captured.getValue().lineage().snapshotId()).isEqualTo(snapshotId);
    assertThat(captured.getValue().lineage().importRunId()).isEqualTo(lease.runId());
    assertThat(result.freshness()).isEqualTo(KmaWeatherFreshness.FRESH);
  }

  @Test
  void fallsBackToExactlyOnePreviousBaseAndRecordsStaleWarning() {
    when(source.fetch(any(), any(), any(Integer.class), any(Integer.class)))
        .thenThrow(KmaWeatherImportException.providerUnavailable())
        .thenReturn(response("previous"));

    KmaWeatherImportResult result =
        service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST);

    ArgumentCaptor<ForecastBaseTime> bases = ArgumentCaptor.forClass(ForecastBaseTime.class);
    verify(source, times(2)).fetch(any(), bases.capture(), any(Integer.class), any(Integer.class));
    assertThat(bases.getAllValues())
        .containsExactly(base("2026-08-16", "00:30"), base("2026-08-15", "23:30"));
    assertThat(result.freshness()).isEqualTo(KmaWeatherFreshness.STALE_WEATHER_DATA);
    ArgumentCaptor<KmaWeatherCommitCommand> committed =
        ArgumentCaptor.forClass(KmaWeatherCommitCommand.class);
    verify(committer).commit(committed.capture());
    assertThat(committed.getValue().stale()).isTrue();
  }

  @Test
  void stopsAfterPreviousBaseFailureAndDoesNotCommitCheckpoint() {
    when(source.fetch(any(), any(), any(Integer.class), any(Integer.class)))
        .thenThrow(KmaWeatherImportException.providerUnavailable());

    assertThatThrownBy(() -> service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST))
        .isInstanceOf(KmaWeatherImportException.class);

    verify(source, times(2)).fetch(any(), any(), any(Integer.class), any(Integer.class));
    verify(committer, never()).commit(any());
    verify(runs).fail(any(), any());
  }

  @Test
  void rejectsInvalidLatestSnapshotBeforeUsingPreviousBase() {
    when(source.fetch(any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(response("invalid"), response("previous"));
    reset(parser);
    when(parser.parse(any(), any()))
        .thenThrow(KmaWeatherImportException.invalidResponse())
        .thenReturn(batchAt("2026-08-15T14:30:00Z"));

    service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST);

    verify(snapshots).markRejected(any());
    verify(snapshots).markParsed(any());
  }

  @Test
  void replaysSucceededRunOnlyWhenCheckpointPointsToSameRun() {
    ImportRunCounts counts = new ImportRunCounts(6, 1, 0, 0, 1, 0, 0, 0);
    when(runs.start(any()))
        .thenReturn(
            new ImportRunStartResult(lease, true, ImportRunExecutionStatus.SUCCEEDED, counts));
    when(checkpoints.find(any()))
        .thenReturn(
            Optional.of(
                new ImportCheckpoint(
                    scope(),
                    Map.of("baseDateTime", "2026-08-16T00:30+09:00"),
                    Instant.parse("2026-08-15T16:00:00Z"),
                    lease.runId(),
                    4,
                    Instant.parse("2026-08-15T16:00:01Z"))));

    KmaWeatherImportResult result =
        service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST);

    assertThat(result.replayed()).isTrue();
    assertThat(result.checkpointVersion()).isEqualTo(4);
    verify(source, never()).fetch(any(), any(), any(Integer.class), any(Integer.class));
  }

  @Test
  void staleCheckpointCommitMarksRunAsStaleWriterAfterTransactionalRollback() {
    when(source.fetch(any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(response("fresh"));
    when(committer.commit(any()))
        .thenThrow(
            com.timingjeju.api.application.importing.ImportCheckpointException.of(
                com.timingjeju.api.application.importing.ImportCheckpointError.STALE_VERSION));

    assertThatThrownBy(() -> service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST))
        .isInstanceOf(com.timingjeju.api.application.importing.ImportCheckpointException.class);

    verify(runs)
        .fail(lease, com.timingjeju.api.application.importing.ImportRunFailure.STALE_WRITER);
  }

  @Test
  void rejectsSucceededReplayWhenCheckpointBelongsToAnotherRun() {
    when(runs.start(any()))
        .thenReturn(
            new ImportRunStartResult(
                lease, true, ImportRunExecutionStatus.SUCCEEDED, ImportRunCounts.zero()));
    when(checkpoints.find(any()))
        .thenReturn(
            Optional.of(
                new ImportCheckpoint(
                    scope(),
                    Map.of(),
                    Instant.parse("2026-08-15T16:00:00Z"),
                    UUID.randomUUID(),
                    4,
                    Instant.parse("2026-08-15T16:00:01Z"))));

    assertThatThrownBy(() -> service.importWeather(command(), KmaWeatherOperation.ULTRA_FORECAST))
        .isInstanceOf(KmaWeatherImportException.class)
        .extracting(failure -> ((KmaWeatherImportException) failure).code())
        .isEqualTo(KmaWeatherImportError.INVALID_REPLAY);

    verify(source, never()).fetch(any(), any(), any(Integer.class), any(Integer.class));
  }

  private KmaWeatherImportCommand command() {
    return new KmaWeatherImportCommand(gridPointId, 52, 38, "weather-20260816-0030-52-38");
  }

  private static KmaWeatherSourceResponse response(String body) {
    return new KmaWeatherSourceResponse(
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), SnapshotPayloadFormat.JSON);
  }

  private static KmaWeatherBatch batchAt(String forecastedAtValue) {
    Instant forecastedAt = Instant.parse(forecastedAtValue);
    return new KmaWeatherBatch(
        52,
        38,
        6,
        Instant.parse("2026-08-15T16:00:00Z"),
        List.of(),
        List.of(
            new KmaWeatherForecast(
                forecastedAt,
                Instant.parse("2026-08-15T16:00:00Z"),
                "ultra_short",
                "1",
                "0",
                java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("25"),
                70,
                new java.math.BigDecimal("2"))));
  }

  private static ForecastBaseTime base(String date, String time) {
    return new ForecastBaseTime(LocalDate.parse(date), LocalTime.parse(time));
  }

  private static ImportRunScope scope() {
    return new ImportRunScope("kma", "VilageFcstInfoService_2.0", "getUltraSrtFcst", "nx=52;ny=38");
  }
}
