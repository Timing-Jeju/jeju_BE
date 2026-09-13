package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationEvidenceTest {
  @Test
  void 생성불가의_빈_ledger와_출처가_필요없는_정책_fact는_허용한다() {
    var mapper = JsonMapper.builder().build();
    var empty =
        mapper.readTree(
            """
        {"data_sources":[],"evidence_facts":[],"recommendations":[],"place_decisions":[]}
        """);
    assertThat(GenerationEvidence.from(empty, Set.of()).facts()).isEmpty();
    var policy =
        RESPONSE
            .replace("\"kind\":\"source\"", "\"kind\":\"policy\"")
            .replace("\"source_refs\":[{\"source_id\":\"approved\"}]", "\"source_refs\":[]");
    assertThat(GenerationEvidence.from(mapper.readTree(policy), Set.of("approved")).facts())
        .hasSize(2);
  }

  @Test
  void 계산_근거가_자신을_참조하는_순환은_유효한_증명으로_수용하지_않는다() {
    var mapper = JsonMapper.builder().build();
    var cyclic =
        RESPONSE.replace("\"input_fact_ids\":[\"route\"]", "\"input_fact_ids\":[\"total\"]");
    assertThatThrownBy(() -> GenerationEvidence.from(mapper.readTree(cyclic), Set.of("approved")))
        .hasMessage("MCP_CONTRACT_INVALID");
  }

  private static final String RESPONSE =
      """
      {"data_sources":[{"source_id":"approved"}],"evidence_facts":[
      {"fact_id":"route","value":{"raw":"보존 금지"},"source_refs":[{"source_id":"approved"}],
       "derivation":{"kind":"source","input_fact_ids":[]}},
      {"fact_id":"total","source_refs":[],"derivation":{"kind":"computed","input_fact_ids":["route"]}}],
       "recommendations":[{"timeline":[{"evidence_fact_ids":["total"]}]}],"place_decisions":[],
       "request":{"previous_days":[{"evidence_fact_ids":["previous-ledger"]}]}}
      """;

  @Test
  void 새_fact_ID는_응답의_출처와_참조가_닫혀_있을_때만_수용하고_값_원문은_복사하지_않는다() {
    var mapper = JsonMapper.builder().build();
    var evidence = GenerationEvidence.from(mapper.readTree(RESPONSE), Set.of("approved"));
    assertThat(evidence.facts()).containsOnlyKeys("route", "total");
    assertThat(evidence.facts().get("total").inputFactIds()).containsExactly("route");
    assertThat(mapper.writeValueAsString(evidence))
        .doesNotContain("보존 금지", "raw", "previous-ledger");
  }

  @Test
  void 미승인_출처와_미지_근거_및_중복_선언은_부분_수용하지_않는다() {
    var mapper = JsonMapper.builder().build();
    for (var invalid :
        new String[] {
          RESPONSE.replace("\"source_id\":\"approved\"", "\"source_id\":\"unapproved\""),
          RESPONSE.replace("\"input_fact_ids\":[\"route\"]", "\"input_fact_ids\":[\"unknown\"]"),
          RESPONSE.replace(
              "\"evidence_fact_ids\":[\"total\"]", "\"evidence_fact_ids\":[\"unknown\"]"),
          RESPONSE.replace("\"fact_id\":\"total\"", "\"fact_id\":\"route\""),
          RESPONSE.replace("\"source_refs\":[{\"source_id\":\"approved\"}]", "\"source_refs\":[]")
        }) {
      assertThatThrownBy(
              () -> GenerationEvidence.from(mapper.readTree(invalid), Set.of("approved")))
          .hasMessage("MCP_CONTRACT_INVALID");
    }
  }
}
