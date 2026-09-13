package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.RecommendedStaySource;
import com.timingjeju.api.application.trip.*;
import java.time.Instant;
import java.util.*;

/** 서버가 잠긴 여행에서 복사하는 내부 입력 projection. MCP 공개 계약을 대체하지 않는다. */
public record GenerationTripInput(
    UUID tripId,
    long tripRevision,
    UUID baseScheduleVersionId,
    GenerationDayBoundary boundary,
    List<TripDay> days,
    List<TripPlannerConditions.DayAnchor> dayAnchors,
    List<TripPlacePreference> savedPreferences,
    List<String> transportModes,
    List<String> preferredCategories,
    boolean relaxedPace,
    List<PlaceInput> places) {
  private static final Set<String> CATEGORIES =
      Set.of("restaurant", "cafe", "leisure", "cultural_facility");

  public GenerationTripInput {
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(boundary);
    if (tripRevision < 1) throw invalid();
    days = List.copyOf(days);
    dayAnchors = List.copyOf(dayAnchors);
    savedPreferences = List.copyOf(savedPreferences);
    transportModes = List.copyOf(transportModes);
    preferredCategories = List.copyOf(preferredCategories);
    places = List.copyOf(places);
  }

  public static GenerationTripInput capture(
      TripAggregate trip,
      UUID targetDayId,
      int completedThroughDayNo,
      UUID approvedAirportId,
      Map<UUID, RecommendedStay> recommendations) {
    if (trip == null || recommendations == null || !"Asia/Seoul".equals(trip.timezone()))
      throw invalid();
    var boundary =
        GenerationDayBoundary.resolve(
            trip.days(),
            targetDayId,
            completedThroughDayNo,
            trip.plannerConditions(),
            trip.transportEvents(),
            approvedAirportId);
    var days = trip.days().stream().sorted(Comparator.comparingInt(TripDay::dayNo)).toList();
    if (!trip.startDate().equals(days.getFirst().date())
        || !trip.endDate().equals(days.getLast().date())) throw invalid();
    if ((completedThroughDayNo == 0) != (trip.activeScheduleVersionId() == null)) throw invalid();
    var modes =
        trip.transportModes().stream()
            .sorted(Comparator.comparingInt(TripTransportMode::priority))
            .map(
                mode ->
                    switch (mode.mode()) {
                      case "public_transit" -> "bus";
                      case "taxi" -> "taxi";
                      case "walk" -> "walk";
                      default -> throw invalid();
                    })
            .toList();
    if (modes.isEmpty() || modes.size() > 3 || new HashSet<>(modes).size() != modes.size())
      throw invalid();
    var preferences =
        trip.placePreferences().stream()
            .sorted(Comparator.comparing(preference -> preference.placeId().toString()))
            .toList();
    if (preferences.stream().map(TripPlacePreference::placeId).distinct().count()
        != preferences.size()) throw invalid();
    var selected = new ArrayList<PlaceInput>();
    for (var preference : preferences) {
      if (!Set.of("must_visit", "preferred", "avoid").contains(preference.type())
          || (preference.targetDayNo() != null
              && (preference.targetDayNo() < 1 || preference.targetDayNo() > days.size())))
        throw invalid();
      if (preference.targetDayNo() != null && preference.targetDayNo() != boundary.dayNo())
        continue;
      if ("avoid".equals(preference.type())) {
        selected.add(
            new PlaceInput(
                preference.placeId(),
                preference.type(),
                preference.priority(),
                null,
                null,
                null,
                null));
        continue;
      }
      Integer minutes = preference.requestedStayMinutes();
      if (minutes != null) {
        if (minutes < 1 || minutes > 1440) throw invalid();
        selected.add(
            new PlaceInput(
                preference.placeId(),
                preference.type(),
                preference.priority(),
                minutes,
                "user_requested",
                null,
                null));
      } else {
        var stay = recommendations.get(preference.placeId());
        if (stay == null
            || stay.minutes() == null
            || stay.minutes() < 1
            || stay.minutes() > 1440
            || stay.source() == null
            || stay.source() == RecommendedStaySource.UNAVAILABLE
            || stay.policyVersion() == null
            || stay.policyVersion().isBlank()
            || stay.effectiveAt() == null) throw invalid();
        selected.add(
            new PlaceInput(
                preference.placeId(),
                preference.type(),
                preference.priority(),
                stay.minutes(),
                stay.source().value(),
                stay.policyVersion(),
                stay.effectiveAt()));
      }
    }
    long required = selected.stream().filter(place -> "must_visit".equals(place.type())).count();
    long preferred = selected.stream().filter(place -> "preferred".equals(place.type())).count();
    if (required > 10 || preferred > 30 || required + preferred == 0) throw invalid();
    return new GenerationTripInput(
        trip.tripId(),
        trip.revision(),
        trip.activeScheduleVersionId(),
        boundary,
        days,
        trip.plannerConditions().dayAnchors(),
        preferences,
        modes,
        trip.plannerConditions().styleCodes().stream()
            .filter(CATEGORIES::contains)
            .sorted()
            .toList(),
        trip.plannerConditions().styleCodes().contains("relaxed"),
        selected);
  }

  public record PlaceInput(
      UUID placeId,
      String type,
      int priority,
      Integer stayMinutes,
      String staySource,
      String stayPolicyVersion,
      Instant stayPolicyEffectiveAt) {}

  private static GenerationException invalid() {
    return GenerationException.inputConstraintViolation();
  }
}
