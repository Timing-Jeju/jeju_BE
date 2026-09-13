package com.timingjeju.api.application.generation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Schema 검증을 마친 응답의 후보 집합·근거·타임라인을 함께 검증하는 내부 projection. */
public record GenerationCandidateProjection(
    String outcome, List<Candidate> candidates, GenerationEvidence evidence) {
  public GenerationCandidateProjection {
    candidates = List.copyOf(candidates);
    if (!("success".equals(outcome) && candidates.size() == 3)
        && !("insufficient_feasible_routes".equals(outcome) && candidates.isEmpty()))
      throw GenerationException.invalidResult();
    java.util.Objects.requireNonNull(evidence);
  }

  public static GenerationCandidateProjection from(
      JsonNode response,
      GenerationTripInput input,
      GenerationPlaceBindings bindings,
      Set<String> approvedSources) {
    var required = new java.util.HashSet<String>();
    var avoided = new java.util.HashSet<String>();
    var stays = new java.util.HashMap<String, Integer>();
    for (var place : input.places()) {
      var factId = bindings.factId(place.placeId());
      if (place.type().equals("avoid")) avoided.add(factId);
      else {
        stays.put(factId, place.stayMinutes());
        if (place.type().equals("must_visit")) required.add(factId);
      }
    }
    var boundary = input.boundary();
    var constraints =
        new GenerationTimeline.Scope(
            boundary.startAt(),
            boundary.endAt(),
            bindings.factId(boundary.startPlaceId()),
            bindings.factId(boundary.endPlaceId()),
            Set.copyOf(input.transportModes()),
            stays,
            Set.of());
    return from(response, constraints, required, avoided, approvedSources, bindings);
  }

  private static GenerationCandidateProjection from(
      JsonNode response,
      GenerationTimeline.Scope constraints,
      Set<String> required,
      Set<String> avoided,
      Set<String> approvedSources,
      GenerationPlaceBindings bindings) {
    var evidence = GenerationEvidence.from(response, approvedSources);
    if (!GenerationCandidateSelection.accepts(response, required, avoided)) return insufficient();
    var scope =
        new GenerationTimeline.Scope(
            constraints.startAt(),
            constraints.endAt(),
            constraints.startPlaceId(),
            constraints.endPlaceId(),
            constraints.allowedModes(),
            constraints.stayMinutes(),
            evidence.facts().keySet());
    var candidates = new ArrayList<Candidate>();
    for (var candidate : response.get("recommendations")) {
      // canonical 미지 ID는 생성 불가로 숨기지 않고 계약/입력 장애로 전달한다.
      for (var place : candidate.get("place_ids")) bindings.canonicalId(place.asText());
      GenerationTimeline timeline;
      try {
        timeline = GenerationTimeline.from(candidate, scope, bindings);
      } catch (GenerationException failure) {
        if (!failure.code().equals("MCP_CONTRACT_INVALID")) throw failure;
        return insufficient();
      }
      var score = candidate.get("score").get("total");
      if (!score.isNumber()
          || score.decimalValue().signum() < 0
          || score.decimalValue().compareTo(BigDecimal.valueOf(100)) > 0)
        throw GenerationException.invalidResult();
      double weightedTotal = 0;
      int totalWeight = 0;
      for (var component : candidate.get("score").get("components").properties()) {
        weightedTotal += component.getValue().get("weighted_value").doubleValue();
        totalWeight = Math.addExact(totalWeight, component.getValue().get("weight").intValue());
      }
      // Python round(float, 2)와 같이 binary64 값을 decimal 반올림한다.
      if (totalWeight != 100
          || roundedScore(weightedTotal).compareTo(roundedScore(score.doubleValue())) != 0)
        throw GenerationException.invalidResult();
      candidates.add(
          new Candidate(
              candidate.get("route_id").asText(),
              candidate.get("rank").intValue(),
              candidate.get("strategy").asText(),
              candidate.get("feasibility").asText(),
              score.decimalValue(),
              timeline));
    }
    candidates.sort(Comparator.comparingInt(Candidate::rank));
    return new GenerationCandidateProjection("success", candidates, evidence);
  }

  private static GenerationCandidateProjection insufficient() {
    return new GenerationCandidateProjection(
        "insufficient_feasible_routes", List.of(), new GenerationEvidence(Map.of(), Set.of()));
  }

  private static BigDecimal roundedScore(double value) {
    if (!Double.isFinite(value)) throw GenerationException.invalidResult();
    return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_EVEN);
  }

  public record Candidate(
      String routeId,
      int rank,
      String strategy,
      String feasibility,
      BigDecimal score,
      GenerationTimeline timeline) {}
}
