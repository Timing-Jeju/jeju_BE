package com.timingjeju.api.domain.schedule.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.schedule.ScheduleItemSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ScheduleItemResponseTest {
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"day_start", "day_end"})
  void 공개_일정항목은_일반방문과_영분_경계점을_구분한다(String role) {
    var start = Instant.parse("2026-09-01T00:00:00Z");
    int stay = role == null ? 60 : 0;
    var item =
        new ScheduleItemSnapshot(
            UUID.randomUUID(),
            1,
            role == null ? "place_visit" : "custom",
            UUID.randomUUID(),
            "일정 위치",
            start,
            start.plusSeconds(stay * 60L),
            stay,
            0,
            false,
            null,
            null,
            role);
    var json =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .valueToTree(ScheduleItemResponse.from(item));
    assertThat(json.has("boundaryRole")).isTrue();
    if (role == null) {
      assertThat(json.get("boundaryRole").isNull()).isTrue();
    } else {
      assertThat(json.get("boundaryRole").asText()).isEqualTo(role);
    }
    assertThat(json.get("stayMinutes").asInt()).isEqualTo(stay);
  }
}
