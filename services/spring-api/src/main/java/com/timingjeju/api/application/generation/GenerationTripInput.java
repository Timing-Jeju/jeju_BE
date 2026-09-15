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
    UUID airportPlaceId,
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
    validateDays(boundary, baseScheduleVersionId, airportPlaceId, days, dayAnchors);
    if (transportModes.isEmpty()
        || transportModes.size() > 3
        || new HashSet<>(transportModes).size() != transportModes.size()
        || !Set.of("bus", "taxi", "walk").containsAll(transportModes)
        || new HashSet<>(preferredCategories).size() != preferredCategories.size()
        || !CATEGORIES.containsAll(preferredCategories)) throw invalid();
    validatePlaces(boundary.dayNo(), days.size(), savedPreferences, places);
  }

  private static void validateDays(
      GenerationDayBoundary boundary,
      UUID base,
      UUID airport,
      List<TripDay> days,
      List<TripPlannerConditions.DayAnchor> anchors) {
    if (airport == null
        || days.isEmpty()
        || days.size() > 5
        || boundary.dayNo() < 1
        || boundary.dayNo() > days.size()
        || (boundary.dayNo() > 1 && base == null)) throw invalid();
    var ids = new HashSet<UUID>();
    for (int n = 0; n < days.size(); n++) {
      var day = days.get(n);
      if (day.dayId() == null
          || !ids.add(day.dayId())
          || day.dayNo() != n + 1
          || day.date() == null
          || day.activityStartTime() == null
          || day.activityEndTime() == null
          || !day.activityStartTime().isBefore(day.activityEndTime())
          || (n > 0 && !day.date().equals(days.getFirst().date().plusDays(n)))) throw invalid();
    }
    var target = days.get(boundary.dayNo() - 1);
    var kst = java.time.ZoneOffset.ofHours(9);
    if (!target.dayId().equals(boundary.dayId())
        || boundary.startAt() == null
        || boundary.endAt() == null
        || !boundary.startAt().getOffset().equals(kst)
        || !boundary.endAt().getOffset().equals(kst)
        || !boundary.startAt().toLocalDate().equals(target.date())
        || !boundary.endAt().toLocalDate().equals(target.date())
        || !boundary.startAt().isBefore(boundary.endAt())
        || boundary.startAt().toLocalTime().isBefore(target.activityStartTime())
        || boundary.endAt().toLocalTime().isAfter(target.activityEndTime())) throw invalid();
    var lodging = new HashMap<UUID, UUID>();
    for (var anchor : anchors) {
      if (!ids.contains(anchor.dayId())
          || lodging.put(anchor.dayId(), anchor.lodgingPlaceId()) != null) throw invalid();
    }
    for (int n = 0; n < days.size() - 1; n++)
      if (!lodging.containsKey(days.get(n).dayId())) throw invalid();
    UUID start =
        boundary.dayNo() == 1 ? airport : lodging.get(days.get(boundary.dayNo() - 2).dayId());
    UUID end = boundary.dayNo() == days.size() ? airport : lodging.get(boundary.dayId());
    if (!Objects.equals(start, boundary.startPlaceId())
        || !Objects.equals(end, boundary.endPlaceId())) throw invalid();
  }

  private static void validatePlaces(
      int dayNo, int dayCount, List<TripPlacePreference> preferences, List<PlaceInput> places) {
    var allIds = new HashSet<UUID>();
    var expected = new HashMap<UUID, TripPlacePreference>();
    for (var preference : preferences) {
      if (preference.placeId() == null
          || !allIds.add(preference.placeId())
          || preference.type() == null
          || !Set.of("must_visit", "preferred", "avoid").contains(preference.type())
          || preference.priority() < 0
          || preference.priority() > 100
          || (preference.targetDayNo() != null
              && (preference.targetDayNo() < 1 || preference.targetDayNo() > dayCount))
          || (preference.requestedStayMinutes() != null
              && (preference.requestedStayMinutes() < 1
                  || preference.requestedStayMinutes() > 1440))) throw invalid();
      if (preference.targetDayNo() == null || preference.targetDayNo() == dayNo)
        expected.put(preference.placeId(), preference);
    }
    if (places.size() != expected.size()) throw invalid();
    var seen = new HashSet<UUID>();
    int required = 0, preferred = 0;
    for (var place : places) {
      var original = expected.get(place.placeId());
      if (original == null
          || !seen.add(place.placeId())
          || !original.type().equals(place.type())
          || original.priority() != place.priority()) throw invalid();
      if ("avoid".equals(place.type())) {
        if (place.stayMinutes() != null
            || place.staySource() != null
            || place.stayPolicyVersion() != null
            || place.stayPolicyEffectiveAt() != null) throw invalid();
        continue;
      }
      if ("must_visit".equals(place.type())) required++;
      else preferred++;
      if (place.stayMinutes() == null || place.stayMinutes() < 1 || place.stayMinutes() > 1440)
        throw invalid();
      if (original.requestedStayMinutes() != null) {
        if (!original.requestedStayMinutes().equals(place.stayMinutes())
            || !"user_requested".equals(place.staySource())
            || place.stayPolicyVersion() != null
            || place.stayPolicyEffectiveAt() != null) throw invalid();
      } else if (place.staySource() == null
          || !Set.of("place_override", "category_default").contains(place.staySource())
          || place.stayPolicyVersion() == null
          || !place.stayPolicyVersion().matches("[a-z0-9][a-z0-9._-]{0,63}")
          || place.stayPolicyEffectiveAt() == null) throw invalid();
    }
    if (required > 10 || preferred > 30 || required + preferred == 0) throw invalid();
  }

  /** Pydantic previous_days의 날짜·필수방문 충돌을 외부 호출 전에 확인한다. */
  public Set<UUID> validatePreviousDays(List<GenerationSelectedDay> previousDays) {
    if (previousDays.size() != boundary.dayNo() - 1) throw invalid();
    var visited = new HashSet<UUID>();
    for (int i = 0; i < previousDays.size(); i++) {
      var previous = previousDays.get(i);
      var day = days.get(i);
      if (!previous.dayId().equals(day.dayId()) || !previous.tripDate().equals(day.date()))
        throw invalid();
      previous.selectedPlaces().stream()
          .filter(place -> place.role().equals("visit"))
          .forEach(place -> visited.add(place.canonicalPlaceId()));
    }
    if (places.stream()
        .anyMatch(place -> place.type().equals("must_visit") && visited.contains(place.placeId())))
      throw invalid();
    return Set.copyOf(visited);
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
    if (completedThroughDayNo > 0 && trip.activeScheduleVersionId() == null) throw invalid();
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
        approvedAirportId,
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
