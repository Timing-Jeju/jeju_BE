package com.timingjeju.api.application.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Pydantic Totals에서 검증·복사한 내부 저장 합계. 원문이나 개별 fact value는 포함하지 않는다. */
public record GenerationTotals(
    int totalMinutes,
    int visitMinutes,
    int transferMinutes,
    int restMinutes,
    int mealMinutes,
    int bufferMinutes,
    int walkingMinutes,
    int walkingDistanceMeters,
    int taxiPickupBufferMinutes,
    int busWaitMinutes,
    int busDistanceMeters,
    int taxiDistanceMeters,
    int totalDistanceMeters,
    CostRange estimatedCost,
    CostRange busCost,
    CostRange taxiCost,
    List<String> evidenceFactIds) {

  public GenerationTotals {
    for (int value :
        new int[] {
          totalMinutes,
          visitMinutes,
          transferMinutes,
          restMinutes,
          mealMinutes,
          bufferMinutes,
          walkingMinutes,
          walkingDistanceMeters,
          taxiPickupBufferMinutes,
          busWaitMinutes,
          busDistanceMeters,
          taxiDistanceMeters,
          totalDistanceMeters
        }) {
      if (value < 0) throw invalid();
    }
    if (estimatedCost == null || busCost == null || taxiCost == null || evidenceFactIds == null)
      throw invalid();
    evidenceFactIds = List.copyOf(evidenceFactIds);
    if ((long) visitMinutes + transferMinutes + restMinutes + mealMinutes + bufferMinutes
            != totalMinutes
        || (long) walkingDistanceMeters + busDistanceMeters + taxiDistanceMeters
            != totalDistanceMeters
        || (long) busCost.minKrw() + taxiCost.minKrw() != estimatedCost.minKrw()
        || (long) busCost.maxKrw() + taxiCost.maxKrw() != estimatedCost.maxKrw()) throw invalid();
  }

  public static GenerationTotals from(JsonNode value, Set<String> knownFacts) {
    if (value == null || !value.isObject()) throw invalid();
    var ids = value.get("derivation_evidence_fact_ids");
    if (ids == null || !ids.isArray()) throw invalid();
    var references = new ArrayList<String>();
    for (var id : ids) {
      if (!id.isTextual() || !knownFacts.contains(id.asText())) throw invalid();
      references.add(id.asText());
    }
    return new GenerationTotals(
        integer(value, "total_minutes"),
        integer(value, "visit_minutes"),
        integer(value, "transfer_minutes"),
        integer(value, "rest_minutes"),
        integer(value, "meal_minutes"),
        integer(value, "buffer_minutes"),
        integer(value, "walking_minutes"),
        integer(value, "walking_distance_meters"),
        integer(value, "taxi_pickup_buffer_minutes"),
        integer(value, "bus_wait_minutes"),
        integer(value, "bus_distance_meters"),
        integer(value, "taxi_distance_meters"),
        integer(value, "total_distance_meters"),
        cost(value.get("estimated_cost")),
        cost(value.get("bus_cost")),
        cost(value.get("taxi_cost")),
        references);
  }

  public record CostRange(int minKrw, int maxKrw, boolean isEstimated) {
    public CostRange {
      if (minKrw < 0 || maxKrw < minKrw) throw invalid();
    }
  }

  public java.util.Map<String, Object> toMcp() {
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("total_minutes", totalMinutes);
    result.put("visit_minutes", visitMinutes);
    result.put("transfer_minutes", transferMinutes);
    result.put("rest_minutes", restMinutes);
    result.put("meal_minutes", mealMinutes);
    result.put("buffer_minutes", bufferMinutes);
    result.put("walking_minutes", walkingMinutes);
    result.put("walking_distance_meters", walkingDistanceMeters);
    result.put("taxi_pickup_buffer_minutes", taxiPickupBufferMinutes);
    result.put("bus_wait_minutes", busWaitMinutes);
    result.put("bus_distance_meters", busDistanceMeters);
    result.put("taxi_distance_meters", taxiDistanceMeters);
    result.put("total_distance_meters", totalDistanceMeters);
    result.put("estimated_cost", wireCost(estimatedCost));
    result.put("bus_cost", wireCost(busCost));
    result.put("taxi_cost", wireCost(taxiCost));
    result.put("derivation_evidence_fact_ids", evidenceFactIds);
    return java.util.Map.copyOf(result);
  }

  private static java.util.Map<String, Object> wireCost(CostRange cost) {
    return java.util.Map.of(
        "min_krw", cost.minKrw(), "max_krw", cost.maxKrw(), "is_estimated", cost.isEstimated());
  }

  private static CostRange cost(JsonNode value) {
    if (value == null || !value.isObject()) throw invalid();
    var estimated = value.get("is_estimated");
    if (estimated == null || !estimated.isBoolean()) throw invalid();
    return new CostRange(
        integer(value, "min_krw"), integer(value, "max_krw"), estimated.booleanValue());
  }

  private static int integer(JsonNode value, String field) {
    var number = value.get(field);
    if (number == null || !number.isIntegralNumber() || !number.canConvertToInt()) throw invalid();
    return number.intValue();
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
