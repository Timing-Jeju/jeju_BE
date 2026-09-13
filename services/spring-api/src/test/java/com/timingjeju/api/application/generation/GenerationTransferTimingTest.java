package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class GenerationTransferTimingTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void 하차도보_누락은_NPE가_아닌_계약오류로_거부한다() throws Exception {
    var candidate = candidate(2);
    ((ObjectNode) candidate.get("timeline").get(0).get("transfer")).remove("egress_walk");
    assertThatThrownBy(() -> GenerationTransferTiming.validate(candidate))
        .isInstanceOf(GenerationException.class)
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 초단위_출발은_AI와_동일하게_전체_버스분을_올림한다() throws Exception {
    var candidate = candidate(2);
    var event = (ObjectNode) candidate.get("timeline").get(0);
    event.put("start_at", "2026-08-15T09:00:30+09:00");
    event.put("end_at", "2026-08-15T09:40:30+09:00");
    ((ObjectNode) event.get("transfer").get("mode_decision")).put("bus_wait_minutes", 14);
    assertThatCode(() -> GenerationTransferTiming.validate(candidate)).doesNotThrowAnyException();
  }

  @Test
  void 환승도보는_앞차의_하차와_뒷차의_승차를_시간과_정류장으로_연결한다() throws Exception {
    var candidate = twoRides();
    GenerationTransferTiming.validate(candidate);
    for (var mutation : List.of("from_id", "to_id", "planned_minutes", "count", "buffer")) {
      var changed = candidate.deepCopy();
      var transfer = changed.get("timeline").get(0).get("transfer");
      var walk = (ObjectNode) transfer.get("transfer_walks").get(0);
      switch (mutation) {
        case "from_id", "to_id" -> walk.put(mutation, "wrong-stop");
        case "planned_minutes" -> walk.put(mutation, 2);
        case "count" -> ((ArrayNode) transfer.get("transfer_walks")).removeAll();
        case "buffer" ->
            ((ObjectNode) transfer.get("bus_rides").get(1))
                .put("recommended_stop_arrival_at", "2026-08-15T09:25:00+09:00");
        default -> throw new AssertionError();
      }
      assertThatThrownBy(() -> GenerationTransferTiming.validate(changed))
          .as(mutation)
          .hasMessage("MCP_CONTRACT_INVALID");
    }
  }

  @Test
  void 직접도보의_계획시간과_이벤트시간이_다르면_거부한다() throws Exception {
    var candidate = candidate(0);
    GenerationTransferTiming.validate(candidate);
    ((ObjectNode) candidate.get("timeline").get(0).get("transfer").get("direct_walk"))
        .put("planned_minutes", 16);
    assertThatThrownBy(() -> GenerationTransferTiming.validate(candidate))
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  private ObjectNode twoRides() throws Exception {
    var candidate = candidate(2);
    var transfer = candidate.get("timeline").get(0).get("transfer");
    var rides = (ArrayNode) transfer.get("bus_rides");
    var first = (ObjectNode) rides.get(0);
    var second = first.deepCopy();
    first.put("scheduled_arrival_at", "2026-08-15T09:25:00+09:00");
    first.put("canonical_alighting_stop_id", "interchange-alight");
    second.put("canonical_boarding_stop_id", "interchange-board");
    second.put("scheduled_departure_at", "2026-08-15T09:30:00+09:00");
    second.put("recommended_stop_arrival_at", "2026-08-15T09:28:00+09:00");
    second.put("boarding_buffer_minutes", 2);
    rides.add(second);
    var walk = (ObjectNode) transfer.get("access_walk").deepCopy();
    walk.put("kind", "transfer_walk");
    walk.put("from_id", "interchange-alight");
    walk.put("to_id", "interchange-board");
    walk.put("expected_minutes", 0);
    walk.put("planned_minutes", 1);
    ((ArrayNode) transfer.get("transfer_walks")).add(walk);
    return candidate;
  }

  private ObjectNode candidate(int index) throws Exception {
    try (var stream = getClass().getResourceAsStream("/mcp/generation-v07.synthetic-output.json")) {
      return (ObjectNode)
          mapper
              .readTree(java.util.Objects.requireNonNull(stream))
              .get("recommendations")
              .get(index);
    }
  }
}
