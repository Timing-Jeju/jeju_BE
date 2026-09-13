package com.timingjeju.api.application.generation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 검증된 선택 이동의 저장용 수치. 좌표·geometry·원본·선택하지 않은 대안은 복사하지 않는다. */
public record GenerationTransfer(
    String eventId,
    String mode,
    int distanceMeters,
    List<Walk> walks,
    List<Ride> rides,
    GenerationTotals.CostRange fare,
    List<String> evidenceFactIds) {
  public GenerationTransfer {
    walks = List.copyOf(walks);
    rides = List.copyOf(rides);
    evidenceFactIds = List.copyOf(evidenceFactIds);
  }

  /** Pydantic Schema·시간·장소 연속성 검증 이후에만 호출한다. */
  static List<GenerationTransfer> from(JsonNode candidate, Set<String> knownFacts) {
    var result = new ArrayList<GenerationTransfer>();
    for (var event : candidate.get("timeline")) {
      if (!"transfer".equals(event.path("type").asText())) continue;
      var transfer = event.get("transfer");
      var mode = text(transfer, "mode");
      var walks = new ArrayList<Walk>();
      var rides = new ArrayList<Ride>();
      GenerationTotals.CostRange fare;
      switch (mode) {
        case "walk" -> {
          walks.add(walk(transfer.get("direct_walk"), knownFacts));
          fare = new GenerationTotals.CostRange(0, 0, false);
        }
        case "bus" -> {
          walks.add(walk(transfer.get("access_walk"), knownFacts));
          for (var value : transfer.get("transfer_walks")) walks.add(walk(value, knownFacts));
          walks.add(walk(transfer.get("egress_walk"), knownFacts));
          for (var value : transfer.get("bus_rides")) {
            rides.add(
                new Ride(
                    text(value, "canonical_boarding_stop_id"),
                    text(value, "canonical_alighting_stop_id"),
                    OffsetDateTime.parse(text(value, "scheduled_departure_at")),
                    OffsetDateTime.parse(text(value, "scheduled_arrival_at")),
                    number(value, "boarding_buffer_minutes"),
                    references(value, knownFacts)));
          }
          // 일별 합계에서 개별 구간의 요금을 배분하거나 추정하지 않는다.
          var cost = transfer.path("mode_decision").path("bus_cost");
          fare = cost.isMissingNode() || cost.isNull() ? null : cost(cost);
        }
        case "taxi" -> {
          var taxi = transfer.get("taxi_alternative");
          fare =
              new GenerationTotals.CostRange(
                  number(taxi, "fare_min_krw"), number(taxi, "fare_max_krw"), true);
        }
        default -> throw invalid();
      }
      var ids = new java.util.LinkedHashSet<>(references(event, knownFacts, false));
      walks.forEach(walk -> ids.addAll(walk.evidenceFactIds()));
      rides.forEach(ride -> ids.addAll(ride.evidenceFactIds()));
      var decision = transfer.get("mode_decision");
      if (decision != null && !decision.isNull()) ids.addAll(references(decision, knownFacts));
      if (mode.equals("taxi")) ids.addAll(references(transfer.get("taxi_alternative"), knownFacts));
      result.add(
          new GenerationTransfer(
              text(event, "event_id"),
              mode,
              number(transfer, "distance_meters"),
              walks,
              rides,
              fare,
              List.copyOf(ids)));
    }
    return List.copyOf(result);
  }

  public record Walk(
      String kind, int plannedMinutes, int distanceMeters, List<String> evidenceFactIds) {
    public Walk {
      evidenceFactIds = List.copyOf(evidenceFactIds);
    }
  }

  public record Ride(
      String boardingStopId,
      String alightingStopId,
      OffsetDateTime departureAt,
      OffsetDateTime arrivalAt,
      int boardingBufferMinutes,
      List<String> evidenceFactIds) {
    public Ride {
      evidenceFactIds = List.copyOf(evidenceFactIds);
    }
  }

  private static Walk walk(JsonNode value, Set<String> knownFacts) {
    return new Walk(
        text(value, "kind"),
        number(value, "planned_minutes"),
        number(value, "distance_meters"),
        references(value, knownFacts));
  }

  private static GenerationTotals.CostRange cost(JsonNode value) {
    var estimated = value.get("is_estimated");
    if (estimated == null || !estimated.isBoolean()) throw invalid();
    return new GenerationTotals.CostRange(
        number(value, "min_krw"), number(value, "max_krw"), estimated.booleanValue());
  }

  private static List<String> references(JsonNode value, Set<String> knownFacts) {
    return references(value, knownFacts, true);
  }

  private static List<String> references(JsonNode value, Set<String> knownFacts, boolean required) {
    var ids = value.get("evidence_fact_ids");
    if (ids == null || !ids.isArray() || (required && ids.isEmpty())) throw invalid();
    var result = new ArrayList<String>();
    for (var id : ids) {
      if (!id.isTextual() || !knownFacts.contains(id.asText())) throw invalid();
      result.add(id.asText());
    }
    return List.copyOf(result);
  }

  private static String text(JsonNode value, String field) {
    var text = value.get(field);
    if (text == null || !text.isTextual() || text.asText().isBlank()) throw invalid();
    return text.asText();
  }

  private static int number(JsonNode value, String field) {
    var number = value.get(field);
    if (number == null
        || !number.isIntegralNumber()
        || !number.canConvertToInt()
        || number.intValue() < 0) throw invalid();
    return number.intValue();
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
