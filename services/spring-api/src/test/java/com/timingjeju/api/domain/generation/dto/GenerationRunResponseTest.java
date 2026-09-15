package com.timingjeju.api.domain.generation.dto;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.application.generation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationRunResponseTest {
  private final UUID trip = UUID.randomUUID(), run = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-13T00:00:00Z");
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void 성공응답은_null_최초기준과_세후보의_저장값_및_구체적URL을_보존한다() {
    var strategies = List.of("balanced", "relaxed", "experience_max");
    var candidates = new ArrayList<GenerationRunReader.SavedCandidate>();
    for (int i = 0; i < 3; i++)
      candidates.add(
          new GenerationRunReader.SavedCandidate(
              UUID.randomUUID(),
              UUID.randomUUID(),
              i + 1,
              strategies.get(i),
              new BigDecimal("80.25"),
              "feasible_with_caution",
              "검증된 합계 설명",
              now,
              now.plus(Duration.ofHours(24))));
    var json =
        mapper.valueToTree(GenerationRunResponse.from(saved("succeeded", "success", candidates)));
    assertThat(json.get("pollUrl").asText())
        .isEqualTo("/api/v1/trips/" + trip + "/schedule-generations/" + run);
    assertThat(json.has("failure")).isFalse();
    assertThat(json.has("mcpInputHash")).isFalse();
    var result = json.get("result");
    assertThat(result.has("baseScheduleVersionId")).isTrue();
    assertThat(result.get("baseScheduleVersionId").isNull()).isTrue();
    assertThat(result.get("factsAsOf").asText()).isEqualTo("2026-09-13T09:00:00+09:00");
    assertThat(result.get("resultSource").asText()).isEqualTo("mcp");
    assertThat(result.get("candidates").size()).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      var actual = result.get("candidates").get(i);
      var expected = candidates.get(i);
      assertThat(actual.get("score").decimalValue()).isEqualByComparingTo(expected.score());
      assertThat(actual.get("scheduleUrl").asText())
          .isEqualTo(
              "/api/v1/trips/" + trip + "/schedule-versions/" + expected.scheduleVersionId());
      assertThat(actual.get("applyUrl").asText())
          .isEqualTo(
              "/api/v1/trips/"
                  + trip
                  + "/schedule-generations/"
                  + run
                  + "/candidates/"
                  + expected.candidateId()
                  + "/apply");
    }
  }

  @Test
  void 상태별_미존재필드는_null도_노출하지_않고_생성불가는_빈후보를_반환한다() {
    for (String status : List.of("queued", "running", "failed", "cancelled")) {
      var json = mapper.valueToTree(GenerationRunResponse.from(saved(status, null, List.of())));
      assertThat(json.has("result")).isFalse();
      assertThat(json.has("failure")).isEqualTo(Set.of("failed", "cancelled").contains(status));
      assertThat(json.has("startedAt")).isEqualTo(!status.equals("queued"));
      assertThat(json.has("completedAt")).isEqualTo(Set.of("failed", "cancelled").contains(status));
    }
    var insufficient =
        mapper.valueToTree(
            GenerationRunResponse.from(
                saved("succeeded", "insufficient_feasible_routes", List.of())));
    assertThat(insufficient.get("result").get("candidates").size()).isZero();
  }

  private GenerationRunReader.SavedRun saved(
      String status, String outcome, List<GenerationRunReader.SavedCandidate> candidates) {
    boolean terminal = Set.of("succeeded", "failed", "cancelled").contains(status);
    return new GenerationRunReader.SavedRun(
        run,
        trip,
        UUID.randomUUID(),
        null,
        status,
        outcome,
        "a".repeat(64),
        now,
        status.equals("queued") ? null : now,
        terminal ? now : null,
        terminal ? now.plus(Duration.ofDays(7)) : null,
        status.equals("succeeded") ? now : null,
        false,
        GenerationFailure.from(status, "MCP_TIMEOUT"),
        candidates);
  }
}
