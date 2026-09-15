package com.timingjeju.api.application.generation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 다음 Day에 전달할 최소 선택 이력. 공개 계약은 Pydantic SelectedDayHistory가 소유한다. */
public record GenerationSelectedDay(
    UUID dayId,
    LocalDate tripDate,
    OffsetDateTime windowStartAt,
    OffsetDateTime windowEndAt,
    OffsetDateTime dayStartAt,
    OffsetDateTime dayEndAt,
    List<SelectedPlace> selectedPlaces,
    GenerationTotals totals,
    List<String> evidenceFactIds) {
  public GenerationSelectedDay {
    selectedPlaces = List.copyOf(selectedPlaces);
    evidenceFactIds = List.copyOf(evidenceFactIds);
    if (dayId == null
        || totals == null
        || selectedPlaces.isEmpty()
        || selectedPlaces.size() > 40
        || evidenceFactIds.isEmpty()
        || evidenceFactIds.size() > 256
        || new java.util.HashSet<>(evidenceFactIds).size() != evidenceFactIds.size())
      throw invalid();
    for (var time : List.of(windowStartAt, windowEndAt, dayStartAt, dayEndAt))
      if (!time.getOffset().equals(java.time.ZoneOffset.ofHours(9))
          || !time.toLocalDate().equals(tripDate)) throw invalid();
    if (windowStartAt.isAfter(dayStartAt)
        || !dayStartAt.isBefore(dayEndAt)
        || dayEndAt.isAfter(windowEndAt)) throw invalid();
    var identities = new java.util.HashSet<String>();
    for (var place : selectedPlaces) {
      if (!identities.add(place.role() + ":" + place.placeFactId())
          || !evidenceFactIds.containsAll(place.evidenceFactIds())) throw invalid();
    }
    if (!evidenceFactIds.containsAll(totals.evidenceFactIds())) throw invalid();
  }

  static GenerationSelectedDay from(
      GenerationTimeline timeline,
      GenerationTotals totals,
      GenerationDayBoundary boundary,
      Set<String> knownFacts) {
    var selected = new LinkedHashMap<String, SelectedPlace>();
    for (var event : timeline.events()) {
      if (!Set.of("visit", "meal", "rest").contains(event.type())) continue;
      var key = event.type() + ":" + event.placeFactId();
      var ids = new LinkedHashSet<>(event.evidenceFactIds());
      var previous = selected.get(key);
      if (previous != null) ids.addAll(previous.evidenceFactIds());
      selected.put(
          key,
          new SelectedPlace(event.placeId(), event.placeFactId(), event.type(), List.copyOf(ids)));
    }
    var references = new java.util.TreeSet<>(totals.evidenceFactIds());
    selected.values().forEach(place -> references.addAll(place.evidenceFactIds()));
    if (!knownFacts.containsAll(references)) throw invalid();
    return new GenerationSelectedDay(
        boundary.dayId(),
        boundary.startAt().toLocalDate(),
        boundary.startAt(),
        boundary.endAt(),
        timeline.events().getFirst().startAt(),
        timeline.events().getLast().endAt(),
        new ArrayList<>(selected.values()),
        totals,
        List.copyOf(references));
  }

  public Map<String, Object> toMcp() {
    return Map.of(
        "trip_date",
        tripDate.toString(),
        "activity_window",
        Map.of("start_at", wireTime(windowStartAt), "end_at", wireTime(windowEndAt)),
        "day_start_at",
        wireTime(dayStartAt),
        "day_end_at",
        wireTime(dayEndAt),
        "selected_places",
        selectedPlaces.stream()
            .map(
                place ->
                    Map.of(
                        "place_id",
                        place.placeFactId(),
                        "role",
                        place.role(),
                        "evidence_fact_ids",
                        place.evidenceFactIds()))
            .toList(),
        "totals",
        totals.toMcp(),
        "evidence_fact_ids",
        evidenceFactIds);
  }

  public record SelectedPlace(
      UUID canonicalPlaceId, String placeFactId, String role, List<String> evidenceFactIds) {
    public SelectedPlace {
      evidenceFactIds = List.copyOf(evidenceFactIds);
      if (canonicalPlaceId == null
          || placeFactId == null
          || placeFactId.isBlank()
          || !Set.of("visit", "meal", "rest").contains(role)
          || evidenceFactIds.isEmpty()
          || evidenceFactIds.size() > 64) throw invalid();
    }
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }

  private static String wireTime(OffsetDateTime value) {
    return value.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }
}
