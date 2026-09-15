package com.timingjeju.api.application.generation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** AI Schema 및 근거 계보 검증 이후 추출하는 내부 저장용 타임라인. 공개 JSON 계약이 아니다. */
public record GenerationTimeline(List<Event> events, List<Risk> risks) {
  private static final Set<String> TYPES = Set.of("visit", "transfer", "rest", "meal", "buffer");
  private static final Set<String> STAYS = Set.of("visit", "rest", "meal");

  public GenerationTimeline {
    events = List.copyOf(events);
    risks = List.copyOf(risks);
  }

  public static GenerationTimeline from(
      JsonNode candidate, Scope scope, GenerationPlaceBindings bindings) {
    if (!text(candidate.get("start_place_id")).equals(scope.startPlaceId())
        || !text(candidate.get("end_place_id")).equals(scope.endPlaceId())) throw invalid();
    bindings.canonicalId(scope.startPlaceId());
    bindings.canonicalId(scope.endPlaceId());
    var events = new ArrayList<Event>();
    var eventIds = new HashSet<String>();
    var totals = new HashMap<String, Integer>();
    var visited = new ArrayList<String>();
    for (var value : array(candidate.get("timeline"))) {
      var id = text(value.get("event_id"));
      var type = text(value.get("type"));
      var sequence = integer(value.get("sequence"));
      var start = time(value.get("start_at"));
      var end = time(value.get("end_at"));
      var duration = integer(value.get("duration_minutes"));
      if (!eventIds.add(id)
          || !TYPES.contains(type)
          || sequence < 1
          || duration < 0
          || !Duration.between(start, end).equals(Duration.ofMinutes(duration))
          || start.isBefore(scope.startAt())
          || end.isAfter(scope.endAt())
          || !start.toLocalDate().equals(scope.startAt().toLocalDate())
          || !end.toLocalDate().equals(scope.startAt().toLocalDate())) throw invalid();
      if (!events.isEmpty()) {
        var previous = events.getLast();
        if (previous.sequence() >= sequence || previous.endAt().isAfter(start)) throw invalid();
      }
      String factPlaceId = nullableText(value.get("place_id"));
      UUID placeId = factPlaceId == null ? null : bindings.canonicalId(factPlaceId);
      for (var detailType : STAYS) {
        var detail = value.get(detailType);
        boolean present = detail != null && !detail.isNull();
        if (type.equals(detailType) != present) throw invalid();
        if (present && !text(detail.get("place_id")).equals(factPlaceId)) throw invalid();
      }
      if (STAYS.contains(type)) {
        if (factPlaceId == null) throw invalid();
        visited.add(factPlaceId);
        var requested = scope.stayMinutes().get(factPlaceId);
        if (requested != null && requested != duration) throw invalid();
      }
      if (type.equals("visit")) {
        var visit = value.get("visit");
        if (!time(visit.get("arrival_at")).equals(start)
            || !time(visit.get("entry_at")).equals(start)
            || !time(visit.get("departure_at")).equals(end)
            || integer(visit.get("stay_minutes")) != duration) throw invalid();
      }
      var transfer = value.get("transfer");
      boolean hasTransfer = transfer != null && !transfer.isNull();
      if (type.equals("transfer") != hasTransfer) throw invalid();
      String mode = hasTransfer ? text(transfer.get("mode")) : null;
      Integer distance = hasTransfer ? integer(transfer.get("distance_meters")) : null;
      if (hasTransfer && (!scope.allowedModes().contains(mode) || distance < 0)) throw invalid();
      var eventFacts =
          new java.util.LinkedHashSet<>(references(value.get("evidence_fact_ids"), scope));
      if (STAYS.contains(type))
        eventFacts.addAll(references(value.get(type).get("evidence_fact_ids"), scope));
      events.add(
          new Event(
              id,
              sequence,
              type,
              start,
              end,
              duration,
              placeId,
              factPlaceId,
              mode,
              distance,
              List.copyOf(eventFacts)));
      totals.merge(type, duration, Math::addExact);
    }
    if (!visited.equals(strings(candidate.get("place_ids")))
        || events.isEmpty()
        || !events.getFirst().startAt().equals(time(candidate.get("day_start_at")))
        || !events.getLast().endAt().equals(time(candidate.get("day_end_at")))) throw invalid();
    var summary = candidate.get("totals");
    int total = 0;
    for (var type : TYPES) {
      int minutes = totals.getOrDefault(type, 0);
      if (integer(summary.get(type + "_minutes")) != minutes) throw invalid();
      total = Math.addExact(total, minutes);
    }
    if (integer(summary.get("total_minutes")) != total) throw invalid();
    var risks = new ArrayList<Risk>();
    var riskEvents = new HashSet<String>();
    for (var value : array(candidate.get("segment_risks"))) {
      var id = text(value.get("event_id"));
      var level = text(value.get("risk"));
      if (!eventIds.contains(id)
          || !riskEvents.add(id)
          || !Set.of("critical", "high", "medium", "low", "unknown").contains(level))
        throw invalid();
      var slack = value.get("slack_minutes");
      risks.add(
          new Risk(
              id,
              level,
              slack == null || slack.isNull() ? null : integer(slack),
              strings(value.get("reason_codes")),
              references(value.get("evidence_fact_ids"), scope)));
    }
    return new GenerationTimeline(events, risks);
  }

  public record Scope(
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      String startPlaceId,
      String endPlaceId,
      Set<String> allowedModes,
      Map<String, Integer> stayMinutes,
      Set<String> factIds) {
    public Scope {
      allowedModes = Set.copyOf(allowedModes);
      stayMinutes = Map.copyOf(stayMinutes);
      factIds = Set.copyOf(factIds);
    }
  }

  public record Event(
      String eventId,
      int sequence,
      String type,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      int durationMinutes,
      UUID placeId,
      String placeFactId,
      String mode,
      Integer distanceMeters,
      List<String> evidenceFactIds) {
    public Event {
      evidenceFactIds = List.copyOf(evidenceFactIds);
    }
  }

  public record Risk(
      String eventId,
      String level,
      Integer slackMinutes,
      List<String> reasonCodes,
      List<String> evidenceFactIds) {
    public Risk {
      reasonCodes = List.copyOf(reasonCodes);
      evidenceFactIds = List.copyOf(evidenceFactIds);
    }
  }

  private static List<String> references(JsonNode value, Scope scope) {
    var ids = strings(value);
    if (!scope.factIds().containsAll(ids)) throw invalid();
    return ids;
  }

  private static List<String> strings(JsonNode value) {
    var result = new ArrayList<String>();
    for (var item : array(value)) result.add(text(item));
    return List.copyOf(result);
  }

  private static JsonNode array(JsonNode value) {
    if (value == null || !value.isArray()) throw invalid();
    return value;
  }

  private static String nullableText(JsonNode value) {
    return value == null || value.isNull() ? null : text(value);
  }

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static int integer(JsonNode value) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
    return value.intValue();
  }

  private static OffsetDateTime time(JsonNode value) {
    try {
      var parsed = OffsetDateTime.parse(text(value));
      if (!parsed.getOffset().equals(ZoneOffset.ofHours(9))) throw invalid();
      return parsed;
    } catch (java.time.DateTimeException failure) {
      throw invalid();
    }
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
