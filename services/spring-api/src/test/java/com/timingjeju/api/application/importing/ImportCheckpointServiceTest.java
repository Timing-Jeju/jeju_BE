package com.timingjeju.api.application.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

  @Test
  void checkpoint는_원본과_반환된_nested_tree의_변경을_허용하지_않는다() {
    List<Object> mutableItems = new ArrayList<>(List.of("first"));
    Map<String, Object> mutableNested = new LinkedHashMap<>();
    mutableNested.put("items", mutableItems);
    Map<String, Object> mutableRoot = new LinkedHashMap<>();
    mutableRoot.put("nested", mutableNested);

    ImportCheckpointAdvanceCommand command =
        new ImportCheckpointAdvanceCommand(
            SCOPE, 0, mutableRoot, null, RUN_ID, ImportRunStatus.SUCCEEDED);
    ImportCheckpoint checkpoint =
        new ImportCheckpoint(
            SCOPE, mutableRoot, null, RUN_ID, 1, Instant.parse("2026-08-14T00:00:01Z"));
    mutableItems.add("mutated");
    mutableNested.put("late", true);
    mutableRoot.put("late", true);

    assertThat(command.checkpoint()).doesNotContainKey("late");
    assertThat(checkpoint.checkpoint()).doesNotContainKey("late");
    assertThat(nestedItems(command.checkpoint())).containsExactly("first");
    assertThat(nestedItems(checkpoint.checkpoint())).containsExactly("first");
    assertThatThrownBy(() -> nestedMap(checkpoint.checkpoint()).put("blocked", true))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> nestedItems(checkpoint.checkpoint()).add("blocked"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void checkpoint는_JSON과_jsonb가_공통으로_지원하는_tree만_허용한다() {
    Map<String, Object> valid = new LinkedHashMap<>();
    valid.put("null", null);
    valid.put("boolean", true);
    valid.put("string", "제주");
    valid.put("integer", 3);
    valid.put("long", Long.MAX_VALUE);
    valid.put("bigInteger", new BigInteger("123456789012345678901234567890"));
    valid.put("decimal", new BigDecimal("123.4500"));
    valid.put("float", 0.25F);
    valid.put("double", 0.125D);
    valid.put("array", List.of(Map.of("page", 2), false));

    ImportCheckpointAdvanceCommand command =
        new ImportCheckpointAdvanceCommand(
            SCOPE, 0, valid, null, RUN_ID, ImportRunStatus.SUCCEEDED);

    assertThat(command.checkpoint())
        .containsEntry("decimal", new BigDecimal("123.4500"))
        .containsEntry("float", new BigDecimal("0.25"))
        .containsEntry("double", new BigDecimal("0.125"));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void checkpoint는_비문자열_key와_비JSON_값_및_지원범위밖_number를_도메인오류로_거부한다() {
    Map nonStringOuterKey = new LinkedHashMap();
    nonStringOuterKey.put(1, "value");
    Map nonStringNestedKey = Map.of("nested", nonStringOuterKey);
    Number arbitraryNumber =
        new Number() {
          @Override
          public int intValue() {
            return 1;
          }

          @Override
          public long longValue() {
            return 1;
          }

          @Override
          public float floatValue() {
            return 1;
          }

          @Override
          public double doubleValue() {
            return 1;
          }
        };
    BigInteger tooWideInteger = BigInteger.TEN.pow(131_072);
    BigDecimal tooPreciseDecimal = new BigDecimal("1e-16384");

    for (Map<String, Object> invalid :
        List.of(
            (Map<String, Object>) nonStringOuterKey,
            nonStringNestedKey,
            Map.of("value", UUID.randomUUID()),
            Map.of("value", Instant.EPOCH),
            Map.of("value", new Object()),
            Map.of("value", Double.NaN),
            Map.of("value", Double.POSITIVE_INFINITY),
            Map.of("value", Double.NEGATIVE_INFINITY),
            Map.of("value", Float.NaN),
            Map.of("value", Float.POSITIVE_INFINITY),
            Map.of("value", Float.NEGATIVE_INFINITY),
            Map.of("value", arbitraryNumber),
            Map.of("value", tooWideInteger),
            Map.of("value", tooPreciseDecimal))) {
      assertThatThrownBy(
              () ->
                  new ImportCheckpointAdvanceCommand(
                      SCOPE, 0, invalid, null, RUN_ID, ImportRunStatus.SUCCEEDED))
          .isInstanceOf(ImportCheckpointException.class)
          .extracting("code")
          .isEqualTo(ImportCheckpointError.INVALID_CHECKPOINT);
      assertThatThrownBy(
              () ->
                  new ImportCheckpoint(
                      SCOPE, invalid, null, RUN_ID, 0, Instant.parse("2026-08-14T00:00:01Z")))
          .isInstanceOf(ImportCheckpointException.class)
          .extracting("code")
          .isEqualTo(ImportCheckpointError.INVALID_CHECKPOINT);
    }
  }

  private static ImportCheckpointAdvanceCommand command(ImportRunStatus status) {
    return new ImportCheckpointAdvanceCommand(
        SCOPE, 7, Map.of("page", 3), Instant.parse("2026-08-14T00:00:00Z"), RUN_ID, status);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedMap(Map<String, Object> checkpoint) {
    return (Map<String, Object>) checkpoint.get("nested");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> nestedItems(Map<String, Object> checkpoint) {
    return (List<Object>) nestedMap(checkpoint).get("items");
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
