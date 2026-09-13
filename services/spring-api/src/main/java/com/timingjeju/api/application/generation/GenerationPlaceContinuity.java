package com.timingjeju.api.application.generation;

import java.util.HashSet;
import tools.jackson.databind.JsonNode;

/** 순서대로 이동한 장소와 승인된 입구/대표좌표 근거를 대조한다. */
final class GenerationPlaceContinuity {
  private GenerationPlaceContinuity() {}

  static void validate(JsonNode candidate, GenerationEntranceEvidence entrances) {
    String current = text(candidate.get("start_place_id"));
    String end = text(candidate.get("end_place_id"));
    var events = candidate.get("timeline");
    for (int index = 0; index < events.size(); index++) {
      var event = events.get(index);
      if (!"transfer".equals(event.path("type").asText())) {
        var place = event.get("place_id");
        if (place != null && !place.isNull() && !current.equals(text(place))) throw invalid();
        continue;
      }
      String destination = end;
      for (int next = index + 1; next < events.size(); next++) {
        var following = events.get(next);
        if ("transfer".equals(following.path("type").asText())) throw invalid();
        var place = following.get("place_id");
        if (place != null && !place.isNull()) {
          destination = text(place);
          break;
        }
      }
      var transfer = event.get("transfer");
      switch (transfer.path("mode").asText()) {
        case "walk" -> verifyWalk(transfer.get("direct_walk"), current, destination, entrances);
        case "bus" -> {
          verifyWalk(transfer.get("access_walk"), current, null, entrances);
          verifyWalk(transfer.get("egress_walk"), null, destination, entrances);
        }
        case "taxi" -> {
          /* 택시 경로 endpoint 근거의 별도 연결은 후속이다. */
        }
        default -> throw invalid();
      }
      current = destination;
    }
    if (!current.equals(end)) throw invalid();
  }

  private static void verifyWalk(
      JsonNode walk, String from, String to, GenerationEntranceEvidence entrances) {
    if (walk == null || !walk.isObject()) throw invalid();
    var ids = walk.get("evidence_fact_ids");
    if (ids == null || !ids.isArray()) throw invalid();
    var facts = new HashSet<String>();
    for (var id : ids) facts.add(text(id));
    boolean provisional = false;
    if (from != null)
      provisional |= entrances.requireEndpoint(text(walk.get("from_id")), from, facts);
    if (to != null) provisional |= entrances.requireEndpoint(text(walk.get("to_id")), to, facts);
    if (!(provisional ? "PROVISIONAL_PLACE_POINT" : "VERIFIED")
        .equals(walk.path("entrance_verification").asText())) throw invalid();
  }

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
