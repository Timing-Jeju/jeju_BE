package com.timingjeju.api.global.commandinput;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NoLocationRepositoryContractTest {
  @Test
  void 저장과_복원_SQL은_제거된_위치_컬럼을_참조하지_않는다() {
    for (String sql :
        new String[] {
          JdbcCommandInputSnapshotRepository.PROJECTION,
          JdbcCommandInputSnapshotRepository.INSERT_SQL
        }) {
      assertThat(sql).doesNotContain("location", "precision_meters", "observed_at", "expires_at");
    }
  }

  @Test
  void 저장소_공개_API에서_현재위치_조회가_제거된다() {
    assertThat(
            Arrays.stream(CommandInputSnapshotRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
        .doesNotContain("findUsableLocation");
  }
}
