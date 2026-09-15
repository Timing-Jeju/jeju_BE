package com.timingjeju.api.application.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** AI Schema 검증 뒤의 세 후보 집합 판정. false이면 후보를 하나도 저장하지 않는다. */
public final class GenerationCandidateSelection {
  private static final Set<String> STRATEGIES = Set.of("balanced", "relaxed", "experience_max");

  private GenerationCandidateSelection() {}

  public static boolean accepts(JsonNode response, Set<String> required, Set<String> avoided) {
    var candidates = array(response.get("recommendations"));
    var status = text(response.get("status"));
    var failure = response.get("failure");
    if (status.equals("insufficient_feasible_routes")) {
      if (!candidates.isEmpty() || failure == null || !failure.isObject()) throw invalid();
      return false;
    }
    if (!status.equals("success")) throw invalid();
    if (failure != null && !failure.isNull()) throw invalid();
    if (candidates.size() != 3) return false;
    var strategies = new HashSet<String>();
    var routes = new HashSet<String>();
    var ranks = new HashSet<Integer>();
    var signatures = new ArrayList<Signature>();
    for (var candidate : candidates) {
      if (!strategies.add(text(candidate.get("strategy")))
          || !routes.add(text(candidate.get("route_id")))
          || !ranks.add(integer(candidate.get("rank")))
          || !Set.of("feasible", "feasible_with_caution")
              .contains(text(candidate.get("feasibility")))) return false;
      var places = new ArrayList<String>();
      for (var place : array(candidate.get("place_ids"))) places.add(text(place));
      if (places.isEmpty()
          || !places.containsAll(required)
          || places.stream().anyMatch(avoided::contains)) return false;
      var modes = new ArrayList<String>();
      var stays = new ArrayList<Integer>();
      for (var event : array(candidate.get("timeline"))) {
        var transfer = event.get("transfer");
        if (transfer != null && !transfer.isNull()) {
          var mode = text(transfer.get("mode"));
          if (!mode.equals("walk")) modes.add(mode);
        }
        if (Set.of("visit", "meal", "rest").contains(text(event.get("type"))))
          stays.add(integer(event.get("duration_minutes")));
      }
      var signature = new Signature(places, modes, stays);
      if (signatures.stream().anyMatch(previous -> !different(previous, signature))) return false;
      signatures.add(signature);
    }
    return strategies.equals(STRATEGIES) && ranks.equals(Set.of(1, 2, 3));
  }

  /** AI route_signatures_are_materially_different와 같은 경계값을 정수 비율로 비교한다. */
  private static boolean different(Signature first, Signature second) {
    var union = new HashSet<>(first.places());
    union.addAll(second.places());
    var intersection = new HashSet<>(first.places());
    intersection.retainAll(second.places());
    int longest = Math.max(first.places().size(), second.places().size());
    int modes = Math.max(first.modes().size(), second.modes().size());
    return intersection.size() * 5 <= union.size() * 4
        || samePositions(first.places(), second.places()) * 10 <= longest * 7
        || (modes > 0 && (modes - samePositions(first.modes(), second.modes())) * 4 >= modes)
        || !first.stays().equals(second.stays());
  }

  private static int samePositions(List<String> first, List<String> second) {
    int same = 0;
    for (int n = 0; n < Math.min(first.size(), second.size()); n++)
      if (first.get(n).equals(second.get(n))) same++;
    return same;
  }

  private record Signature(List<String> places, List<String> modes, List<Integer> stays) {}

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static int integer(JsonNode value) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
    return value.intValue();
  }

  private static JsonNode array(JsonNode value) {
    if (value == null || !value.isArray()) throw invalid();
    return value;
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
