package com.timingjeju.api.application.generation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tools.jackson.databind.JsonNode;

/** Schema 검증 후 이동의 도보 산술과 시간표 연결을 검사한다. 원본은 보존하지 않는다. */
final class GenerationTransferTiming {
  private GenerationTransferTiming() {}

  static void validate(JsonNode candidate) {
    for (var event : candidate.get("timeline")) {
      if (!"transfer".equals(event.path("type").asText())) continue;
      var transfer = event.get("transfer");
      var start = time(event.get("start_at"));
      var end = time(event.get("end_at"));
      switch (transfer.path("mode").asText()) {
        case "walk" -> {
          var walk = transfer.get("direct_walk");
          if (!start.plusMinutes(walk(walk, "direct_walk")).equals(end)) throw invalid();
        }
        case "bus" -> bus(transfer, start, end);
        case "taxi" -> taxi(transfer, start, end);
        default -> throw invalid();
      }
    }
  }

  private static void taxi(JsonNode transfer, OffsetDateTime start, OffsetDateTime end) {
    var taxi = transfer.get("taxi_alternative");
    if (taxi == null || !taxi.isObject()) throw invalid();
    int minutes = number(taxi.get("duration_minutes"));
    int distance = number(taxi.get("distance_meters"));
    var estimated = taxi.get("is_estimated");
    if (minutes == 0
        || distance == 0
        || !start.plusMinutes(minutes).equals(end)
        || distance != number(transfer.get("distance_meters"))
        || number(taxi.get("fare_min_krw")) > number(taxi.get("fare_max_krw"))
        || estimated == null
        || !estimated.isBoolean()
        || !estimated.booleanValue()) throw invalid();
  }

  private static void bus(JsonNode transfer, OffsetDateTime start, OffsetDateTime end) {
    var rides = transfer.get("bus_rides");
    var transfers = transfer.get("transfer_walks");
    if (rides == null
        || !rides.isArray()
        || rides.isEmpty()
        || transfers == null
        || !transfers.isArray()
        || transfers.size() != rides.size() - 1) throw invalid();
    var access = transfer.get("access_walk");
    var egress = transfer.get("egress_walk");
    int egressMinutes = walk(egress, "egress_walk");
    var reached = start.plusMinutes(walk(access, "access_walk"));
    if (!text(access.get("to_id")).equals(text(rides.get(0).get("canonical_boarding_stop_id"))))
      throw invalid();
    long firstWait = 0;
    for (int index = 0; index < rides.size(); index++) {
      var ride = rides.get(index);
      var departure = time(ride.get("scheduled_departure_at"));
      var arrival = time(ride.get("scheduled_arrival_at"));
      var recommended = time(ride.get("recommended_stop_arrival_at"));
      int buffer = number(ride.get("boarding_buffer_minutes"));
      if (!"CONFIRMED".equals(ride.path("mapping_status").asText())
          || buffer == 0
          || !arrival.isAfter(departure)
          || recommended.isAfter(departure.minusMinutes(buffer))
          || reached.isAfter(recommended)) throw invalid();
      if (index == 0) {
        var waiting = Duration.between(reached, departure);
        // AI _select_route의 ModeDecision 표시값은 양수 대기의 완전한 분(floor)이다.
        firstWait = waiting.toMinutes();
      }
      reached = arrival;
      if (index + 1 < rides.size()) {
        var interchange = transfers.get(index);
        if (!text(interchange.get("from_id")).equals(text(ride.get("canonical_alighting_stop_id")))
            || !text(interchange.get("to_id"))
                .equals(text(rides.get(index + 1).get("canonical_boarding_stop_id"))))
          throw invalid();
        reached = reached.plusMinutes(walk(interchange, "transfer_walk"));
      }
    }
    if (!text(egress.get("from_id"))
            .equals(text(rides.get(rides.size() - 1).get("canonical_alighting_stop_id")))
        || !start
            .plusMinutes(ceilMinutes(Duration.between(start, reached.plusMinutes(egressMinutes))))
            .equals(end)) throw invalid();
    var decision = transfer.get("mode_decision");
    if (decision != null
        && !decision.isNull()
        && number(decision.get("bus_wait_minutes")) != firstWait) throw invalid();
  }

  private static long ceilMinutes(Duration duration) {
    if (duration.isNegative()) throw invalid();
    long minutes = duration.toMinutes();
    return minutes + (duration.minusMinutes(minutes).isZero() ? 0 : 1);
  }

  private static int walk(JsonNode walk, String kind) {
    if (walk == null || !walk.isObject() || !kind.equals(walk.path("kind").asText()))
      throw invalid();
    var multiplier = walk.get("speed_multiplier");
    if (multiplier == null
        || !multiplier.isNumber()
        || !Double.isFinite(multiplier.doubleValue())
        || multiplier.doubleValue() < 1) throw invalid();
    double planned =
        Math.ceil(number(walk.get("expected_minutes")) * multiplier.doubleValue())
            + number(walk.get("route_uncertainty_minutes"));
    int actual = number(walk.get("planned_minutes"));
    if (planned != actual) throw invalid();
    return actual;
  }

  private static int number(JsonNode value) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 0) throw invalid();
    return value.intValue();
  }

  private static String text(JsonNode value) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
    return value.asText();
  }

  private static OffsetDateTime time(JsonNode value) {
    try {
      var time = OffsetDateTime.parse(text(value));
      if (!time.getOffset().equals(ZoneOffset.ofHours(9))) throw invalid();
      return time;
    } catch (java.time.DateTimeException failure) {
      throw invalid();
    }
  }

  private static GenerationException invalid() {
    return GenerationException.invalidResult();
  }
}
