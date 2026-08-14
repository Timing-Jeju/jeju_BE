package com.timingjeju.api.application.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ImportCheckpointServiceTest {

  private static final ImportRunScope SCOPE =
      new ImportRunScope("KTO", "TourAPI", "areaBasedSyncList2", "jeju");
  private static final UUID RUN_ID = UUID.fromString("24000000-0000-0000-0000-000000000001");

  @Test
  void succeeded_run만_repository_CAS로_전달한다() {
    RecordingRepository repository = new RecordingRepository();
    ImportCheckpointService service = new ImportCheckpointService(repository);
    ImportCheckpointAdvanceCommand command = command(ImportRunStatus.SUCCEEDED);

    ImportCheckpoint advanced = service.advance(command);

    assertThat(repository.lastCommand).isEqualTo(command);
    assertThat(advanced.version()).isEqualTo(8);
  }

  @Test
  void failed와_partial_run은_checkpoint를_전진시키지_않는다() {
    RecordingRepository repository = new RecordingRepository();
    ImportCheckpointService service = new ImportCheckpointService(repository);

    for (ImportRunStatus status :
        java.util.List.of(ImportRunStatus.FAILED, ImportRunStatus.PARTIAL)) {
      assertThatThrownBy(() -> service.advance(command(status)))
          .isInstanceOf(ImportCheckpointException.class)
          .extracting("code")
          .isEqualTo(ImportCheckpointError.RUN_NOT_SUCCEEDED);
    }
    assertThat(repository.lastCommand).isNull();
  }

  @Test
  void checkpoint_error는_retryable_분류와_안전한_예외표현을_제공한다() {
    ImportCheckpointException failure =
        ImportCheckpointException.of(ImportCheckpointError.STALE_VERSION);

    assertThat(failure.code()).isEqualTo(ImportCheckpointError.STALE_VERSION);
    assertThat(failure.retryable()).isTrue();
    assertThat(failure.getMessage()).doesNotContain("SQL", "checkpoint compare-and-set");
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getSuppressed()).isEmpty();
  }

  @Test
  void expected_version의_최솟값과_최댓값_및_필수값을_검증한다() {
    assertThatThrownBy(
            () ->
                new ImportCheckpointAdvanceCommand(
                    SCOPE, -1, Map.of(), null, RUN_ID, ImportRunStatus.SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new ImportCheckpointAdvanceCommand(
                    SCOPE, Long.MAX_VALUE, Map.of(), null, RUN_ID, ImportRunStatus.SUCCEEDED)
                .expectedVersion())
        .isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(
            () ->
                new ImportCheckpointAdvanceCommand(
                    SCOPE, 0, null, null, RUN_ID, ImportRunStatus.SUCCEEDED))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void checkpoint_value와_service의_필수값을_검증하고_find를_위임한다() {
    Instant updatedAt = Instant.parse("2026-08-14T00:00:01Z");
    ImportCheckpoint stored = new ImportCheckpoint(SCOPE, Map.of(), null, null, 0, updatedAt);
    RecordingRepository repository = new RecordingRepository();
    repository.found = Optional.of(stored);
    ImportCheckpointService service = new ImportCheckpointService(repository);

    assertThat(service.find(SCOPE)).contains(stored);
    assertThat(repository.lastScope).isEqualTo(SCOPE);
    assertThatThrownBy(() -> service.find(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.advance(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ImportCheckpointService(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ImportCheckpoint(SCOPE, Map.of(), null, null, -1, updatedAt))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ImportCheckpoint(SCOPE, Map.of(), null, null, 0, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static ImportCheckpointAdvanceCommand command(ImportRunStatus status) {
    return new ImportCheckpointAdvanceCommand(
        SCOPE, 7, Map.of("page", 3), Instant.parse("2026-08-14T00:00:00Z"), RUN_ID, status);
  }

  private static final class RecordingRepository implements ImportCheckpointRepository {
    private ImportCheckpointAdvanceCommand lastCommand;
    private ImportRunScope lastScope;
    private Optional<ImportCheckpoint> found = Optional.empty();

    @Override
    public Optional<ImportCheckpoint> find(ImportRunScope scope) {
      lastScope = scope;
      return found;
    }

    @Override
    public ImportCheckpoint advance(ImportCheckpointAdvanceCommand command) {
      lastCommand = command;
      return new ImportCheckpoint(
          command.scope(),
          command.checkpoint(),
          command.sourceWatermarkAt(),
          command.lastSucceededRunId(),
          command.expectedVersion() + 1,
          Instant.parse("2026-08-14T00:00:01Z"));
    }
  }
}
