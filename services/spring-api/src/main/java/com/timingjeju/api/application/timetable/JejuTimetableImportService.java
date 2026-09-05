package com.timingjeju.api.application.timetable;

import java.util.List;
import java.util.Objects;

public final class JejuTimetableImportService {
  private final TimetableParser parser;
  private final TimetableCatalog catalog;
  private final TimetableImportStore store;

  public JejuTimetableImportService(
      TimetableParser parser, TimetableCatalog catalog, TimetableImportStore store) {
    this.parser = Objects.requireNonNull(parser);
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
  }

  public TimetableImportResult importXlsx(TimetableImportCommand command) {
    ParsedTimetable parsed = parser.parse(Objects.requireNonNull(command));
    TimetableCatalogValidation validation = catalog.validate(parsed.entries());
    if (validation != TimetableCatalogValidation.VALID) {
      throw new TimetableImportException("TIMETABLE_CATALOG_" + validation);
    }
    if (!parsed.rejectedRows().isEmpty() && !command.dryRun()) {
      throw new TimetableImportException("TIMETABLE_ROWS_REJECTED");
    }
    TimetableVersionState state =
        store.inspect(command.scheduleId(), command.effectiveDate(), parsed.sha256());
    if (state == TimetableVersionState.CONFLICT) {
      if (command.dryRun()) {
        return new TimetableImportResult(
            parsed.entries().size(),
            append(parsed.rejectedRows(), "SAME_VERSION_CONFLICT"),
            true,
            false,
            null,
            parsed.omissions());
      }
      throw new TimetableImportException("SAME_VERSION_CONFLICT");
    }
    if (state == TimetableVersionState.REPLAY) {
      return new TimetableImportResult(
          parsed.entries().size(),
          parsed.rejectedRows(),
          command.dryRun(),
          true,
          null,
          parsed.omissions());
    }
    if (command.dryRun()) {
      return new TimetableImportResult(
          parsed.entries().size(), parsed.rejectedRows(), true, false, null, parsed.omissions());
    }
    TimetableAtomicWrite write =
        new TimetableAtomicWrite(
            command.scheduleId(),
            command.effectiveDate(),
            command.fetchedAt(),
            command.idempotencyKey(),
            parsed.sha256(),
            parsed.canonicalManifest(),
            TimetableSourceMetadata.official(command.scheduleId()),
            parsed.entries(),
            null);
    TimetableWriteResult result = store.commit(write);
    return new TimetableImportResult(
        parsed.entries().size(), List.of(), false, result.replayed(), result, parsed.omissions());
  }

  private static List<String> append(List<String> values, String value) {
    var result = new java.util.ArrayList<>(values);
    result.add(value);
    return List.copyOf(result);
  }
}
