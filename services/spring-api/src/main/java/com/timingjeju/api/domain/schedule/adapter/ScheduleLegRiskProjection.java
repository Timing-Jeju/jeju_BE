package com.timingjeju.api.domain.schedule.adapter;

import com.timingjeju.api.application.schedule.ScheduleException;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 저장된 구조화 등급만 공개하며 원문과 geometry는 응답으로 복사하지 않는다. */
record ScheduleLegRiskProjection(String level, List<String> reasonCodes) {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final List<String> LEVELS =
      List.of("low", "medium", "unknown", "high", "critical");

  static ScheduleLegRiskProjection from(String facts) {
    try {
      if (facts == null) return new ScheduleLegRiskProjection(null, List.of());
      var root = MAPPER.readTree(facts);
      var generation = root.get("generation");
      if (generation == null) return new ScheduleLegRiskProjection(null, List.of());
      if (!generation.path("schemaVersion").isIntegralNumber()
          || generation.path("schemaVersion").intValue() != 1) throw invalid();
      var risks = generation.get("risks");
      if (risks == null || !risks.isArray()) throw invalid();
      String level = risks.isEmpty() ? "unknown" : "low";
      var codes = new LinkedHashSet<String>();
      for (var risk : risks) {
        var value = risk.get("level");
        if (value == null || !value.isString() || !LEVELS.contains(value.asText())) throw invalid();
        if (LEVELS.indexOf(value.asText()) > LEVELS.indexOf(level)) level = value.asText();
        var reasons = risk.get("reasonCodes");
        if (reasons == null || !reasons.isArray()) throw invalid();
        for (var reason : reasons) {
          if (!reason.isString() || !reason.asText().matches("[A-Z][A-Z0-9_]{0,127}"))
            throw invalid();
          codes.add(reason.asText());
        }
      }
      return new ScheduleLegRiskProjection(level, List.copyOf(codes));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static ScheduleException invalid() {
    return ScheduleException.internalServerError();
  }
}
