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
  void 버스_시간표와_접근도보_산술이_틀리면_부분후보를_노출하지_않는다() throws Exception {
    for (var field : List.of("planned_minutes", "to_id", "wait", "arrival", "egress")) {
      var result = response();
      var transfer =
          (ObjectNode) result.get("recommendations").get(2).get("timeline").get(0).get("transfer");
      switch (field) {
        case "planned_minutes" -> ((ObjectNode) transfer.get("access_walk")).put(field, 6);
        case "to_id" -> ((ObjectNode) transfer.get("access_walk")).put(field, "wrong-stop");
        case "wait" -> ((ObjectNode) transfer.get("mode_decision")).put("bus_wait_minutes", 8);
        case "egress" -> transfer.remove("egress_walk");
        case "arrival" ->
            ((ObjectNode) transfer.get("bus_rides").get(0))
                .put("scheduled_arrival_at", "2026-08-15T09:36:00+09:00");
        default -> throw new AssertionError();
      }
      var projection = project(result);
      assertThat(projection.outcome()).as(field).isEqualTo("insufficient_feasible_routes");
      assertThat(projection.candidates()).isEmpty();
    }
  }

  @Test
  void 저장용_합계는_검증된_거리와_비용범위_및_근거만_보존한다() throws Exception {
    var totals = project(response()).candidates().getFirst().totals();
    assertThat(totals.totalMinutes()).isEqualTo(450);
    assertThat(totals.totalDistanceMeters()).isEqualTo(3000);
    assertThat(totals.walkingMinutes()).isEqualTo(90);
    assertThat(totals.estimatedCost().minKrw()).isZero();
    assertThat(totals.evidenceFactIds()).isNotEmpty();
    assertThat(mapper.writeValueAsString(totals))
        .doesNotContain("geometry", "formula", "coordinates", "source_refs");
  }

  @Test
  void 거리합계와_비용합계가_일치하지_않으면_세후보를_모두_거부한다() throws Exception {
    var distance = response();
    ((ObjectNode) distance.get("recommendations").get(2).get("totals"))
        .put("total_distance_meters", 1);
    assertThatThrownBy(() -> project(distance)).hasMessage("MCP_CONTRACT_INVALID");
    var cost = response();
    ((ObjectNode) cost.get("recommendations").get(2).get("totals").get("estimated_cost"))
        .put("max_krw", 100);
    assertThatThrownBy(() -> project(cost)).hasMessage("MCP_CONTRACT_INVALID");
  }

  @Test
  void 저장된_체류시간과_회피_선호를_실제_결과_검증에_적용한다() throws Exception {
    assertThat(
            GenerationCandidateProjection.from(response(), input(30, false), bindings(), Set.of())
                .outcome())
        .isEqualTo("insufficient_feasible_routes");
    assertThat(
            GenerationCandidateProjection.from(response(), input(60, true), bindings(), Set.of())
                .outcome())
        .isEqualTo("insufficient_feasible_routes");
    assertThat(
            GenerationCandidateProjection.from(response(), input(60, false), bindings(), Set.of())
                .outcome())
        .isEqualTo("success");
  }

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
    Map<String, Object> inputSchema =
        mapper.readValue(resource("generation-v07.input-schema.json"), maps);
    assertThat(McpSchemaFingerprint.sha256(inputSchema, mapper))
        .isEqualTo(
            McpExpectedCatalog.load(mapper)
                .tools()
                .get("recommend_jeju_day_trips")
                .inputSchemaSha256());
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
    var runId = UUID.randomUUID();
    var snapshot =
        GenerationTripSnapshot.create(runId, UUID.randomUUID(), input(60, false), mapper);
    var snapshots = mock(GenerationTripInputRepository.class);
    when(snapshots.find(runId)).thenReturn(java.util.Optional.of(snapshot));
    var commands =
        mock(com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository.class);
    var parent =
        new com.timingjeju.api.application.commandinput.CommandInputParent.Generation(runId);
    var command =
        new com.timingjeju.api.application.commandinput.CommandInputCanonicalizer(mapper)
            .canonicalize(
                new com.timingjeju.api.application.commandinput.CommandInputRequest(
                    parent,
                    "itinerary_generation",
                    2,
                    "0.7.0",
                    "generation-v1",
                    mapper.valueToTree(
                        Map.of(
                            "targetDayId",
                            snapshot.input().boundary().dayId().toString(),
                            "candidateCount",
                            3,
                            "refreshExternalFacts",
                            false)),
                    snapshot.ownerId(),
                    snapshot.input().tripId(),
                    null));
    when(commands.find(parent)).thenReturn(java.util.Optional.of(command));
    var places = mock(GenerationPlaceResolver.class);
    when(places.resolve(anySet(), any())).thenReturn(bindings());
    when(places.resolveFactIds(anySet(), any())).thenReturn(bindings());
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-14T00:00:00Z"), java.time.ZoneOffset.UTC);
    var executor =
        new McpGenerationExecutor(snapshots, commands, places, client, mapper, clock, Set.of());
    var result = executor.execute(runId, clock.instant().plusSeconds(180));
    assertThat(result.outcome()).isEqualTo("success");
    assertThat(result.candidates())
        .hasSize(3)
        .extracting(GenerationCandidateProjection.Candidate::rank)
        .containsExactly(1, 2, 3);
    assertThat(mapper.writeValueAsString(result))
        .doesNotContain("geometry", "title", "source_refs", "formula", "coordinates");
    var wire = org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(sdk).callTool(wire.capture());
    assertThat(wire.getValue().arguments()).containsKey("request").containsKey("inputHash");
    assertThat(mapper.writeValueAsString(wire.getValue().arguments()))
        .contains("tourapi.place:2", "requested_stay_minutes")
        .doesNotContain(
            snapshot.ownerId().toString(),
            snapshot.input().tripId().toString(),
            "original_text",
            "coordinates");
    for (var field :
        List.of(
            "owner",
            "trip",
            "base",
            "parent",
            "hash",
            "day",
            "count",
            "runType",
            "contract",
            "algorithm")) {
      var body = command.restoreStructuredInput(mapper).deepCopy();
      if (field.equals("day")) ((ObjectNode) body).put("targetDayId", UUID.randomUUID().toString());
      if (field.equals("count")) ((ObjectNode) body).put("candidateCount", 2);
      var invalid =
          new com.timingjeju.api.application.commandinput.CommandInputSnapshot(
              field.equals("parent")
                  ? new com.timingjeju.api.application.commandinput.CommandInputParent.Generation(
                      UUID.randomUUID())
                  : parent,
              field.equals("runType") ? null : command.runType(),
              command.schemaVersion(),
              field.equals("contract") ? null : command.contractVersion(),
              field.equals("algorithm") ? null : command.algorithmVersion(),
              mapper.writeValueAsString(body),
              field.equals("hash") ? "0".repeat(64) : command.commandInputHash(),
              field.equals("owner") ? UUID.randomUUID() : command.ownerUserId(),
              field.equals("trip") ? UUID.randomUUID() : command.tripPlanId(),
              field.equals("base") ? UUID.randomUUID() : null);
      when(commands.find(parent)).thenReturn(java.util.Optional.of(invalid));
      assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
          .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    }
    when(snapshots.find(runId)).thenReturn(java.util.Optional.empty());
    assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThatThrownBy(() -> executor.execute(runId, clock.instant()))
        .isInstanceOf(com.timingjeju.api.application.asyncrun.RetryableRunException.class);
    verify(sdk, times(1)).callTool(any());
    when(snapshots.find(runId)).thenReturn(java.util.Optional.of(snapshot));
    when(commands.find(parent)).thenReturn(java.util.Optional.of(command));
    doThrow(new McpRemoteCallException("MCP_TIMEOUT", true)).when(sdk).callTool(any());
    assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
        .isInstanceOfSatisfying(
            com.timingjeju.api.application.asyncrun.RetryableRunException.class,
            failure -> assertThat(failure.stableErrorCode()).isEqualTo("MCP_TIMEOUT"));
    doThrow(new McpRemoteCallException("MCP_AUTHENTICATION_FAILED", false))
        .when(sdk)
        .callTool(any());
    assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
        .isInstanceOf(GenerationException.class)
        .hasMessage("MCP_AUTHENTICATION_FAILED");
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
    return GenerationCandidateProjection.from(response, input(60, false), bindings(), Set.of());
  }

  private GenerationPlaceBindings bindings() {
    var places = new ArrayList<GenerationPlaceBindings.Place>();
    for (int n = 1; n <= 6; n++)
      places.add(
          new GenerationPlaceBindings.Place(new UUID(79, n), Integer.toString(n), "합성 공식 장소"));
    return new GenerationPlaceBindings(places);
  }

  private GenerationTripInput input(int minutes, boolean avoid) {
    var dayId = new UUID(79, 101);
    var airportId = new UUID(79, 1);
    var requiredId = new UUID(79, 2);
    var start = OffsetDateTime.parse("2026-08-15T09:00:00+09:00");
    var end = OffsetDateTime.parse("2026-08-15T20:00:00+09:00");
    var preferences = new ArrayList<com.timingjeju.api.application.trip.TripPlacePreference>();
    var places = new ArrayList<GenerationTripInput.PlaceInput>();
    preferences.add(
        new com.timingjeju.api.application.trip.TripPlacePreference(
            requiredId, "must_visit", 1, 50, minutes));
    places.add(
        new GenerationTripInput.PlaceInput(
            requiredId, "must_visit", 50, minutes, "user_requested", null, null));
    if (avoid) {
      preferences.add(
          new com.timingjeju.api.application.trip.TripPlacePreference(
              new UUID(79, 3), "avoid", 1, 0, null));
      places.add(
          new GenerationTripInput.PlaceInput(new UUID(79, 3), "avoid", 0, null, null, null, null));
    }
    return new GenerationTripInput(
        new UUID(79, 100),
        1,
        null,
        new GenerationDayBoundary(dayId, 1, airportId, airportId, start, end),
        airportId,
        List.of(
            new com.timingjeju.api.application.trip.TripDay(
                dayId, 1, start.toLocalDate(), start.toLocalTime(), end.toLocalTime())),
        List.of(),
        preferences,
        List.of("walk", "bus", "taxi"),
        List.of(),
        false,
        places);
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
