package com.timingjeju.api.application.generation;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장된 하루 조건의 wire adapter. 공개 Schema는 AI Pydantic 모델이 소유한다. */
public final class GenerationMcpDayConditions {
  private GenerationMcpDayConditions() {}

  /** 이전 Day 이력과 MCP envelope는 호출 orchestrator가 별도로 결합해야 한다. */
  public static Map<String, Object> from(
      GenerationTripInput input, GenerationPlaceBindings bindings) {
    var boundary = input.boundary();
    var accommodation =
        boundary.endPlaceId().equals(input.airportPlaceId())
            ? boundary.startPlaceId()
            : boundary.endPlaceId();
    var modes = input.transportModes();
    var ordered =
        input.places().stream()
            .sorted(
                Comparator.comparingInt(GenerationTripInput.PlaceInput::priority)
                    .reversed()
                    .thenComparing(place -> place.placeId().toString()))
            .toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("schema_version", "0.7.0");
    result.put("request_mode", "generate");
    result.put("trip_date", boundary.startAt().toLocalDate().toString());
    result.put("timezone", "Asia/Seoul");
    result.put("accommodation", bindings.accommodation(accommodation));
    result.put(
        "activity_window",
        Map.of(
            "start_at",
            boundary.startAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "end_at",
            boundary.endAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    result.put(
        "day_boundary",
        Map.of(
            "start_place",
            bindings.reference(boundary.startPlaceId()),
            "end_place",
            bindings.reference(boundary.endPlaceId())));
    result.put(
        "transport",
        Map.of(
            "allowed_modes",
            modes,
            "preferred_mode",
            modes.getFirst(),
            "fallback_order",
            List.copyOf(modes.subList(1, modes.size()))));
    result.put("required_places", references(ordered, "must_visit", bindings));
    result.put("preferred_places", references(ordered, "preferred", bindings));
    result.put("excluded_places", references(ordered, "avoid", bindings));
    result.put(
        "place_duration_preferences",
        ordered.stream()
            .filter(place -> !"avoid".equals(place.type()))
            .map(place -> duration(place, bindings))
            .toList());
    result.put("discovery", Map.of("preferred_categories", input.preferredCategories()));
    if (input.relaxedPace()) result.put("rest", Map.of("pace", "relaxed"));
    return Map.copyOf(result);
  }

  private static Map<String, Object> duration(
      GenerationTripInput.PlaceInput place, GenerationPlaceBindings bindings) {
    var result = new LinkedHashMap<String, Object>();
    result.put("place_id", bindings.factId(place.placeId()));
    result.put("requested_stay_minutes", place.stayMinutes());
    if (!"user_requested".equals(place.staySource())) {
      result.put("source", place.staySource());
      result.put("policy_version", place.stayPolicyVersion());
      result.put("policy_effective_at", place.stayPolicyEffectiveAt().toString());
    }
    return Map.copyOf(result);
  }

  private static List<Map<String, Object>> references(
      List<GenerationTripInput.PlaceInput> places, String type, GenerationPlaceBindings bindings) {
    return places.stream()
        .filter(place -> type.equals(place.type()))
        .map(place -> bindings.reference(place.placeId()))
        .toList();
  }
}
