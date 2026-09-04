package com.timingjeju.api.domain.accommodation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccommodationStoreSourceContractTest {
  @Test
  void delete는_대상_숙소_존재를_active_schedule보다_먼저_확인한다() throws Exception {
    String source =
        Files.readString(
            root()
                .resolve(
                    "services/spring-api/src/main/java/com/timingjeju/api/domain/accommodation/adapter/JdbcAccommodationStore.java"));
    int method = source.indexOf("public void delete(AccommodationDeleteRecord record)");
    int missing = source.indexOf("if (targetIndex < 0)", method);
    int active = source.indexOf("if (root.activeScheduleVersionId() != null)", method);

    assertThat(method).isGreaterThanOrEqualTo(0);
    assertThat(missing).isGreaterThan(method);
    assertThat(active).isGreaterThan(missing);
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("services/spring-api"))) return current;
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
