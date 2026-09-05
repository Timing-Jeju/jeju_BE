package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.snapshot.PersistedSnapshotProviderCatalog;
import com.timingjeju.api.application.timetable.TimetableAtomicWrite;
import com.timingjeju.api.application.timetable.TimetableEntryCandidate;
import com.timingjeju.api.application.timetable.TimetableSourceMetadata;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class JdbcTimetableAdaptersTest {
  @Test
  void atomic_store_commit은_Spring_transaction_경계이고_제주_provider가_snapshot_allowlist다()
      throws Exception {
    Method commit =
        JdbcTimetableImportStore.class.getMethod(
            "commit", com.timingjeju.api.application.timetable.TimetableAtomicWrite.class);
    assertThat(commit.isAnnotationPresent(Transactional.class)).isTrue();
    assertThat(PersistedSnapshotProviderCatalog.allows("JEJU_PROVINCE")).isTrue();
  }

  @Test
  void importer는_기본비활성_internal_ApplicationRunner로만_노출한다() {
    ConditionalOnProperty condition =
        JejuTimetableImportRunnerConfiguration.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.name()).containsExactly("timing-jeju.timetable-import.enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
  }

  @Test
  void manifest의_effectiveDate와_정수_omissionCount를_JDBC호출전에_검증한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcTimetableImportStore store = new JdbcTimetableImportStore(jdbc, new ObjectMapper());

    for (String manifest :
        List.of(
            manifest("2024-08-02", "0"),
            manifest("2024-08-01", "-1"),
            manifest("2024-08-01", "1.0"),
            manifest("2024-08-01", "1e0"))) {
      assertThatThrownBy(() -> store.commit(write(manifest)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("TIMETABLE_MANIFEST_INVALID");
    }
    verify(jdbc, never()).queryForObject(anyString(), eq(Object.class), any());
  }

  @Test
  void manifest는_UTF8_2MiB를_초과하면_JDBC호출전에_거부한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcTimetableImportStore store = new JdbcTimetableImportStore(jdbc, new ObjectMapper());
    String oversized =
        manifest("2024-08-01", "0").replace("]}", ",\"" + "a".repeat(2 * 1024 * 1024) + "\"]}");

    assertThatThrownBy(() -> store.commit(write(oversized)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TIMETABLE_MANIFEST_TOO_LARGE");
    verify(jdbc, never()).queryForObject(anyString(), eq(Object.class), any());
  }

  @Test
  void 성공_run은_fetched_inserted_skipped_rejected와_typed_request_metadata를_기록한다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Object.class), any())).thenReturn(new Object());
    when(jdbc.queryForList(anyString(), eq(String.class), any(), any())).thenReturn(List.of());
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    JdbcTimetableImportStore store = new JdbcTimetableImportStore(jdbc, new ObjectMapper());

    store.commit(write(manifest("2024-08-01", "2")));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, org.mockito.Mockito.times(3)).update(sql.capture(), parameters.capture());
    int runIndex = indexContaining(sql.getAllValues(), "insert into public.data_import_runs");
    assertThat(sql.getAllValues().get(runIndex))
        .contains("fetched_count", "inserted_count", "skipped_count", "rejected_count");
    assertThat(parameters.getAllValues().get(runIndex)).containsSequence(3, 1, 2, 0);
    int snapshotIndex =
        indexContaining(sql.getAllValues(), "insert into public.external_api_snapshots");
    Object[] snapshotParameters = parameters.getAllValues().get(snapshotIndex);
    String requestMetadata = (String) snapshotParameters[8];
    var metadata = new ObjectMapper().readTree(requestMetadata);
    assertThat(metadata.path("effectiveDate").isTextual()).isTrue();
    assertThat(metadata.path("effectiveDate").asString()).isEqualTo("2024-08-01");
    assertThat(metadata.path("omissionCount").isIntegralNumber()).isTrue();
    assertThat(metadata.path("omissionCount").intValue()).isEqualTo(2);
  }

  private static int indexContaining(List<String> values, String expected) {
    for (int index = 0; index < values.size(); index++) {
      if (values.get(index).contains(expected)) return index;
    }
    throw new AssertionError(expected);
  }

  private static TimetableAtomicWrite write(String manifest) {
    TimetableEntryCandidate entry =
        new TimetableEntryCandidate(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "out",
            "daily",
            LocalTime.of(6, 30),
            "3043887/405001/route/out/1/00000000-0000-0000-0000-000000000002/daily/06:30",
            "JEJU_PROVINCE",
            "jeju-bus-schedule-xlsx",
            "TAGO",
            "39");
    return new TimetableAtomicWrite(
        "405001",
        LocalDate.of(2024, 8, 1),
        Instant.parse("2026-09-04T00:00:00Z"),
        "issue-38-test",
        "a".repeat(64),
        manifest,
        TimetableSourceMetadata.official("405001"),
        List.of(entry),
        null);
  }

  private static String manifest(String effectiveDate, String omissionCount) {
    return "{\"datasetId\":\"3043887\",\"effectiveDate\":\""
        + effectiveDate
        + "\",\"mappingVersion\":\"operator-mapping-v1\",\"omissionCount\":"
        + omissionCount
        + ",\"omissionCode\":\"UNRESOLVED_OFFICIAL_COLUMN_OMITTED\",\"parserVersion\":\"jeju-timetable-xlsx-v1\",\"recordKeys\":[]}";
  }
}
