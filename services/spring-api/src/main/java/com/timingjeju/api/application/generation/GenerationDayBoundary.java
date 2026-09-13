package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.transportevent.TransportEvent;
import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripPlannerConditions;
import com.timingjeju.api.application.trip.TripTransportEvents;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 저장된 계획 anchor와 항공 시각만 사용한다. 사용자 원문이나 좌표는 포함하지 않는다. */
public record GenerationDayBoundary(
    UUID dayId,
    int dayNo,
    UUID startPlaceId,
    UUID endPlaceId,
    OffsetDateTime startAt,
    OffsetDateTime endAt) {
  private static final ZoneOffset KST = ZoneOffset.ofHours(9);

  public static GenerationDayBoundary resolve(
      List<TripDay> savedDays,
      UUID targetDayId,
      int completedThroughDayNo,
      TripPlannerConditions conditions,
      TripTransportEvents events,
      UUID approvedAirportId) {
    if (savedDays == null
        || savedDays.isEmpty()
        || savedDays.size() > 5
        || savedDays.stream().anyMatch(java.util.Objects::isNull)
        || completedThroughDayNo < 0
        || completedThroughDayNo >= savedDays.size()
        || conditions == null
        || events == null
        || approvedAirportId == null) throw invalid();
    var days = savedDays.stream().sorted(Comparator.comparingInt(TripDay::dayNo)).toList();
    var ids = new HashSet<UUID>();
    for (int index = 0; index < days.size(); index++) {
      var day = days.get(index);
      if (day.dayId() == null
          || !ids.add(day.dayId())
          || day.dayNo() != index + 1
          || day.date() == null
          || day.activityStartTime() == null
          || day.activityEndTime() == null
          || !day.activityStartTime().isBefore(day.activityEndTime())
          || (index > 0 && !day.date().equals(days.get(0).date().plusDays(index)))) throw invalid();
    }
    var target = days.get(completedThroughDayNo);
    if (!target.dayId().equals(targetDayId)) throw invalid();
    var arrival = flight(events.arrival(), "arrival", approvedAirportId);
    var departure = flight(events.departure(), "departure", approvedAirportId);
    if (!arrival.toLocalDate().equals(days.get(0).date())
        || !departure.toLocalDate().equals(days.get(days.size() - 1).date())
        || !arrival.isBefore(departure)) throw invalid();
    if (conditions.dayAnchors().stream().anyMatch(anchor -> !ids.contains(anchor.dayId())))
      throw invalid();
    // 여행 전체의 숙박 연결을 접수 시 확정하여 다음 Day에서 숨은 입력 누락이 생기지 않게 한다.
    for (int index = 0; index < days.size() - 1; index++)
      lodging(conditions, days.get(index).dayId());
    boolean first = target.dayNo() == 1;
    boolean last = target.dayNo() == days.size();
    UUID startPlace =
        first ? approvedAirportId : lodging(conditions, days.get(target.dayNo() - 2).dayId());
    UUID endPlace = last ? approvedAirportId : lodging(conditions, target.dayId());
    var start = target.date().atTime(target.activityStartTime()).atOffset(KST);
    var end = target.date().atTime(target.activityEndTime()).atOffset(KST);
    if (first && arrival.isAfter(start)) start = arrival;
    if (last && departure.isBefore(end)) end = departure;
    if (!start.isBefore(end)) throw invalid();
    return new GenerationDayBoundary(
        target.dayId(), target.dayNo(), startPlace, endPlace, start, end);
  }

  private static OffsetDateTime flight(TransportEvent event, String type, UUID airport) {
    if (event == null
        || !type.equals(event.eventType())
        || !"flight".equals(event.transportType())
        || event.scheduledAt() == null
        || event.customTerminalName() != null
        || (event.terminalPlaceId() != null && !airport.equals(event.terminalPlaceId())))
      throw invalid();
    return event.scheduledAt().withOffsetSameInstant(KST);
  }

  private static UUID lodging(TripPlannerConditions conditions, UUID dayId) {
    return conditions.dayAnchors().stream()
        .filter(anchor -> dayId.equals(anchor.dayId()))
        .map(TripPlannerConditions.DayAnchor::lodgingPlaceId)
        .findFirst()
        .orElseThrow(GenerationDayBoundary::invalid);
  }

  private static GenerationException invalid() {
    return GenerationException.inputConstraintViolation();
  }
}
