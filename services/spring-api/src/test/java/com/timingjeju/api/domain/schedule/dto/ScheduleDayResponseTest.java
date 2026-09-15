package com.timingjeju.api.domain.schedule.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.schedule.ScheduleDaySnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ScheduleDayResponseTest {
  @Test
  void AI_이력의_존재를_후보_일정에서도_손실없이_전달한다() {
    var day =
        new ScheduleDaySnapshot(
            UUID.randomUUID(), 1, LocalDate.of(2026, 10, 1), List.of(), List.of(), true);
    var json =
        JsonMapper.builder().findAndAddModules().build().valueToTree(ScheduleDayResponse.from(day));
    assertThat(json.get("hasGenerationResult").asBoolean()).isTrue();
  }

  @Test
  void AI_이력이_없는_날짜는_일정_조회에서_명시적으로_구분한다() {
    var day =
        new ScheduleDaySnapshot(
            UUID.randomUUID(), 1, LocalDate.of(2026, 10, 1), List.of(), List.of());
    var json =
        JsonMapper.builder().findAndAddModules().build().valueToTree(ScheduleDayResponse.from(day));
    assertThat(json.has("hasGenerationResult")).isTrue();
    assertThat(json.get("hasGenerationResult").asBoolean()).isFalse();
  }
}
