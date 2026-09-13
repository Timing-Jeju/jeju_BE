package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class JejuGenerationCandidateProjectionTest {
  @Test
  void 점수_합계와_가중치_합이_다르면_계약오류로_거부한다() throws Exception {
    var response = response();
    ((ObjectNode) response.get("recommendations").get(0).get("score")).put("total", 1.01);
    assertThatThrownBy(() -> project(response)).hasMessage("MCP_CONTRACT_INVALID");
    var wrongWeights = response();
    for (var component :
        wrongWeights.get("recommendations").get(0).get("score").get("components").properties())
      ((ObjectNode) component.getValue()).put("weight", 0);
    assertThatThrownBy(() -> project(wrongWeights)).hasMessage("MCP_CONTRACT_INVALID");
  }

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final TypeReference<Map<String, Object>> maps = new TypeReference<>() {};

  @Test
  void 실제_AI_생성_Schema와_합성응답을_SDK에서_세_후보로_추출한다() throws Exception {
    var outputSchema = mapper.readValue(resource("generation-v07.output-schema.json"), maps);
    assertThat(McpSchemaFingerprint.sha256(outputSchema, mapper))
        .isEqualTo(
            McpExpectedCatalog.load(mapper)
                .tools()
                .get("recommend_jeju_day_trips")
                .outputSchemaSha256());
    var sdk = mock(McpSyncClient.class);
    Map<String, Object> inputSchema = Map.of("type", "object");
    when(sdk.isInitialized()).thenReturn(true);
    when(sdk.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool(
                        "recommend_jeju_day_trips",
                        null,
                        null,
                        inputSchema,
                        outputSchema,
                        null,
                        null)),
                null));
    when(sdk.callTool(any()))
        .thenReturn(
            new McpSchema.CallToolResult(
                List.of(), false, mapper.convertValue(response(), maps), Map.of()));
    var client =
        new SpringAiJejuMcpClient(
            sdk,
            McpContractGuard.forSingleTool(
                mapper, "recommend_jeju_day_trips", inputSchema, outputSchema),
            mapper,
            new SimpleMeterRegistry(),
            McpCallResilience.defaults(),
            mock(McpCallAuditWriter.class));
    client.verifyServerContract();
    var result =
        client.callGeneration(
            new McpInvocation(
                "recommend_jeju_day_trips",
                "generation-79",
                Map.of(),
                "a".repeat(64),
                McpCallParent.forGenerationRun(UUID.randomUUID()),
                Map.of(),
                Map.of()),
            content -> project((ObjectNode) mapper.valueToTree(content)));
    assertThat(result.projection().outcome()).isEqualTo("success");
    assertThat(result.projection().candidates())
        .hasSize(3)
        .extracting(GenerationCandidateProjection.Candidate::rank)
        .containsExactly(1, 2, 3);
    assertThat(mapper.writeValueAsString(result.projection()))
        .doesNotContain("geometry", "title", "source_refs", "formula", "coordinates");
    verify(sdk).callTool(any());
  }

  @Test
  void 하나의_후보만_부적합해도_모든_후보를_폐기한다() throws Exception {
    var response = response();
    ((ObjectNode) response.get("recommendations").get(2).get("timeline").get(0))
        .put("duration_minutes", 999);
    assertThat(project(response).outcome()).isEqualTo("insufficient_feasible_routes");
    assertThat(project(response).candidates()).isEmpty();
  }

  @Test
  void 생성불가와_부분후보는_빈_결과이며_미지근거는_계약오류다() throws Exception {
    var response = response();
    ((tools.jackson.databind.node.ArrayNode) response.get("recommendations")).remove(2);
    assertThat(project(response).candidates()).isEmpty();
    response.put("status", "insufficient_feasible_routes");
    response.putArray("recommendations");
    response
        .putObject("failure")
        .put("code", "insufficient_feasible_routes")
        .put("message", "합성 실패");
    assertThat(project(response).outcome()).isEqualTo("insufficient_feasible_routes");
    var invalid = response();
    ((ObjectNode) invalid.get("recommendations").get(0).get("timeline").get(0))
        .putArray("evidence_fact_ids")
        .add("unknown");
    assertThatThrownBy(() -> project(invalid)).hasMessage("MCP_CONTRACT_INVALID");
  }

  private GenerationCandidateProjection project(ObjectNode response) {
    var places = new ArrayList<GenerationPlaceBindings.Place>();
    for (int n = 1; n <= 6; n++)
      places.add(
          new GenerationPlaceBindings.Place(new UUID(79, n), Integer.toString(n), "합성 공식 장소"));
    var scope =
        new GenerationTimeline.Scope(
            OffsetDateTime.parse("2026-08-15T09:00:00+09:00"),
            OffsetDateTime.parse("2026-08-15T20:00:00+09:00"),
            "tourapi.place:1",
            "tourapi.place:1",
            Set.of("walk", "bus", "taxi"),
            Map.of(),
            Set.of());
    return GenerationCandidateProjection.from(
        response,
        scope,
        Set.of("tourapi.place:2"),
        Set.of(),
        Set.of(),
        new GenerationPlaceBindings(places));
  }

  private ObjectNode response() throws Exception {
    var response = (ObjectNode) mapper.readTree(resource("generation-v07.synthetic-output.json"));
    canonicalizeIds(response);
    return response;
  }

  private void canonicalizeIds(tools.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var object = (ObjectNode) node;
      for (var entry : List.copyOf(object.properties())) {
        if (entry.getKey().endsWith("_id") && entry.getValue().isTextual())
          object.put(entry.getKey(), canonicalId(entry.getValue().asText()));
        else if (entry.getKey().endsWith("_ids") && entry.getValue().isArray()) {
          var ids = (tools.jackson.databind.node.ArrayNode) entry.getValue();
          for (int n = 0; n < ids.size(); n++)
            if (ids.get(n).isTextual())
              ids.set(n, mapper.getNodeFactory().stringNode(canonicalId(ids.get(n).asText())));
        } else canonicalizeIds(entry.getValue());
      }
    } else if (node.isArray()) node.forEach(this::canonicalizeIds);
  }

  private String canonicalId(String value) {
    int index = List.of("hotel", "required", "a", "b", "meal", "rest").indexOf(value);
    return index < 0 ? value : "tourapi.place:" + (index + 1);
  }

  private String resource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/mcp/" + name)) {
      return new String(
          java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
