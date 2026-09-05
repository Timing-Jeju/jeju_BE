package com.timingjeju.api.global.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.snapshot.PersistedSnapshotProviderCatalog;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

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
}
