package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationEntranceEvidenceTest {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private static final String SOURCE = "travel.place-entrance-map";

  @Test
  void 공항_대표좌표는_검증입구가_아닌_대표점으로만_허용한다() {
    var response =
        mapper.readTree(
            """
      {"data_sources":[{"source_id":"kac.airport"}],"evidence_facts":[
      {"fact_id":"kac.airport:CJU","category":"place","value":{},"source_refs":[{"source_id":"kac.airport"}],"derivation":{"kind":"source","input_fact_ids":[]}}],
      "recommendations":[],"place_decisions":[]}
      """);
    var evidence = GenerationEntranceEvidence.from(response, Set.of("kac.airport"));
    assertThat(
            evidence.requireEndpoint(
                "place-point:kac.airport:CJU", "kac.airport:CJU", Set.of("kac.airport:CJU")))
        .isTrue();
    assertThatThrownBy(
            () ->
                evidence.require("airport-entrance", "kac.airport:CJU", Set.of("kac.airport:CJU")))
        .isInstanceOf(GenerationException.class);
  }

  private static final String RESPONSE =
      """
      {"data_sources":[{"source_id":"travel.place-entrance-map"}],
       "evidence_facts":[{"fact_id":"entrance-fact","category":"place_entrance",
        "value":{"entrance_id":"entrance-123","place_id":"tourapi.place:123"},
        "source_refs":[{"source_id":"travel.place-entrance-map"}],
        "derivation":{"kind":"source","input_fact_ids":[]}}],
       "recommendations":[],"place_decisions":[]}
      """;

  @Test
  void 누락된_이동_endpoint도_안정적인_계약오류로_거부한다() {
    var bindings = GenerationEntranceEvidence.from(mapper.readTree(RESPONSE), Set.of(SOURCE));
    assertThatThrownBy(() -> bindings.require(null, "tourapi.place:123", Set.of("entrance-fact")))
        .isInstanceOf(GenerationException.class)
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 같은_입구의_상충하는_장소를_거부하고_같은_관계의_복수근거는_허용한다() {
    var response = (tools.jackson.databind.node.ObjectNode) mapper.readTree(RESPONSE);
    var facts = (tools.jackson.databind.node.ArrayNode) response.get("evidence_facts");
    var second = (tools.jackson.databind.node.ObjectNode) facts.get(0).deepCopy();
    second.put("fact_id", "second-fact");
    facts.add(second);
    var bindings = GenerationEntranceEvidence.from(response, Set.of(SOURCE));
    bindings.require("entrance-123", "tourapi.place:123", Set.of("second-fact"));
    ((tools.jackson.databind.node.ObjectNode) second.get("value"))
        .put("place_id", "tourapi.place:124");
    assertThatThrownBy(() -> GenerationEntranceEvidence.from(response, Set.of(SOURCE)))
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 승인된_입구와_장소_계보를_해당_이동의_근거와_함께_검증한다() {
    var bindings = GenerationEntranceEvidence.from(mapper.readTree(RESPONSE), Set.of(SOURCE));
    bindings.require("entrance-123", "tourapi.place:123", Set.of("entrance-fact"));
    assertThatThrownBy(
            () -> bindings.require("entrance-123", "tourapi.place:124", Set.of("entrance-fact")))
        .hasMessage("MCP_CONTRACT_INVALID");
    assertThatThrownBy(() -> bindings.require("entrance-123", "tourapi.place:123", Set.of()))
        .hasMessage("MCP_CONTRACT_INVALID");
    assertThatThrownBy(
            () -> bindings.require("unknown", "tourapi.place:123", Set.of("entrance-fact")))
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 장소연결_누락이나_다른_출처를_검증입구로_추정하지_않는다() {
    for (var invalid :
        new String[] {
          RESPONSE.replace(",\"place_id\":\"tourapi.place:123\"", ""),
          RESPONSE.replace("tourapi.place:123", "unknown:123"),
          RESPONSE.replace("\"kind\":\"source\"", "\"kind\":\"policy\""),
          RESPONSE.replace(SOURCE, "other-source")
        }) {
      assertThatThrownBy(
              () ->
                  GenerationEntranceEvidence.from(
                      mapper.readTree(invalid), Set.of(SOURCE, "other-source")))
          .hasMessage("MCP_CONTRACT_INVALID");
    }
  }
}
