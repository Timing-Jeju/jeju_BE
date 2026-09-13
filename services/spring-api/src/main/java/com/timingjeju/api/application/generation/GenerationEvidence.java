package com.timingjeju.api.application.generation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Schema 검증 뒤 추출하는 최소 계보. fact value·geometry·원문은 보존하지 않는다. */
public record GenerationEvidence(Map<String, Fact> facts, Set<String> sourceIds) {
  private static final Set<String> REFERENCES =
      Set.of(
          "evidence_fact_ids",
          "derivation_evidence_fact_ids",
          "distance_derivation_fact_ids",
          "new_evidence_fact_ids");

  public GenerationEvidence {
    facts = Map.copyOf(facts);
    sourceIds = Set.copyOf(sourceIds);
  }

  public static GenerationEvidence from(JsonNode response, Set<String> approvedSources) {
    var sources = new HashSet<String>();
    for (var source : array(response.get("data_sources"))) {
      var id = text(source.get("source_id"));
      if (!sources.add(id) || !approvedSources.contains(id)) throw invalid();
    }
    var facts = new HashMap<String, Fact>();
    for (var value : array(response.get("evidence_facts"))) {
      var id = text(value.get("fact_id"));
      var sourceRefs = new HashSet<String>();
      for (var ref : array(value.get("source_refs"))) sourceRefs.add(text(ref.get("source_id")));
      var derivation = value.get("derivation");
      if (derivation == null || !derivation.isObject()) throw invalid();
      var kind = text(derivation.get("kind"));
      if (!Set.of("source", "policy", "computed").contains(kind)
          || (kind.equals("source") && sourceRefs.isEmpty())
          || !sources.containsAll(sourceRefs)) throw invalid();
      var inputs = ids(derivation.get("input_fact_ids"));
      if (facts.putIfAbsent(id, new Fact(sourceRefs, inputs)) != null) throw invalid();
    }
    for (var fact : facts.values()) {
      if (!facts.keySet().containsAll(fact.inputFactIds())) throw invalid();
    }
    validateAcyclic(facts);
    // previous_days는 이전 ledger에 닫혀 있으므로 현재 응답의 참조와 섞지 않는다.
    validateReferences(array(response.get("recommendations")), facts.keySet());
    validateReferences(array(response.get("place_decisions")), facts.keySet());
    return new GenerationEvidence(facts, sources);
  }

  private static void validateAcyclic(Map<String, Fact> facts) {
    var remaining = new HashMap<String, Integer>();
    var dependents = new HashMap<String, Set<String>>();
    var ready = new ArrayDeque<String>();
    facts.forEach(
        (id, fact) -> {
          remaining.put(id, fact.inputFactIds().size());
          if (fact.inputFactIds().isEmpty()) ready.add(id);
          fact.inputFactIds()
              .forEach(
                  parent -> dependents.computeIfAbsent(parent, ignored -> new HashSet<>()).add(id));
        });
    int visited = 0;
    while (!ready.isEmpty()) {
      var id = ready.removeFirst();
      visited++;
      for (var child : dependents.getOrDefault(id, Set.of())) {
        if (remaining.compute(child, (key, count) -> count - 1) == 0) ready.add(child);
      }
    }
    if (visited != facts.size()) throw invalid();
  }

  private static void validateReferences(JsonNode root, Set<String> facts) {
    var pending = new ArrayDeque<JsonNode>();
    pending.add(root);
    while (!pending.isEmpty()) {
      var value = pending.removeFirst();
      if (value.isObject()) {
        for (var entry : value.properties()) {
          if (REFERENCES.contains(entry.getKey()) && !facts.containsAll(ids(entry.getValue())))
            throw invalid();
          pending.add(entry.getValue());
        }
      } else if (value.isArray()) value.forEach(pending::add);
    }
  }

  private static Set<String> ids(JsonNode value) {
    var ids = new HashSet<String>();
    for (var id : array(value)) ids.add(text(id));
    return Set.copyOf(ids);
  }

  private static JsonNode array(JsonNode value) {
    if (value == null || !value.isArray()) throw invalid();
    return value;
  }

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }

  public record Fact(Set<String> sourceIds, Set<String> inputFactIds) {
    public Fact {
      sourceIds = Set.copyOf(sourceIds);
      inputFactIds = Set.copyOf(inputFactIds);
    }
  }
}
