package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportCheckpoint;
import com.timingjeju.api.application.importing.ImportCheckpointAdvanceCommand;
import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.kma.KmaWeatherBatch;
import com.timingjeju.api.application.kma.KmaWeatherCommitCommand;
import com.timingjeju.api.application.kma.KmaWeatherLineage;
import com.timingjeju.api.application.kma.KmaWeatherRepository;
import com.timingjeju.api.application.kma.KmaWeatherUpsertResult;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
class TransactionalKmaWeatherCommitterTest {

  @Test
  void upsertsThenCompletesRunThenAdvancesCheckpointWithExpectedVersion() throws Exception {
    KmaWeatherRepository repository = mock(KmaWeatherRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    when(repository.upsert(any())).thenReturn(new KmaWeatherUpsertResult(1, 0, 0));
    when(checkpoints.advance(any())).thenReturn(checkpoint(8));
    TransactionalKmaWeatherCommitter committer =
        new TransactionalKmaWeatherCommitter(repository, runs, checkpoints);

    var result = committer.commit(command(7, true));

    InOrder order = inOrder(repository, runs, checkpoints);
    order.verify(repository).upsert(any());
    order.verify(runs).succeed(any(), any());
    order.verify(checkpoints).advance(any());
    ArgumentCaptor<ImportCheckpointAdvanceCommand> advance =
        ArgumentCaptor.forClass(ImportCheckpointAdvanceCommand.class);
    verify(checkpoints).advance(advance.capture());
    assertThat(advance.getValue().expectedVersion()).isEqualTo(7);
    assertThat(advance.getValue().lastSucceededRunId()).isEqualTo(command(7, true).lease().runId());
    assertThat(advance.getValue().checkpoint())
        .containsEntry("baseDate", "2026-08-16")
        .containsEntry("baseTime", "00:30")
        .containsEntry("stale", true);
    assertThat(result.checkpointVersion()).isEqualTo(8);
    assertThat(
            TransactionalKmaWeatherCommitter.class
                .getMethod("commit", KmaWeatherCommitCommand.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
  }

  @Test
  void staleCheckpointFailureEscapesTransactionForOuterRunFailureHandling() {
    KmaWeatherRepository repository = mock(KmaWeatherRepository.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    when(repository.upsert(any())).thenReturn(new KmaWeatherUpsertResult(0, 1, 0));
    when(checkpoints.advance(any()))
        .thenThrow(ImportCheckpointException.of(ImportCheckpointError.STALE_VERSION));
    TransactionalKmaWeatherCommitter committer =
        new TransactionalKmaWeatherCommitter(repository, runs, checkpoints);

    assertThatThrownBy(() -> committer.commit(command(7, false)))
        .isInstanceOf(ImportCheckpointException.class);
  }

  private static KmaWeatherCommitCommand command(long version, boolean stale) {
    UUID run = UUID.fromString("43000000-0000-0000-0000-000000000001");
    return new KmaWeatherCommitCommand(
        new ImportRunLease(run, UUID.randomUUID(), 1),
        new ImportRunScope("kma", "VilageFcstInfoService_2.0", "getUltraSrtFcst", "nx=52;ny=38"),
        version,
        UUID.randomUUID(),
        new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(0, 30)),
        new KmaWeatherBatch(52, 38, 6, Instant.parse("2026-08-15T16:00:00Z"), List.of(), List.of()),
        new KmaWeatherLineage("getUltraSrtFcst", "a".repeat(64), UUID.randomUUID(), run),
        Instant.parse("2026-08-15T15:45:01Z"),
        stale);
  }

  private static ImportCheckpoint checkpoint(long version) {
    return new ImportCheckpoint(
        new ImportRunScope("kma", "VilageFcstInfoService_2.0", "getUltraSrtFcst", "nx=52;ny=38"),
        Map.of("baseDate", "2026-08-16"),
        Instant.parse("2026-08-15T16:00:00Z"),
        UUID.randomUUID(),
        version,
        Instant.parse("2026-08-15T16:00:01Z"));
  }
}
