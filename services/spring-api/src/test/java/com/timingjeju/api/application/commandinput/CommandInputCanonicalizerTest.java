package com.timingjeju.api.application.commandinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class CommandInputCanonicalizerTest {
  private static final UUID RUN_ID = UUID.fromString("10800000-0000-0000-0000-000000000001");
  private static final UUID OWNER_ID = UUID.fromString("10800000-0000-0000-0000-000000000002");
  private static final UUID TRIP_ID = UUID.fromString("10800000-0000-0000-0000-000000000003");
  private static final UUID BASE_ID = UUID.fromString("10800000-0000-0000-0000-000000000004");
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-24T00:00:00Z");
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CommandInputCanonicalizer canonicalizer =
      new CommandInputCanonicalizer(objectMapper);

  @Test
  void object_key_order가_달라도_canonical_JSON과_command_hash가_같다() throws Exception {
    var first = canonicalizer.canonicalize(generationRequest(false));
    var second = canonicalizer.canonicalize(generationRequest(true));

    assertThat(first.canonicalStructuredInput()).isEqualTo(second.canonicalStructuredInput());
    assertThat(first.commandInputHash())
        .isEqualTo(second.commandInputHash())
        .matches("[0-9a-f]{64}");
    assertThat(
            java.util.Arrays.stream(CommandInputSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .doesNotContain("mcpInputHash");
  }

  @Test
  void array_order나_command_contract가_달라지면_hash가_달라진다() throws Exception {
    var first = canonicalizer.canonicalize(revisionRequest("[\"MOVE_ITEM\",\"SHORTEN_STAY\"]"));
    var reordered = canonicalizer.canonicalize(revisionRequest("[\"SHORTEN_STAY\",\"MOVE_ITEM\"]"));
    var contractChanged =
        canonicalizer.canonicalize(
            new CommandInputRequest(
                first.parent(),
                first.runType(),
                first.schemaVersion(),
                "command/v2",
                first.algorithmVersion(),
                first.restoreStructuredInput(objectMapper),
                first.ownerUserId(),
                first.tripPlanId(),
                first.baseScheduleVersionId(),
                null));

    assertThat(first.commandInputHash()).isNotEqualTo(reordered.commandInputHash());
    assertThat(first.commandInputHash()).isNotEqualTo(contractChanged.commandInputHash());
  }

  @Test
  void structured_input은_object와_schema_1만_허용하고_민감키를_재귀적으로_거부한다() throws Exception {
    assertThatThrownBy(() -> canonicalizer.canonicalize(request("[1,2]", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON object");
    assertThatThrownBy(() -> canonicalizer.canonicalize(request("{}", null, "command/v1", 2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema version");

    for (String forbidden :
        new String[] {
          "raw_body", "requestBody", "providerResponse", "access", "phone", "latitude"
        }) {
      var request = request("{\"" + forbidden + "\":\"secret\"}", null);
      assertThatThrownBy(() -> canonicalizer.canonicalize(request))
          .as(forbidden)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("closed schema");
    }
  }

  @Test
  void run_type과_schema_version별_closed_projection만_허용한다() throws Exception {
    var feasibility = request("{\"refreshExternalFacts\":false}", null);
    assertThat(canonicalizer.canonicalize(feasibility).canonicalStructuredInput())
        .isEqualTo("{\"refreshExternalFacts\":false}");

    var generation =
        baseRequest(
            new CommandInputParent.Generation(RUN_ID),
            "itinerary_generation",
            "{\"targetDayId\":\"10800000-0000-0000-0000-000000000005\",\"candidateCount\":3,\"refreshExternalFacts\":true}",
            null);
    assertThat(canonicalizer.canonicalize(generation).runType()).isEqualTo("itinerary_generation");

    var nestedAlias =
        request("{\"refreshExternalFacts\":false,\"access\":{\"token\":\"x\"}}", null);
    assertThatThrownBy(() -> canonicalizer.canonicalize(nestedAlias))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("closed schema");
  }

  @Test
  void spare_time_timestamp는_Java와_DB_공통_canonical_RFC3339_subset만_허용한다() throws Exception {
    for (String timestamp :
        new String[] {
          "0001-01-01T00:00:00Z", "2000-02-29T23:59:59.123456789+18:00", "9999-12-31T23:59:59-18:00"
        }) {
      assertThat(canonicalizer.canonicalize(spareTimeRequest(timestamp, timestamp)).runType())
          .as(timestamp)
          .isEqualTo("spare_time");
    }
    for (String timestamp :
        new String[] {
          "0000-01-01T00:00:00Z",
          "2025-02-29T00:00:00Z",
          "2026-01-01T24:00:00Z",
          "2026-01-01T00:00:00.1234567890Z",
          "2026-01-01T00:00:00+18:01",
          "2026-01-01T00:00:00-18:01",
          "2026-01-01T00:00:00+19:00"
        }) {
      assertThatThrownBy(() -> canonicalizer.canonicalize(spareTimeRequest(timestamp, timestamp)))
          .as(timestamp)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("closed schema");
    }
    assertThat(
            canonicalizer
                .canonicalize(spareTimeRequest("2026-01-01T00:00:00+18:00", "2025-12-31T23:00:00Z"))
                .runType())
        .isEqualTo("spare_time");
    assertThatThrownBy(
            () ->
                canonicalizer.canonicalize(
                    spareTimeRequest("2026-01-01T00:00:00-18:00", "2026-01-01T23:00:00+18:00")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parent_kind와_run_type은_compute_generation_revision_matrix에서_정확히_일치한다() throws Exception {
    assertThat(canonicalizer.canonicalize(request(validFeasibility(), null)).parent())
        .isInstanceOf(CommandInputParent.Compute.class);

    var generation = generationRequest(false);
    var revision = revisionRequest("[]");
    assertThat(canonicalizer.canonicalize(generation).parent())
        .isInstanceOf(CommandInputParent.Generation.class);
    assertThat(canonicalizer.canonicalize(revision).parent())
        .isInstanceOf(CommandInputParent.ScheduleRevision.class);

    var mismatch =
        baseRequest(
            new CommandInputParent.Generation(RUN_ID), "feasibility", validFeasibility(), null);
    assertThatThrownBy(() -> canonicalizer.canonicalize(mismatch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent");
  }

  @Test
  void coarse_location은_GRID_100M_PLACE_STOP만_허용하고_hash에는_최초_digest를_포함한다() throws Exception {
    var grid =
        new CommandLocation(
            new CoarseLocation.Grid100m(333, 777), "1.0.0", OBSERVED_AT, OBSERVED_AT, null, null);
    var place =
        new CommandLocation(
            new CoarseLocation.Place(UUID.randomUUID()),
            "1.0.0",
            OBSERVED_AT,
            OBSERVED_AT,
            null,
            null);
    var stop =
        new CommandLocation(
            new CoarseLocation.Stop(UUID.randomUUID()),
            "1.0.0",
            OBSERVED_AT,
            OBSERVED_AT,
            null,
            null);

    assertThat(
            canonicalizer
                .canonicalize(request(validFeasibility(), grid))
                .location()
                .orElseThrow()
                .precisionMeters())
        .isEqualTo(100);
    assertThat(
            canonicalizer
                .canonicalize(request(validFeasibility(), place))
                .location()
                .orElseThrow()
                .precisionMeters())
        .isNull();
    assertThat(
            canonicalizer
                .canonicalize(request(validFeasibility(), stop))
                .location()
                .orElseThrow()
                .precisionMeters())
        .isNull();
    assertThat(canonicalizer.canonicalize(request(validFeasibility(), grid)).commandInputHash())
        .isNotEqualTo(
            canonicalizer.canonicalize(request(validFeasibility(), null)).commandInputHash());
  }

  @Test
  void 도착한_terminal과_trip_end만_사용해_24시간_cutoff를_계산하고_미래_anchor는_제외한다() throws Exception {
    Instant evaluatedAt = Instant.parse("2026-08-24T12:00:00Z");
    Instant terminalAt = Instant.parse("2026-08-24T10:00:00Z");
    Instant futureTripEnd = Instant.parse("2026-08-25T00:00:00Z");
    var location =
        new CommandLocation(
            new CoarseLocation.Grid100m(1, 2),
            "1.0.0",
            OBSERVED_AT,
            evaluatedAt,
            terminalAt,
            futureTripEnd);

    var snapshot = canonicalizer.canonicalize(request(validFeasibility(), location));
    assertThat(snapshot.location().orElseThrow().expiresAt())
        .contains(Instant.parse("2026-08-25T10:00:00Z"));
    assertThat(snapshot.isLocationDue(Instant.parse("2026-08-25T09:59:59.999999Z"))).isFalse();
    assertThat(snapshot.isLocationDue(Instant.parse("2026-08-25T10:00:00Z"))).isTrue();

    var noArrivedAnchor =
        new CommandLocation(
            new CoarseLocation.Grid100m(1, 2),
            "1.0.0",
            OBSERVED_AT,
            evaluatedAt,
            null,
            futureTripEnd);
    assertThat(
            canonicalizer
                .canonicalize(request(validFeasibility(), noArrivedAnchor))
                .location()
                .orElseThrow()
                .expiresAt())
        .isEmpty();
  }

  @Test
  void snapshot은_원본_JsonNode_변경과_무관하게_restart에서_동일하게_복원된다() throws Exception {
    var mutable =
        (tools.jackson.databind.node.ObjectNode)
            objectMapper.readTree("{\"refreshExternalFacts\":false}");
    var request =
        baseRequest(
            new CommandInputParent.Compute(RUN_ID), "feasibility", mutable, null, "command/v1", 1);
    var snapshot = canonicalizer.canonicalize(request);
    mutable.set("refreshExternalFacts", objectMapper.readTree("true"));

    assertThat(snapshot.canonicalStructuredInput()).isEqualTo("{\"refreshExternalFacts\":false}");
    assertThat(snapshot.restoreStructuredInput(objectMapper))
        .isEqualTo(objectMapper.readTree("{\"refreshExternalFacts\":false}"));
  }

  private CommandInputRequest generationRequest(boolean reversed) throws Exception {
    String json =
        reversed
            ? "{\"refreshExternalFacts\":true,\"candidateCount\":3,\"targetDayId\":\"10800000-0000-0000-0000-000000000005\"}"
            : "{\"targetDayId\":\"10800000-0000-0000-0000-000000000005\",\"candidateCount\":3,\"refreshExternalFacts\":true}";
    return baseRequest(
        new CommandInputParent.Generation(RUN_ID), "itinerary_generation", json, null);
  }

  private CommandInputRequest revisionRequest(String instructionCodes) throws Exception {
    return baseRequest(
        new CommandInputParent.ScheduleRevision(RUN_ID),
        "schedule_revision",
        "{\"targetDayId\":\"10800000-0000-0000-0000-000000000005\",\"affectedItemIds\":[],\"instructionCodes\":"
            + instructionCodes
            + "}",
        null);
  }

  private CommandInputRequest spareTimeRequest(String windowStart, String windowEnd)
      throws Exception {
    return baseRequest(
        new CommandInputParent.Compute(RUN_ID),
        "spare_time",
        "{\"targetDayId\":\"10800000-0000-0000-0000-000000000005\",\"windowStart\":\""
            + windowStart
            + "\",\"windowEnd\":\""
            + windowEnd
            + "\"}",
        null);
  }

  private static String validFeasibility() {
    return "{\"refreshExternalFacts\":false}";
  }

  private CommandInputRequest request(String json, CommandLocation location) throws Exception {
    return request(json, location, "command/v1");
  }

  private CommandInputRequest request(String json, CommandLocation location, String contractVersion)
      throws Exception {
    return request(json, location, contractVersion, 1);
  }

  private CommandInputRequest request(
      String json, CommandLocation location, String contractVersion, int schemaVersion)
      throws Exception {
    return baseRequest(
        new CommandInputParent.Compute(RUN_ID),
        "feasibility",
        objectMapper.readTree(json),
        location,
        contractVersion,
        schemaVersion);
  }

  private CommandInputRequest baseRequest(
      CommandInputParent parent, String runType, String json, CommandLocation location)
      throws Exception {
    return baseRequest(parent, runType, objectMapper.readTree(json), location, "command/v1", 1);
  }

  private CommandInputRequest baseRequest(
      CommandInputParent parent,
      String runType,
      tools.jackson.databind.JsonNode input,
      CommandLocation location,
      String contractVersion,
      int schemaVersion) {
    return new CommandInputRequest(
        parent,
        runType,
        schemaVersion,
        contractVersion,
        "algorithm/v1",
        input,
        OWNER_ID,
        TRIP_ID,
        BASE_ID,
        location);
  }
}
