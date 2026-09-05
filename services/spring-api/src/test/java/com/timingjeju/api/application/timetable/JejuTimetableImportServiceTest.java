package com.timingjeju.api.application.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JejuTimetableImportServiceTest {
  private static final byte[] XLSX = {1, 2, 3};
  private static final UUID ROUTE = UUID.fromString("38000000-0000-0000-0000-000000000101");
  private static final UUID STOP = UUID.fromString("38000000-0000-0000-0000-000000000201");

  @Test
  void dry_run은_catalog까지_전부검증하지만_snapshot과_timetable을_쓰지_않는다() {
    FakeStore store = new FakeStore();
    var result = service(store).importXlsx(command(true));
    assertThat(result.acceptedRows()).isEqualTo(1);
    assertThat(result.rejectedRows()).isEmpty();
    assertThat(store.commits).isZero();
    assertThat(store.inspections).isEqualTo(1);
  }

  @Test
  void dry_run은_모든_rejected_row를_결정적순서로_반환하고_쓰기하지_않는다() {
    FakeStore store = new FakeStore();
    ParsedTimetable report =
        new ParsedTimetable(
            "a".repeat(64),
            "{\"parserVersion\":\"jeju-timetable-xlsx-v1\"}",
            parsed().entries(),
            List.of(
                "sheet=OUT row=8 column=2 INVALID_TIME",
                "sheet=IN row=9 column=3 TIME_TYPE_MISMATCH"));
    var service =
        new JejuTimetableImportService(
            command -> report, entries -> TimetableCatalogValidation.VALID, store);

    var result = service.importXlsx(command(true));

    assertThat(result.rejectedRows()).containsExactlyElementsOf(report.rejectedRows());
    assertThat(store.commits).isZero();
  }

  @Test
  void production은_manifest_snapshot_run_timetable을_단일_atomic_commit으로_전달한다() {
    FakeStore store = new FakeStore();
    var result = service(store).importXlsx(command(false));
    assertThat(result.replayed()).isFalse();
    assertThat(store.commits).isEqualTo(1);
    assertThat(store.last.rawXlsx()).isNull();
    assertThat(store.last.source().providerCode()).isEqualTo("JEJU_PROVINCE");
    assertThat(store.last.entries().getFirst().routeSourceProvider()).isEqualTo("TAGO");
  }

  @Test
  void 동일_version_hash는_replay하고_동일_version_다른_hash는_쓰기없이_충돌한다() {
    FakeStore replay = new FakeStore();
    replay.state = TimetableVersionState.REPLAY;
    assertThat(service(replay).importXlsx(command(false)).replayed()).isTrue();
    assertThat(replay.commits).isZero();

    FakeStore conflict = new FakeStore();
    conflict.state = TimetableVersionState.CONFLICT;
    assertThatThrownBy(() -> service(conflict).importXlsx(command(false)))
        .isInstanceOf(TimetableImportException.class)
        .hasMessageContaining("SAME_VERSION_CONFLICT");
    assertThat(conflict.commits).isZero();
  }

  @Test
  void inspect뒤_동시_import가_먼저_commit되면_store의_replay판정을_응답에_보존한다() {
    FakeStore store = new FakeStore();
    store.commitReplay = true;
    var result = service(store).importXlsx(command(false));
    assertThat(result.replayed()).isTrue();
    assertThat(result.writes().inserted()).isZero();
    assertThat(result.writes().skipped()).isEqualTo(1);
  }

  @Test
  void route_direction_stop_catalog의_missing_ambiguous_mismatch는_commit전에_전체실패한다() {
    for (TimetableCatalogValidation validation :
        List.of(
            TimetableCatalogValidation.MISSING,
            TimetableCatalogValidation.AMBIGUOUS,
            TimetableCatalogValidation.MISMATCH)) {
      FakeStore store = new FakeStore();
      var service = new JejuTimetableImportService(c -> parsed(), entries -> validation, store);
      assertThatThrownBy(() -> service.importXlsx(command(false)))
          .isInstanceOf(TimetableImportException.class);
      assertThat(store.commits).isZero();
    }
  }

  private static JejuTimetableImportService service(FakeStore store) {
    return new JejuTimetableImportService(
        c -> parsed(), entries -> TimetableCatalogValidation.VALID, store);
  }

  private static TimetableImportCommand command(boolean dryRun) {
    return new TimetableImportCommand(
        XLSX,
        "405001",
        LocalDate.of(2024, 8, 15),
        Instant.parse("2026-09-04T00:00:00Z"),
        dryRun,
        "issue38-v1");
  }

  private static ParsedTimetable parsed() {
    var entry =
        new TimetableEntryCandidate(
            ROUTE,
            STOP,
            "OUT",
            "daily",
            LocalTime.of(5, 0),
            "3043887/405001/out/8/airport/daily/05:00",
            "JEJU_PROVINCE",
            "jeju-bus-schedule-xlsx",
            "TAGO",
            "39");
    return new ParsedTimetable(
        "a".repeat(64), "{\"parserVersion\":\"jeju-timetable-xlsx-v1\"}", List.of(entry));
  }

  private static final class FakeStore implements TimetableImportStore {
    int inspections;
    int commits;
    TimetableVersionState state = TimetableVersionState.NEW;
    TimetableAtomicWrite last;
    boolean commitReplay;

    public TimetableVersionState inspect(
        String scheduleId, LocalDate effectiveDate, String sha256) {
      inspections++;
      return state;
    }

    public TimetableWriteResult commit(TimetableAtomicWrite write) {
      commits++;
      last = write;
      return commitReplay
          ? new TimetableWriteResult(0, 0, write.entries().size(), true)
          : new TimetableWriteResult(1, 0, 0);
    }
  }
}
