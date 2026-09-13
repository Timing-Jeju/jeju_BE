package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class GenerationCandidateSelectionTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void 성공과_실패_정보가_모순되면_Schema를_통과해도_거부한다() {
    var success = response();
    success.putObject("failure").put("code", "failed");
    assertThatThrownBy(() -> GenerationCandidateSelection.accepts(success, Set.of(), Set.of()))
        .hasMessage("MCP_CONTRACT_INVALID");
    var insufficient = response().put("status", "insufficient_feasible_routes");
    insufficient.putArray("recommendations");
    assertThatThrownBy(() -> GenerationCandidateSelection.accepts(insufficient, Set.of(), Set.of()))
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 주의_후보와_실제_체류시간_차이는_AI_다양성_계약대로_인정한다() {
    var response = response();
    var candidates = response.get("recommendations");
    for (int i = 0; i < 3; i++) {
      var candidate = (ObjectNode) candidates.get(i);
      candidate.set("place_ids", candidates.get(0).get("place_ids").deepCopy());
      candidate.put("feasibility", "feasible_with_caution");
      ((ObjectNode) candidate.get("timeline").get(0)).put("duration_minutes", 30 + 15 * i);
    }
    assertThat(GenerationCandidateSelection.accepts(response, Set.of("required"), Set.of()))
        .isTrue();
  }

  @Test
  void 중복_route_ID와_rank는_세_후보로_계수하지_않는다() {
    for (var field : new String[] {"route_id", "rank"}) {
      var response = response();
      var candidates = response.get("recommendations");
      ((ObjectNode) candidates.get(2)).set(field, candidates.get(0).get(field));
      assertThat(GenerationCandidateSelection.accepts(response, Set.of(), Set.of())).isFalse();
    }
  }

  @Test
  void 세_전략의_서로_다른_유효_후보만_전체_성공으로_수용한다() {
    assertThat(
            GenerationCandidateSelection.accepts(response(), Set.of("required"), Set.of("avoid")))
        .isTrue();
  }

  @Test
  void 후보_부족과_전략_중복_및_필수_누락과_회피_방문은_전체_실패다() {
    var partial = response();
    ((ArrayNode) partial.get("recommendations")).remove(2);
    var duplicate = response();
    ((ObjectNode) duplicate.get("recommendations").get(2)).put("strategy", "balanced");
    assertThat(GenerationCandidateSelection.accepts(partial, Set.of("required"), Set.of()))
        .isFalse();
    assertThat(GenerationCandidateSelection.accepts(duplicate, Set.of("required"), Set.of()))
        .isFalse();
    assertThat(GenerationCandidateSelection.accepts(response(), Set.of("missing"), Set.of()))
        .isFalse();
    assertThat(GenerationCandidateSelection.accepts(response(), Set.of(), Set.of("a"))).isFalse();
  }

  @Test
  void 같은_경로는_다른_ID와_전략만_붙여도_서로_다른_후보가_아니다() {
    var response = response();
    var candidates = response.get("recommendations");
    for (int i = 1; i < 3; i++) {
      ((ObjectNode) candidates.get(i))
          .set("place_ids", candidates.get(0).get("place_ids").deepCopy());
    }
    assertThat(GenerationCandidateSelection.accepts(response, Set.of(), Set.of())).isFalse();
  }

  @Test
  void 생성불가는_빈_후보만_허용하며_부분_결과를_성공시키지_않는다() {
    var response = response();
    response.put("status", "insufficient_feasible_routes");
    assertThatThrownBy(() -> GenerationCandidateSelection.accepts(response, Set.of(), Set.of()))
        .hasMessage("MCP_CONTRACT_INVALID");
    response.putArray("recommendations");
    response.putObject("failure").put("code", "insufficient_feasible_routes");
    assertThat(GenerationCandidateSelection.accepts(response, Set.of(), Set.of())).isFalse();
  }

  private ObjectNode response() {
    var response = mapper.createObjectNode().put("status", "success");
    var candidates = response.putArray("recommendations");
    var strategies = new String[] {"balanced", "relaxed", "experience_max"};
    var places = new String[] {"a", "b", "c"};
    for (int i = 0; i < 3; i++) {
      var candidate =
          candidates
              .addObject()
              .put("route_id", "route-" + i)
              .put("rank", i + 1)
              .put("strategy", strategies[i])
              .put("feasibility", "feasible");
      candidate.putArray("place_ids").add("required").add(places[i]);
      candidate.putArray("timeline").addObject().put("type", "visit").put("duration_minutes", 30);
    }
    return response;
  }
}
