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
  private static final Set<String> SOURCES = Set.of("travel.place-entrance-map", "tourapi.place");

  @Test
  void 결과_기준시각은_계획시각을_보존하며_후보가_부족해도_현재시각으로_바꾸지_않는다() throws Exception {
    var response = response();
    var planned = OffsetDateTime.parse(response.get("planning_context").get("planned_at").asText());
    assertThat(project(response).factsAsOf()).isEqualTo(planned.toInstant());
    ((ObjectNode) response).put("status", "insufficient_feasible_routes");
    ((ObjectNode) response).set("recommendations", mapper.createArrayNode());
    response.set(
        "failure",
        mapper
            .createObjectNode()
            .put("code", "insufficient_feasible_routes")
            .put("message", "합성 후보 부족"));
    var insufficient = project(response);
    assertThat(insufficient.outcome()).isEqualTo("insufficient_feasible_routes");
    assertThat(insufficient.factsAsOf()).isEqualTo(planned.toInstant());
    for (String invalid :
        List.of(
            "not-a-date",
            "2026-08-01T09:00:00",
            "2026-08-01T00:00:00Z",
            "9999-12-31T23:59:59+09:00")) {
      ((ObjectNode) response.get("planning_context")).put("planned_at", invalid);
      assertThatThrownBy(() -> project(response)).hasMessage("MCP_CONTRACT_INVALID");
    }
  }

  @Test
  void 후보_설명은_검증된_전략과_합계로_만들고_AI_자유문을_복사하지_않는다() throws Exception {
    var response = response();
    var value =
        (ObjectNode) response.get("recommendations").get(0).get("recommendation_reasons").get(0);
    value.put("text", "저장하지 않을 AI 자유문");
    var labels = Map.of("balanced", "균형형", "relaxed", "여유형", "experience_max", "경험 최대형");
    for (var candidate : project(response).candidates()) {
      assertThat(candidate.explanation())
          .isEqualTo(
              labels.get(candidate.strategy())
                  + " 일정 · 방문 "
                  + candidate.totals().visitMinutes()
                  + "분 · 이동 "
                  + candidate.totals().transferMinutes()
                  + "분 · 식사 "
                  + candidate.totals().mealMinutes()
                  + "분 · 휴식 "
                  + candidate.totals().restMinutes()
                  + "분 · 계획 버퍼 "
                  + candidate.totals().bufferMinutes()
                  + "분")
          .doesNotContain("저장하지 않을 AI 자유문");
    }
  }

  @Test
  void 이전날_이력은_상세_활동근거와_합계만_남기고_전체타임라인을_보내지_않는다() throws Exception {
    var response = response();
    ((ObjectNode) response.get("recommendations").get(0).get("timeline").get(1))
        .set("evidence_fact_ids", mapper.createArrayNode());
    var history = project(response).candidates().getFirst().history();
    assertThat(history.selectedPlaces()).hasSize(5);
    assertThat(history.selectedPlaces().getFirst().evidenceFactIds()).contains("fact-required");
    assertThat(history.evidenceFactIds()).containsAll(history.totals().evidenceFactIds());
    var wire = mapper.valueToTree(history.toMcp());
    assertThat(wire.properties())
        .extracting(java.util.Map.Entry::getKey)
        .containsExactlyInAnyOrder(
            "trip_date",
            "activity_window",
            "day_start_at",
            "day_end_at",
            "selected_places",
            "totals",
            "evidence_fact_ids");
    assertThat(wire.get("totals").has("derivation_evidence_fact_ids")).isTrue();
    var contract = mapper.readTree(resource("generation-v07.input-schema.json"));
    var historySchema = (ObjectNode) contract.get("$defs").get("SelectedDayHistory").deepCopy();
    historySchema.set("$defs", contract.get("$defs"));
    assertThat(
            com.networknt.schema.SchemaRegistry.withDefaultDialect(
                    com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                .getSchema(historySchema)
                .validate(wire))
        .isEmpty();
    assertThat(wire.get("day_start_at").asText()).isEqualTo("2026-08-15T09:00:00+09:00");
    assertThat(mapper.writeValueAsString(wire))
        .doesNotContain(
            "timeline", "geometry", "coordinates", "title", "original_text", "canonicalPlaceId");
  }

  @Test
  void 저장할_하루는_체류없는_양끝_기준점과_모든_활동_및_사이_버퍼를_보존한다() throws Exception {
    var candidate = project(response()).candidates().getFirst();
    var day = candidate.scheduleDay();
    assertThat(day.items()).hasSize(7);
    assertThat(day.items().getFirst().boundaryRole()).isEqualTo("day_start");
    assertThat(day.items().getLast().boundaryRole()).isEqualTo("day_end");
    assertThat(day.items().getFirst().startAt()).isEqualTo(day.items().getFirst().endAt());
    assertThat(day.items().getLast().startAt()).isEqualTo(day.items().getLast().endAt());
    assertThat(day.connections()).hasSize(6);
    assertThat(day.connections().get(1).events())
        .extracting(GenerationTimeline.Event::type)
        .containsExactly("transfer", "buffer");
    assertThat(day.connections().get(1).events().getLast().durationMinutes()).isEqualTo(60);
    assertThat(day.items().getLast().endAt())
        .isEqualTo(candidate.timeline().events().getLast().endAt());
  }

  @Test
  void 상위_이벤트근거가_비어도_필수_상세도보근거를_보존한다() throws Exception {
    var value = response();
    ((ObjectNode) value.get("recommendations").get(0).get("timeline").get(0))
        .set("evidence_fact_ids", mapper.createArrayNode());
    var result = project(value);
    assertThat(result.outcome()).isEqualTo("success");
    assertThat(result.candidates().getFirst().transfers().getFirst().evidenceFactIds())
        .contains("fact-route-hotel-required");
  }

  @Test
  void 버스_구간요금은_선택근거와_함께_범위로_보존한다() throws Exception {
    var value = response();
    var transfer = value.get("recommendations").get(2).get("timeline").get(0).get("transfer");
    var decision = (ObjectNode) transfer.get("mode_decision");
    decision.set(
        "bus_cost", mapper.readTree("{\"min_krw\":1200,\"max_krw\":1500,\"is_estimated\":true}"));
    var ids = mapper.createArrayNode().add("fact-route-hotel-port");
    decision.set("evidence_fact_ids", ids);
    var result = project(value).candidates().get(2).transfers().getFirst();
    assertThat(result.fare()).isEqualTo(new GenerationTotals.CostRange(1200, 1500, true));
    assertThat(result.evidenceFactIds()).contains("fact-route-hotel-port");
  }

  @Test
  void 후보의_이동은_선택된_구간의_수치와_근거만_보존하고_미지_버스요금을_만들지_않는다() throws Exception {
    var candidates = project(response()).candidates();
    var walk = candidates.getFirst().transfers().getFirst();
    assertThat(walk.mode()).isEqualTo("walk");
    assertThat(walk.walks()).hasSize(1);
    assertThat(walk.walks().getFirst().plannedMinutes()).isEqualTo(15);
    assertThat(walk.fare().minKrw()).isZero();
    var bus = candidates.get(2).transfers().getFirst();
    assertThat(bus.mode()).isEqualTo("bus");
    assertThat(bus.walks())
        .extracting(GenerationTransfer.Walk::kind)
        .containsExactly("access_walk", "egress_walk");
    assertThat(bus.rides()).hasSize(1);
    assertThat(bus.rides().getFirst().arrivalAt()).isAfter(bus.rides().getFirst().departureAt());
    assertThat(bus.fare()).isNull();
    assertThat(bus.evidenceFactIds()).isNotEmpty();
    assertThat(mapper.writeValueAsString(candidates))
        .doesNotContain(
            "geometry",
            "coordinates",
            "position",
            "original_text",
            "boarding_stop_name",
            "alternatives",
            "source_refs");
  }

  @Test
  void 대표좌표는_승인장소_근거와_잠정표시를_모두_요구한다() throws Exception {
    var result = response();
    var sources = (tools.jackson.databind.node.ArrayNode) result.get("data_sources");
    var source = (ObjectNode) sources.get(0).deepCopy();
    source.put("source_id", "tourapi.place");
    sources.add(source);
    var facts = (tools.jackson.databind.node.ArrayNode) result.get("evidence_facts");
    var fact = (ObjectNode) facts.get(facts.size() - 1).deepCopy();
    fact.put("fact_id", "tourapi.place:1");
    fact.put("category", "place");
    fact.set("source_refs", mapper.valueToTree(List.of(Map.of("source_id", "tourapi.place"))));
    facts.add(fact);
    var walk =
        (ObjectNode)
            result
                .get("recommendations")
                .get(0)
                .get("timeline")
                .get(0)
                .get("transfer")
                .get("direct_walk");
    walk.put("from_id", "place-point:tourapi.place:1");
    walk.put("entrance_verification", "PROVISIONAL_PLACE_POINT");
    ((tools.jackson.databind.node.ArrayNode) walk.get("evidence_fact_ids")).add("tourapi.place:1");
    assertThat(project(result).outcome()).isEqualTo("success");
    walk.put("entrance_verification", "VERIFIED");
    assertThat(project(result).outcome()).isEqualTo("insufficient_feasible_routes");
    walk.put("entrance_verification", "PROVISIONAL_PLACE_POINT");
    ((ObjectNode) fact.get("derivation")).put("kind", "policy");
    assertThat(project(result).outcome()).isEqualTo("insufficient_feasible_routes");
  }

  @Test
  void 입구근거가_없는_이동과_다른_도착장소는_거부한다() throws Exception {
    for (var field : List.of("to_id", "evidence_fact_ids")) {
      var result = response();
      var walk =
          (ObjectNode)
              result
                  .get("recommendations")
                  .get(0)
                  .get("timeline")
                  .get(0)
                  .get("transfer")
                  .get("direct_walk");
      if (field.equals("to_id")) walk.put(field, "test-entrance:tourapi.place:3");
      else walk.set(field, mapper.valueToTree(List.of("fact-route-hotel-required")));
      assertThat(project(result).outcome()).as(field).isEqualTo("insufficient_feasible_routes");
    }
  }

  @Test
  void 이동의_출발입구가_이전_장소와_다르면_후보를_모두_거부한다() throws Exception {
    var result = response();
    ((ObjectNode)
            result
                .get("recommendations")
                .get(0)
                .get("timeline")
                .get(0)
                .get("transfer")
                .get("direct_walk"))
        .put("from_id", "unrelated-entrance");
    assertThat(project(result).outcome()).isEqualTo("insufficient_feasible_routes");
  }

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
            GenerationCandidateProjection.from(response(), input(30, false), bindings(), SOURCES)
                .outcome())
        .isEqualTo("insufficient_feasible_routes");
    assertThat(
            GenerationCandidateProjection.from(response(), input(60, true), bindings(), SOURCES)
                .outcome())
        .isEqualTo("insufficient_feasible_routes");
    assertThat(
            GenerationCandidateProjection.from(response(), input(60, false), bindings(), SOURCES)
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
  void 택시_주행수치가_타임라인과_다르면_세_후보를_모두_거부한다() throws Exception {
    var result = response();
    var transfer =
        (ObjectNode) result.get("recommendations").get(0).get("timeline").get(0).get("transfer");
    transfer.put("mode", "taxi");
    transfer.putNull("direct_walk");
    transfer
        .putObject("taxi_alternative")
        .put("duration_minutes", 16)
        .put("distance_meters", 500)
        .put("fare_min_krw", 7000)
        .put("fare_max_krw", 9000)
        .put("is_estimated", true)
        .putArray("evidence_fact_ids")
        .add("fact-route-hotel-required");
    var projection = project(result);
    assertThat(projection.outcome()).isEqualTo("insufficient_feasible_routes");
    assertThat(projection.candidates()).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void 실제_AI_생성_Schema와_합성응답을_SDK에서_세_후보로_추출한다(boolean withPreviousDay) throws Exception {
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
    var dayInput = withPreviousDay ? nextDayInput() : input(60, false);
    var previousDays =
        withPreviousDay ? List.of(previousHistory()) : List.<GenerationSelectedDay>of();
    var history = mock(GenerationDayHistoryRepository.class);
    when(history.findPrevious(dayInput)).thenReturn(previousDays);
    var snapshot = GenerationTripSnapshot.create(runId, UUID.randomUUID(), dayInput, mapper);
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
                    snapshot.input().baseScheduleVersionId()));
    when(commands.find(parent)).thenReturn(java.util.Optional.of(command));
    var places = mock(GenerationPlaceResolver.class);
    when(places.resolve(anySet(), any())).thenReturn(bindings());
    when(places.resolveFactIds(anySet(), any())).thenReturn(bindings());
    var clock =
        java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-14T00:00:00Z"), java.time.ZoneOffset.UTC);
    var executor =
        new McpGenerationExecutor(
            snapshots, commands, places, client, mapper, clock, SOURCES, history);
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
    assertThat(mapper.valueToTree(wire.getValue().arguments()).at("/request/previous_days"))
        .isEqualTo(
            mapper.valueToTree(previousDays.stream().map(GenerationSelectedDay::toMcp).toList()));
    assertThat(wire.getValue().arguments()).containsKey("request").containsKey("inputHash");
    assertThat(mapper.writeValueAsString(wire.getValue().arguments()))
        .contains("tourapi.place:2", "requested_stay_minutes")
        .doesNotContain(
            snapshot.ownerId().toString(),
            snapshot.input().tripId().toString(),
            "original_text",
            "coordinates");
    int successfulCalls = 1;
    if (withPreviousDay) {
      var old = previousDays.getFirst();
      var repeated =
          new GenerationSelectedDay(
              old.dayId(),
              old.tripDate(),
              old.windowStartAt(),
              old.windowEndAt(),
              old.dayStartAt(),
              old.dayEndAt(),
              List.of(
                  new GenerationSelectedDay.SelectedPlace(
                      new UUID(79, 3), "tourapi.place:3", "visit", List.of("previous-place"))),
              old.totals(),
              old.evidenceFactIds());
      when(history.findPrevious(dayInput)).thenReturn(List.of(repeated));
      var rejected = executor.execute(runId, clock.instant().plusSeconds(180));
      assertThat(rejected.outcome()).isEqualTo("insufficient_feasible_routes");
      assertThat(rejected.candidates()).isEmpty();
      successfulCalls++;
      var requiredConflict =
          new GenerationSelectedDay(
              old.dayId(),
              old.tripDate(),
              old.windowStartAt(),
              old.windowEndAt(),
              old.dayStartAt(),
              old.dayEndAt(),
              List.of(
                  new GenerationSelectedDay.SelectedPlace(
                      new UUID(79, 2), "tourapi.place:2", "visit", List.of("previous-place"))),
              old.totals(),
              old.evidenceFactIds());
      when(history.findPrevious(dayInput)).thenReturn(List.of(requiredConflict));
      assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
      when(history.findPrevious(dayInput))
          .thenThrow(GenerationException.inputConstraintViolation());
      assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
      doReturn(previousDays).when(history).findPrevious(dayInput);
    }
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
              field.equals("base") ? UUID.randomUUID() : command.baseScheduleVersionId());
      when(commands.find(parent)).thenReturn(java.util.Optional.of(invalid));
      assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
          .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    }
    when(snapshots.find(runId)).thenReturn(java.util.Optional.empty());
    assertThatThrownBy(() -> executor.execute(runId, clock.instant().plusSeconds(180)))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    assertThatThrownBy(() -> executor.execute(runId, clock.instant()))
        .isInstanceOf(com.timingjeju.api.application.asyncrun.RetryableRunException.class);
    verify(sdk, times(successfulCalls)).callTool(any());
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
    return GenerationCandidateProjection.from(response, input(60, false), bindings(), SOURCES);
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

  private GenerationTripInput nextDayInput() {
    var current = input(60, false);
    var day = current.days().getFirst();
    var previousDayId = new UUID(79, 102);
    return new GenerationTripInput(
        current.tripId(),
        1,
        new UUID(79, 99),
        new GenerationDayBoundary(
            day.dayId(),
            2,
            current.airportPlaceId(),
            current.airportPlaceId(),
            current.boundary().startAt(),
            current.boundary().endAt()),
        current.airportPlaceId(),
        List.of(
            new com.timingjeju.api.application.trip.TripDay(
                previousDayId,
                1,
                day.date().minusDays(1),
                day.activityStartTime(),
                day.activityEndTime()),
            new com.timingjeju.api.application.trip.TripDay(
                day.dayId(), 2, day.date(), day.activityStartTime(), day.activityEndTime())),
        List.of(
            new com.timingjeju.api.application.trip.TripPlannerConditions.DayAnchor(
                previousDayId, current.airportPlaceId())),
        current.savedPreferences().stream()
            .map(
                p ->
                    new com.timingjeju.api.application.trip.TripPlacePreference(
                        p.placeId(), p.type(), 2, p.priority(), p.requestedStayMinutes()))
            .toList(),
        current.transportModes(),
        current.preferredCategories(),
        current.relaxedPace(),
        current.places());
  }

  private GenerationSelectedDay previousHistory() {
    var start = OffsetDateTime.parse("2026-08-14T09:00:00+09:00");
    var zero = new GenerationTotals.CostRange(0, 0, false);
    var totals =
        new GenerationTotals(
            60, 60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, zero, zero, zero, List.of("previous-total"));
    return new GenerationSelectedDay(
        new UUID(79, 102),
        start.toLocalDate(),
        start,
        start.plusHours(11),
        start,
        start.plusHours(1),
        List.of(
            new GenerationSelectedDay.SelectedPlace(
                new UUID(79, 999), "tourapi.place:previous", "visit", List.of("previous-place"))),
        totals,
        List.of("previous-place", "previous-total"));
  }

  private ObjectNode response() throws Exception {
    var response = (ObjectNode) mapper.readTree(resource("generation-v07.synthetic-output.json"));
    canonicalizeIds(response);
    addSyntheticEntranceBindings(response);
    return response;
  }

  private void addSyntheticEntranceBindings(ObjectNode response) {
    var source = mapper.createObjectNode();
    source.put("source_id", "travel.place-entrance-map");
    source.put("provider", "synthetic-test");
    source.putNull("dataset_version");
    source.putNull("data_as_of");
    source.put("retrieved_at", "2026-08-11T12:00:00+09:00");
    source.put("status", "ACTIVE");
    source.put("attribution_text", "합성 입구 연결 테스트");
    ((tools.jackson.databind.node.ArrayNode) response.get("data_sources")).add(source);
    var facts = (tools.jackson.databind.node.ArrayNode) response.get("evidence_facts");
    for (int index = 1; index <= 6; index++) {
      String place = "tourapi.place:" + index;
      var fact = (ObjectNode) facts.get(0).deepCopy();
      fact.put("fact_id", "test-entrance-fact:" + index);
      fact.put("category", "place_entrance");
      fact.set(
          "value",
          mapper.valueToTree(Map.of("entrance_id", "test-entrance:" + place, "place_id", place)));
      fact.set(
          "source_refs",
          mapper.valueToTree(List.of(Map.of("source_id", "travel.place-entrance-map"))));
      fact.set(
          "derivation", mapper.valueToTree(Map.of("kind", "source", "input_fact_ids", List.of())));
      facts.add(fact);
    }
    bindWalks(response.get("recommendations"));
  }

  private void bindWalks(tools.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      if (node.has("from_id") && node.has("to_id") && node.has("entrance_verification")) {
        var object = (ObjectNode) node;
        for (var field : List.of("from_id", "to_id")) {
          String id = node.get(field).asText();
          if (id.startsWith("entrance-")) id = canonicalId(id.substring("entrance-".length()));
          if (id.startsWith("tourapi.place:")) {
            object.put(field, "test-entrance:" + id);
            ((tools.jackson.databind.node.ArrayNode) node.get("evidence_fact_ids"))
                .add("test-entrance-fact:" + id.substring("tourapi.place:".length()));
          }
        }
      }
      for (var property : node.properties()) bindWalks(property.getValue());
    } else if (node.isArray()) node.forEach(this::bindWalks);
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
