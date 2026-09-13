package com.timingjeju.api.application.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 검증된 입구↔장소 관계의 호출 내 조회 객체. fact value/좌표를 저장용 결과에 복사하지 않는다. */
public final class GenerationEntranceEvidence {
  private static final String SOURCE = "travel.place-entrance-map";
  private final Map<String, Binding> bindings;

  private GenerationEntranceEvidence(Map<String, Binding> bindings) {
    this.bindings = Map.copyOf(bindings);
  }

  public static GenerationEntranceEvidence from(JsonNode response, Set<String> approvedSources) {
    var evidence = GenerationEvidence.from(response, approvedSources);
    var bindings = new HashMap<String, Binding>();
    for (var fact : response.get("evidence_facts")) {
      if (!"place_entrance".equals(fact.path("category").asText())) continue;
      var factId = text(fact.get("fact_id"));
      if (!"source".equals(fact.path("derivation").path("kind").asText())
          || !evidence.facts().get(factId).sourceIds().equals(Set.of(SOURCE))) throw invalid();
      var value = fact.get("value");
      if (value == null || !value.isObject()) throw invalid();
      var entranceId = text(value.get("entrance_id"));
      var placeId = text(value.get("place_id"));
      if (!placeId.matches("tourapi\\.place:[0-9]{1,32}")) throw invalid();
      var known = bindings.get(entranceId);
      if (known != null && !known.placeId().equals(placeId)) throw invalid();
      var facts = new HashSet<String>();
      if (known != null) facts.addAll(known.factIds());
      facts.add(factId);
      bindings.put(entranceId, new Binding(placeId, Set.copyOf(facts)));
    }
    return new GenerationEntranceEvidence(bindings);
  }

  public void require(String entranceId, String expectedPlaceId, Set<String> walkFactIds) {
    if (entranceId == null) throw invalid();
    var binding = bindings.get(entranceId);
    if (binding == null
        || !binding.placeId().equals(expectedPlaceId)
        || walkFactIds == null
        || binding.factIds().stream().noneMatch(walkFactIds::contains)) throw invalid();
  }

  private record Binding(String placeId, Set<String> factIds) {}

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
